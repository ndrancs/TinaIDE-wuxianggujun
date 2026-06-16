package com.wuxianggujun.tinaide.storage

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import com.wuxianggujun.tinaide.core.common.io.TarExtractor
import com.wuxianggujun.tinaide.core.i18n.Strings
import com.wuxianggujun.tinaide.core.i18n.strOr
import java.io.File
import java.util.UUID
import java.util.zip.ZipFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber

object ProjectImporter {

    private const val TAG = "ProjectImporter"

    private val tarSuffixes = listOf(
        ".tar.gz",
        ".tgz",
        ".tar.xz",
        ".txz",
        ".tar.zst",
        ".tar"
    )

    suspend fun importDirectory(
        context: Context,
        uri: Uri,
        projectsRoot: File,
        projectLocationManager: ProjectLocationManager,
        storageManager: StorageManager
    ): Result<File> = withContext(Dispatchers.IO) {
        runCatching {
            takePersistablePermission(
                context = context,
                uri = uri,
                flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )

            val projectDir = resolveDirectoryFromTreeUri(context, uri)
                ?.takeIf { it.exists() && it.isDirectory }
                ?: throw IllegalArgumentException(Strings.error_import_directory_not_supported.strOr(context))

            if (!storageManager.canAccessProjectDir(projectDir)) {
                throw IllegalArgumentException(Strings.permission_storage_settings.strOr(context))
            }

            if ((!projectsRoot.exists() && !projectsRoot.mkdirs()) || !projectsRoot.isDirectory) {
                throw IllegalStateException(Strings.error_create_project_dir.strOr(context))
            }

            val targetName = sanitizeProjectName(projectDir.name)
            val targetDir = File(projectsRoot, targetName)
            val sourcePath = runCatching { projectDir.canonicalPath }.getOrElse { projectDir.absolutePath }
            val targetPath = runCatching { targetDir.canonicalPath }.getOrElse { targetDir.absolutePath }

            if (sourcePath == targetPath) {
                projectLocationManager.registerProject(projectDir)
                return@runCatching projectDir
            }

            if (targetDir.exists()) {
                throw IllegalArgumentException(Strings.error_project_name_exists.strOr(context))
            }

            val stagingDir = File(projectsRoot, ".import-${UUID.randomUUID()}")
            val copiedProjectDir = File(stagingDir, targetName)
            if (!stagingDir.mkdirs()) {
                throw IllegalStateException(Strings.toast_import_failed.strOr(context))
            }

            try {
                val copied = projectDir.copyRecursively(
                    target = copiedProjectDir,
                    overwrite = false
                ) { _, _ ->
                    OnErrorAction.TERMINATE
                }
                if (!copied) {
                    throw IllegalStateException(Strings.toast_import_failed.strOr(context))
                }

                if (!copiedProjectDir.renameTo(targetDir)) {
                    throw IllegalStateException(Strings.toast_import_failed.strOr(context))
                }

                projectLocationManager.registerProject(targetDir)
                targetDir
            } finally {
                if (stagingDir.exists()) {
                    stagingDir.deleteRecursively()
                }
            }
        }
    }

    suspend fun importArchive(
        context: Context,
        uri: Uri,
        projectsRoot: File,
        projectLocationManager: ProjectLocationManager
    ): Result<File> = withContext(Dispatchers.IO) {
        runCatching {
            takePersistablePermission(
                context = context,
                uri = uri,
                flags = Intent.FLAG_GRANT_READ_URI_PERMISSION
            )

            if ((!projectsRoot.exists() && !projectsRoot.mkdirs()) || !projectsRoot.isDirectory) {
                throw IllegalStateException(Strings.error_create_project_dir.strOr(context))
            }

            val displayName = queryDisplayName(context, uri)
                ?.takeIf { it.isNotBlank() }
                ?: "imported_project.zip"
            val tempArchive = copyArchiveToCache(context, uri, displayName)
            val stagingDir = File(projectsRoot, ".import-${UUID.randomUUID()}")
            var importedProjectDir: File? = null

            if (!stagingDir.mkdirs()) {
                tempArchive.delete()
                throw IllegalStateException(Strings.toast_import_failed.strOr(context))
            }

            try {
                extractArchive(context, tempArchive, stagingDir, displayName)
                importedProjectDir = finalizeImportedProject(context, stagingDir, projectsRoot, displayName)
                projectLocationManager.registerProject(importedProjectDir)
                importedProjectDir
            } catch (t: Throwable) {
                importedProjectDir?.deleteRecursively()
                throw t
            } finally {
                runCatching { tempArchive.delete() }
                if (stagingDir.exists()) {
                    stagingDir.deleteRecursively()
                }
            }
        }
    }

    private fun copyArchiveToCache(context: Context, uri: Uri, displayName: String): File {
        val cacheDir = File(context.cacheDir, "project-imports").apply { mkdirs() }
        val tempArchive = File(
            cacheDir,
            "project-import-${System.currentTimeMillis()}-${sanitizeFileName(displayName)}"
        )

        context.contentResolver.openInputStream(uri)?.use { input ->
            tempArchive.outputStream().use { output ->
                input.copyTo(output)
            }
        } ?: throw IllegalArgumentException(Strings.error_invalid_project_archive.strOr(context))

        return tempArchive
    }

    private fun extractArchive(
        context: Context,
        archiveFile: File,
        targetDir: File,
        displayName: String
    ) {
        when (detectArchiveKind(archiveFile, displayName)) {
            ArchiveKind.ZIP -> extractZip(archiveFile, targetDir)
            ArchiveKind.TAR -> TarExtractor.extract(archiveFile, targetDir)
            null -> throw IllegalArgumentException(Strings.error_invalid_project_archive.strOr(context))
        }
    }

    private fun finalizeImportedProject(
        context: Context,
        stagingDir: File,
        projectsRoot: File,
        displayName: String
    ): File {
        val topLevelEntries = stagingDir.listFiles()
            ?.filterNot(::shouldIgnoreTopLevelEntry)
            .orEmpty()

        if (topLevelEntries.isEmpty()) {
            throw IllegalArgumentException(Strings.error_project_archive_empty.strOr(context))
        }

        val sourceDir = topLevelEntries.singleOrNull()
            ?.takeIf { it.isDirectory }
            ?: stagingDir
        val projectName = sanitizeProjectName(
            if (sourceDir == stagingDir) stripArchiveSuffix(displayName) else sourceDir.name
        )
        val targetDir = File(projectsRoot, projectName)

        if (targetDir.exists()) {
            throw IllegalArgumentException(Strings.error_project_name_exists.strOr(context))
        }

        if (!sourceDir.renameTo(targetDir)) {
            throw IllegalStateException(Strings.toast_import_failed.strOr(context))
        }

        if (sourceDir != stagingDir && stagingDir.exists()) {
            stagingDir.deleteRecursively()
        }

        return targetDir
    }

    private fun extractZip(archiveFile: File, targetDir: File) {
        ZipFile(archiveFile).use { zip ->
            val entries = zip.entries()
            while (entries.hasMoreElements()) {
                val entry = entries.nextElement()
                val safeName = sanitizeArchivePath(entry.name)
                if (safeName.isBlank()) continue

                val entryFile = File(targetDir, safeName)
                if (entry.isDirectory) {
                    entryFile.mkdirs()
                } else {
                    entryFile.parentFile?.mkdirs()
                    zip.getInputStream(entry).use { input ->
                        entryFile.outputStream().use { output ->
                            input.copyTo(output)
                        }
                    }
                }
            }
        }
    }

    private fun sanitizeArchivePath(path: String): String {
        var normalized = path.replace('\\', '/')
        while (normalized.startsWith("./")) normalized = normalized.removePrefix("./")
        while (normalized.startsWith("/")) normalized = normalized.removePrefix("/")

        if (normalized == ".." || normalized.startsWith("../") || normalized.contains("/../")) {
            throw IllegalArgumentException("Invalid archive path: $path")
        }
        return normalized
    }

    private fun detectArchiveKind(archiveFile: File, displayName: String): ArchiveKind? {
        val lowerName = displayName.lowercase()
        return when {
            lowerName.endsWith(".zip") -> ArchiveKind.ZIP
            tarSuffixes.any { lowerName.endsWith(it) } -> ArchiveKind.TAR
            isZipFile(archiveFile) -> ArchiveKind.ZIP
            else -> null
        }
    }

    private fun isZipFile(file: File): Boolean {
        val header = ByteArray(4)
        val bytesRead = file.inputStream().use { input ->
            input.read(header)
        }
        return bytesRead >= 4 &&
            header[0] == 0x50.toByte() &&
            header[1] == 0x4B.toByte() &&
            header[2] == 0x03.toByte() &&
            header[3] == 0x04.toByte()
    }

    private fun shouldIgnoreTopLevelEntry(file: File): Boolean = file.name == "__MACOSX" || file.name == ".DS_Store"

    private fun sanitizeProjectName(rawName: String): String {
        val normalized = rawName
            .substringAfterLast('/')
            .substringAfterLast('\\')
            .trim()
            .replace(Regex("[\\\\/:*?\"<>|]"), "_")
            .trim('.')

        return normalized.ifBlank { "imported_project" }
    }

    private fun sanitizeFileName(rawName: String): String = rawName
        .substringAfterLast('/')
        .substringAfterLast('\\')
        .replace(Regex("[\\\\/:*?\"<>|]"), "_")
        .ifBlank { "imported_project.zip" }

    private fun stripArchiveSuffix(fileName: String): String {
        val matchedSuffix = tarSuffixes
            .plus(".zip")
            .firstOrNull { fileName.endsWith(it, ignoreCase = true) }

        return when {
            matchedSuffix != null -> fileName.dropLast(matchedSuffix.length)
            else -> fileName.substringBeforeLast('.', fileName)
        }
    }

    private fun queryDisplayName(context: Context, uri: Uri): String? {
        return runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { cursor ->
                    if (!cursor.moveToFirst()) return@use null
                    val columnIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (columnIndex < 0) return@use null
                    cursor.getString(columnIndex)
                }
        }.getOrNull()
    }

    private fun resolveDirectoryFromTreeUri(context: Context, uri: Uri): File? {
        val documentId = runCatching { DocumentsContract.getTreeDocumentId(uri) }
            .onFailure { Timber.tag(TAG).w(it, "Failed to resolve tree document id: %s", uri) }
            .getOrNull()
            ?: return null

        val resolved = resolveDocumentIdToFile(context, documentId)
        Timber.tag(TAG).d("Resolved tree uri %s to %s", uri, resolved?.absolutePath)
        return resolved
    }

    private fun resolveDocumentIdToFile(context: Context, documentId: String): File? {
        if (documentId.startsWith("raw:")) {
            return File(documentId.removePrefix("raw:"))
        }

        val parts = documentId.split(':', limit = 2)
        val volumeId = parts.firstOrNull().orEmpty()
        val relativePath = parts.getOrElse(1) { "" }
        val root = when (volumeId.lowercase()) {
            "primary" -> Environment.getExternalStorageDirectory()
            "home" -> Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS)
            else -> resolveSecondaryStorageRoot(context, volumeId)
        } ?: return null

        return if (relativePath.isBlank()) {
            root
        } else {
            File(root, relativePath)
        }
    }

    private fun resolveSecondaryStorageRoot(context: Context, volumeId: String): File? = context.getExternalFilesDirs(null)
        .asSequence()
        .filterNotNull()
        .mapNotNull(::deriveStorageRoot)
        .firstOrNull { root ->
            root.name.equals(volumeId, ignoreCase = true) ||
                root.absolutePath.contains("/$volumeId/")
        }

    private fun deriveStorageRoot(appSpecificDir: File): File? {
        val marker = "${File.separator}Android${File.separator}data${File.separator}"
        val absolutePath = appSpecificDir.absolutePath
        val index = absolutePath.indexOf(marker)
        if (index <= 0) return null
        return File(absolutePath.substring(0, index))
    }

    private fun takePersistablePermission(context: Context, uri: Uri, flags: Int) {
        runCatching {
            context.contentResolver.takePersistableUriPermission(uri, flags)
        }.onFailure {
            Timber.tag(TAG).d(it, "Persistable permission not granted for %s", uri)
        }
    }

    private enum class ArchiveKind {
        ZIP,
        TAR
    }
}
