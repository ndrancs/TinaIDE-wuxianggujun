package com.wuxianggujun.tinaide.storage

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ProviderInfo
import android.net.Uri
import android.webkit.MimeTypeMap
import com.wuxianggujun.tinaide.core.i18n.Strings
import com.wuxianggujun.tinaide.core.i18n.strOr
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber

private const val TAG = "ExternalFileIntents"
private const val FILE_PROVIDER_META_DATA = "android.support.FILE_PROVIDER_PATHS"
private const val FILE_PROVIDER_AUTHORITY_SUFFIX = ".fileprovider"

object ExternalFileIntents {

    fun fileProviderAuthority(context: Context): String = "${context.packageName}$FILE_PROVIDER_AUTHORITY_SUFFIX"

    fun getShareableUri(context: Context, file: File): Uri {
        val authority = fileProviderAuthority(context)
        return runCatching {
            TinaFileProvider.getUriForFile(context, authority, file)
        }.onFailure { error ->
            logFileProviderDiagnostics(context, file, error)
        }.getOrThrow()
    }

    fun logFileProviderDiagnostics(
        context: Context,
        file: File? = null,
        throwable: Throwable? = null
    ) {
        val authority = fileProviderAuthority(context)
        val packageManager = context.packageManager
        val providerInfo = packageManager.resolveContentProvider(
            authority,
            PackageManager.GET_META_DATA
        )

        if (providerInfo == null) {
            Timber.tag(TAG).e(
                throwable,
                "FileProvider missing: authority=%s package=%s file=%s exists=%s size=%d",
                authority,
                context.packageName,
                file?.absolutePath,
                file?.exists(),
                file?.takeIf(File::exists)?.length() ?: -1L
            )
            return
        }

        val pathsXmlLoaded = providerInfo.canLoadFileProviderPaths(packageManager)
        val usesExplicitPathResource = providerInfo.name == TinaFileProvider::class.java.name
        val metaDataKeys = providerInfo.metaData?.keySet()?.joinToString().orEmpty()
        Timber.tag(TAG).i(
            "FileProvider diagnostics: authority=%s provider=%s app=%s exported=%s grantUriPermissions=%s metaDataKeys=[%s] pathsXmlLoaded=%s usesExplicitPathResource=%s file=%s exists=%s size=%d",
            authority,
            providerInfo.name,
            providerInfo.packageName,
            providerInfo.exported,
            providerInfo.grantUriPermissions,
            metaDataKeys,
            pathsXmlLoaded,
            usesExplicitPathResource,
            file?.absolutePath,
            file?.exists(),
            file?.takeIf(File::exists)?.length() ?: -1L
        )

        if (!pathsXmlLoaded && !usesExplicitPathResource) {
            Timber.tag(TAG).e(
                throwable,
                "FileProvider meta-data is unavailable: authority=%s provider=%s requiredMetaData=%s",
                authority,
                providerInfo.name,
                FILE_PROVIDER_META_DATA
            )
        } else if (!pathsXmlLoaded) {
            Timber.tag(TAG).w(
                "FileProvider manifest meta-data is unavailable; using explicit TinaFileProvider path resource: authority=%s provider=%s",
                authority,
                providerInfo.name
            )
        }
    }

    suspend fun openWithExternalApp(
        context: Context,
        file: File,
        onError: (String) -> Unit
    ) {
        if (!file.exists()) {
            onError(Strings.toast_file_not_exist.strOr(context))
            return
        }
        if (file.isDirectory) {
            onError(Strings.toast_open_with_dir_not_supported.strOr(context))
            return
        }

        val shareable = ensureShareableFile(context, file).getOrElse { e ->
            onError(Strings.toast_open_with_failed.strOr(context, e.message ?: Strings.error_unknown.strOr(context)))
            return
        }

        withContext(Dispatchers.Main) {
            runCatching {
                val uri = getShareableUri(context, shareable)
                val mime = guessMimeType(shareable) ?: "*/*"
                val intent = Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(uri, mime)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                val chooser = Intent.createChooser(intent, Strings.cmd_file_open_with.strOr(context))
                chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(chooser)
            }.onFailure { e ->
                onError(Strings.toast_open_with_failed.strOr(context, e.message ?: Strings.error_unknown.strOr(context)))
            }
        }
    }

    suspend fun shareFileOrDirectory(
        context: Context,
        file: File,
        onInfo: (String) -> Unit,
        onError: (String) -> Unit
    ) {
        if (!file.exists()) {
            onError(Strings.toast_file_not_exist.strOr(context))
            return
        }

        if (file.isDirectory) {
            onInfo(Strings.toast_exporting.strOr(context))
            val result = ProjectExporter.exportProject(context, file, progressListener = null)
            when (result) {
                is ProjectExporter.ExportResult.Success -> {
                    runCatching {
                        ProjectExporter.shareZipFile(context, result.zipFile, file.name)
                    }.onFailure { e ->
                        onError(Strings.toast_share_failed.strOr(context, e.message ?: Strings.error_unknown.strOr(context)))
                    }
                }
                is ProjectExporter.ExportResult.Error -> {
                    onError(result.message)
                }
            }
            return
        }

        val shareable = ensureShareableFile(context, file).getOrElse { e ->
            onError(Strings.toast_share_failed.strOr(context, e.message ?: Strings.error_unknown.strOr(context)))
            return
        }

        withContext(Dispatchers.Main) {
            runCatching {
                val uri = getShareableUri(context, shareable)
                val mime = guessMimeType(shareable) ?: "*/*"
                val intent = Intent(Intent.ACTION_SEND).apply {
                    type = mime
                    putExtra(Intent.EXTRA_STREAM, uri)
                    putExtra(Intent.EXTRA_SUBJECT, shareable.name)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                val chooser = Intent.createChooser(intent, Strings.cmd_file_share.strOr(context))
                chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(chooser)
            }.onFailure { e ->
                onError(Strings.toast_share_failed.strOr(context, e.message ?: Strings.error_unknown.strOr(context)))
            }
        }
    }

    suspend fun ensureShareableFile(context: Context, file: File): Result<File> = withContext(Dispatchers.IO) {
        runCatching {
            // 尝试直接通过 FileProvider 生成 Uri（若路径不在 provider 映射中会抛异常）
            getShareableUri(context, file)
            file
        }.recoverCatching { error ->
            if (error.isFileProviderConfigurationError()) {
                throw IllegalStateException(
                    Strings.file_provider_config_invalid.strOr(context),
                    error
                )
            }

            Timber.tag(TAG).w(
                error,
                "File is outside FileProvider paths; copying to cache exports: source=%s",
                file.absolutePath
            )
            copyToCacheExports(context, file).also { copiedFile ->
                getShareableUri(context, copiedFile)
                Timber.tag(TAG).i(
                    "Prepared shareable cache file: source=%s target=%s size=%d",
                    file.absolutePath,
                    copiedFile.absolutePath,
                    copiedFile.length()
                )
            }
        }
    }

    private fun copyToCacheExports(context: Context, file: File): File {
        val exportsDir = File(context.cacheDir, "exports")
        exportsDir.mkdirs()
        val safeName = file.name.ifBlank { "shared_${System.currentTimeMillis()}" }
        val out = File(exportsDir, "${System.currentTimeMillis()}_$safeName")
        file.inputStream().use { input ->
            out.outputStream().use { output ->
                input.copyTo(output)
            }
        }
        return out
    }

    private fun guessMimeType(file: File): String? {
        val ext = file.extension.lowercase()
        if (ext.isBlank()) return null
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext)
    }

    private fun ProviderInfo.canLoadFileProviderPaths(packageManager: PackageManager): Boolean {
        val parser = runCatching {
            loadXmlMetaData(packageManager, FILE_PROVIDER_META_DATA)
        }.getOrNull()
        return try {
            parser != null
        } finally {
            parser?.close()
        }
    }

    private fun Throwable.isFileProviderConfigurationError(): Boolean {
        var current: Throwable? = this
        while (current != null) {
            val message = current.message.orEmpty()
            if (message.contains(FILE_PROVIDER_META_DATA, ignoreCase = true) ||
                (
                    message.contains("meta-data", ignoreCase = true) &&
                        message.contains("provider", ignoreCase = true)
                    )
            ) {
                return true
            }
            current = current.cause
        }
        return false
    }
}
