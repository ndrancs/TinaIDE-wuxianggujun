package com.wuxianggujun.tinaide.storage

import android.content.ContentResolver
import android.content.Context
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import androidx.annotation.Keep
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.wuxianggujun.tinaide.core.storage.R
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException

/**
 * TinaIDE 文件分享 Provider。
 *
 * 生成和读取 `content://` URI 都使用内置路径表，避免部分 Release 环境下
 * PackageManager 返回的 ProviderInfo 缺失 FILE_PROVIDER_PATHS meta-data。
 */
@Keep
class TinaFileProvider : FileProvider(R.xml.file_paths) {

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?
    ): Cursor {
        val file = getFileForUri(requireProviderContext(), uri)
        val requestedColumns = projection ?: DEFAULT_COLUMNS
        val resultColumns = mutableListOf<String>()
        val values = mutableListOf<Any?>()

        requestedColumns.forEach { column ->
            when (column) {
                OpenableColumns.DISPLAY_NAME -> {
                    resultColumns += OpenableColumns.DISPLAY_NAME
                    values += file.name
                }
                OpenableColumns.SIZE -> {
                    resultColumns += OpenableColumns.SIZE
                    values += file.length()
                }
            }
        }

        return MatrixCursor(resultColumns.toTypedArray(), 1).apply {
            addRow(values.toTypedArray())
        }
    }

    override fun getType(uri: Uri): String {
        val file = getFileForUri(requireProviderContext(), uri)
        val extension = file.extension.lowercase().takeIf(String::isNotBlank)
        return extension
            ?.let { MimeTypeMap.getSingleton().getMimeTypeFromExtension(it) }
            ?: "application/octet-stream"
    }

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        val file = getFileForUri(requireProviderContext(), uri)
        if (!file.exists()) {
            throw FileNotFoundException(file.absolutePath)
        }
        return ParcelFileDescriptor.open(file, modeToMode(mode))
    }

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int {
        val file = getFileForUri(requireProviderContext(), uri)
        return if (file.delete()) 1 else 0
    }

    private fun requireProviderContext(): Context = context ?: error("TinaFileProvider is not attached to a Context")

    companion object {
        private const val ROOT_EXPORTS = "exports"
        private const val ROOT_CACHE = "cache"
        private const val ROOT_EXTERNAL_EXPORTS = "external_exports"
        private const val ROOT_FILES = "files"
        private const val ROOT_EXTERNAL_FILES = "external_files"

        private val DEFAULT_COLUMNS = arrayOf(
            OpenableColumns.DISPLAY_NAME,
            OpenableColumns.SIZE
        )

        fun getUriForFile(context: Context, authority: String, file: File): Uri {
            val targetFile = file.canonicalFileOrThrow()
            val root = fileProviderRoots(context)
                .filter { providerRoot -> targetFile.isInside(providerRoot.directory) }
                .maxByOrNull { providerRoot -> providerRoot.directory.path.length }
                ?: throw IllegalArgumentException(
                    "File is outside TinaFileProvider configured paths: ${file.absolutePath}"
                )

            val relativePath = targetFile.relativePathFrom(root.directory)
            val encodedPath = Uri.encode(root.name) + "/" + Uri.encode(relativePath, "/")
            return Uri.Builder()
                .scheme(ContentResolver.SCHEME_CONTENT)
                .authority(authority)
                .encodedPath(encodedPath)
                .build()
        }

        private fun getFileForUri(context: Context, uri: Uri): File {
            val pathSegments = uri.pathSegments
            if (pathSegments.isEmpty()) {
                throw IllegalArgumentException("Invalid TinaFileProvider URI: $uri")
            }

            val rootName = pathSegments.first()
            val relativePath = pathSegments.drop(1).joinToString("/")
            val root = fileProviderRoots(context).firstOrNull { providerRoot ->
                providerRoot.name == rootName
            } ?: throw IllegalArgumentException("Unknown TinaFileProvider root: $rootName")

            val file = File(root.directory, relativePath).canonicalFileOrThrow()
            if (!file.isInside(root.directory)) {
                throw SecurityException("Resolved path jumped beyond configured root: $uri")
            }
            return file
        }

        private fun fileProviderRoots(context: Context): List<ProviderRoot> = buildList {
            add(ProviderRoot(ROOT_EXPORTS, File(context.cacheDir, ROOT_EXPORTS)))
            add(ProviderRoot(ROOT_CACHE, context.cacheDir))
            ContextCompat.getExternalCacheDirs(context).firstOrNull()?.let { externalCacheDir ->
                add(ProviderRoot(ROOT_EXTERNAL_EXPORTS, File(externalCacheDir, ROOT_EXPORTS)))
            }
            add(ProviderRoot(ROOT_FILES, context.filesDir))
            ContextCompat.getExternalFilesDirs(context, null).firstOrNull()?.let { externalFilesDir ->
                add(ProviderRoot(ROOT_EXTERNAL_FILES, externalFilesDir))
            }
        }.map { providerRoot ->
            providerRoot.copy(directory = providerRoot.directory.canonicalFileOrThrow())
        }

        private fun modeToMode(mode: String): Int = when (mode) {
            "r" -> ParcelFileDescriptor.MODE_READ_ONLY
            "w", "wt" ->
                ParcelFileDescriptor.MODE_WRITE_ONLY or
                    ParcelFileDescriptor.MODE_CREATE or
                    ParcelFileDescriptor.MODE_TRUNCATE
            "wa" ->
                ParcelFileDescriptor.MODE_WRITE_ONLY or
                    ParcelFileDescriptor.MODE_CREATE or
                    ParcelFileDescriptor.MODE_APPEND
            "rw" ->
                ParcelFileDescriptor.MODE_READ_WRITE or
                    ParcelFileDescriptor.MODE_CREATE
            "rwt" ->
                ParcelFileDescriptor.MODE_READ_WRITE or
                    ParcelFileDescriptor.MODE_CREATE or
                    ParcelFileDescriptor.MODE_TRUNCATE
            else -> throw IllegalArgumentException("Invalid mode: $mode")
        }

        private fun File.canonicalFileOrThrow(): File = try {
            canonicalFile
        } catch (error: IOException) {
            throw IllegalArgumentException("Failed to resolve canonical path: $absolutePath", error)
        }

        private fun File.isInside(root: File): Boolean {
            val rootPath = root.path.trimEnd('/')
            val targetPath = path
            return targetPath == rootPath || targetPath.startsWith("$rootPath/")
        }

        private fun File.relativePathFrom(root: File): String {
            val rootPath = root.path.trimEnd('/')
            val targetPath = path
            return when {
                targetPath == rootPath -> name
                rootPath == "" -> targetPath.trimStart('/')
                else -> targetPath.substring(rootPath.length + 1)
            }
        }
    }

    private data class ProviderRoot(
        val name: String,
        val directory: File
    )
}
