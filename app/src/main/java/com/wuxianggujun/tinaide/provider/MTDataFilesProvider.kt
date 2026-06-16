package com.wuxianggujun.tinaide.provider

import android.content.Context
import android.content.pm.ProviderInfo
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Bundle
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import android.provider.DocumentsContract.Root
import android.provider.DocumentsProvider
import android.system.ErrnoException
import android.system.Os
import android.webkit.MimeTypeMap
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException

/**
 * MT 管理器数据文件提供器
 *
 * 允许 MT 管理器在无 ROOT 情况下访问应用的私有目录文件。
 *
 * 支持的目录：
 * - data: 应用私有数据目录 (/data/data/包名)
 * - android_data: 外部存储应用目录 (Android/data/包名)
 * - android_obb: OBB 目录 (Android/obb/包名)
 * - user_de_data: 设备加密存储目录 (/data/user_de/用户ID/包名)
 *
 * @see <a href="https://github.com/L-JINBIN/MTDataFilesProvider">原始项目</a>
 */
class MTDataFilesProvider : DocumentsProvider() {

    companion object {
        const val COLUMN_MT_EXTRAS = "mt_extras"
        const val COLUMN_MT_PATH = "mt_path"
        const val METHOD_SET_LAST_MODIFIED = "mt:setLastModified"
        const val METHOD_SET_PERMISSIONS = "mt:setPermissions"
        const val METHOD_CREATE_SYMLINK = "mt:createSymlink"

        private val DEFAULT_ROOT_PROJECTION = arrayOf(
            Root.COLUMN_ROOT_ID,
            Root.COLUMN_MIME_TYPES,
            Root.COLUMN_FLAGS,
            Root.COLUMN_ICON,
            Root.COLUMN_TITLE,
            Root.COLUMN_SUMMARY,
            Root.COLUMN_DOCUMENT_ID
        )

        private val DEFAULT_DOCUMENT_PROJECTION = arrayOf(
            Document.COLUMN_DOCUMENT_ID,
            Document.COLUMN_MIME_TYPE,
            Document.COLUMN_DISPLAY_NAME,
            Document.COLUMN_LAST_MODIFIED,
            Document.COLUMN_FLAGS,
            Document.COLUMN_SIZE,
            COLUMN_MT_EXTRAS
        )

        // S_IFLNK 符号链接类型掩码
        private const val S_IFMT = 0x0F000 // 0170000
        private const val S_IFLNK = 0x0A000 // 0120000
    }

    private lateinit var packageName: String
    private lateinit var dataDir: File
    private var userDeDataDir: File? = null
    private var androidDataDir: File? = null
    private var androidObbDir: File? = null

    override fun attachInfo(context: Context, info: ProviderInfo) {
        super.attachInfo(context, info)
        packageName = context.packageName
        dataDir = context.filesDir.parentFile!!

        userDeDataDir = context.createDeviceProtectedStorageContext().filesDir.parentFile

        context.getExternalFilesDir(null)?.let { externalFilesDir ->
            androidDataDir = externalFilesDir.parentFile
        }

        androidObbDir = context.obbDir
    }

    override fun onCreate(): Boolean = true

    /**
     * 根据文档 ID 获取对应的文件
     * @param docId 文档 ID
     * @param checkExists 是否检查文件存在
     * @return 文件对象，如果是根目录则返回 null
     */
    @Throws(FileNotFoundException::class)
    private fun getFileForDocId(docId: String, checkExists: Boolean = true): File? {
        val parts = parseDocumentId(docId)
        val type = parts.type
        if (type == null) {
            return null
        }

        val root = getRootForType(type) ?: throw FileNotFoundException("$docId not found")
        val safeSubPath = sanitizeDocumentSubPath(parts.subPath, docId)
        val file = if (safeSubPath.isEmpty()) root else File(root, safeSubPath)

        requireFileInsideRoot(root, file, docId)

        if (checkExists) {
            try {
                // 不能用 File.exists: 如果 file 是个 link，且目标无法访问，则 exists 返回 false
                Os.lstat(file.path)
            } catch (e: Exception) {
                throw FileNotFoundException("$docId not found")
            }
        }

        return file
    }

    @Throws(FileNotFoundException::class)
    private fun parseDocumentId(docId: String): MTDocumentIdParts = MTDataFilesProviderPathSafety.parseDocumentId(packageName, docId)

    private fun getRootForType(type: String): File? = when {
        type.equals("data", ignoreCase = true) -> dataDir
        type.equals("android_data", ignoreCase = true) -> androidDataDir
        type.equals("android_obb", ignoreCase = true) -> androidObbDir
        type.equals("user_de_data", ignoreCase = true) -> userDeDataDir
        else -> null
    }

    @Throws(FileNotFoundException::class)
    private fun sanitizeDocumentSubPath(subPath: String, docId: String): String = MTDataFilesProviderPathSafety.sanitizeDocumentSubPath(subPath, docId)

    @Throws(FileNotFoundException::class)
    private fun requireFileInsideRoot(root: File, file: File, docId: String) {
        MTDataFilesProviderPathSafety.requireFileInsideRoot(
            root = root,
            file = file,
            docId = docId,
            existsWithoutFollowing = ::existsWithoutFollowing,
            isSymbolicLink = ::isSymbolicLink,
            readLink = { Os.readlink(it.path) },
        )
    }

    @Throws(FileNotFoundException::class)
    private fun requireSymlinkTargetInsideRoot(root: File, linkFile: File, linkTarget: String, docId: String): String = MTDataFilesProviderPathSafety.requireSymlinkTargetInsideRoot(root, linkFile, linkTarget, docId)

    private fun canonicalCandidate(file: File): File = MTDataFilesProviderPathSafety.canonicalCandidate(file)

    private fun existsWithoutFollowing(file: File): Boolean = try {
        Os.lstat(file.path)
        true
    } catch (_: Exception) {
        false
    }

    private fun isSameOrChild(root: File, candidate: File): Boolean = MTDataFilesProviderPathSafety.isSameOrChild(root, candidate)

    @Throws(FileNotFoundException::class)
    private fun sanitizeDisplayName(displayName: String): String = MTDataFilesProviderPathSafety.sanitizeDisplayName(displayName)

    @Throws(FileNotFoundException::class)
    private fun getParentDocumentId(documentId: String): String = MTDataFilesProviderPathSafety.getParentDocumentId(packageName, documentId)

    private fun appendDocumentId(parentDocumentId: String, displayName: String): String = MTDataFilesProviderPathSafety.appendDocumentId(parentDocumentId, displayName)

    private fun getDocumentIdFromUri(uri: Uri): String? = runCatching { DocumentsContract.getDocumentId(uri) }
        .getOrNull()
        ?.takeIf { it.isNotBlank() }
        ?: uri.pathSegments.let { segments ->
            when {
                segments.size >= 4 -> segments[3]
                segments.size >= 2 -> segments[1]
                else -> null
            }
        }

    override fun queryRoots(projection: Array<String>?): Cursor {
        val context = context ?: return MatrixCursor(projection ?: DEFAULT_ROOT_PROJECTION)
        val applicationInfo = context.applicationInfo
        val applicationName = applicationInfo.loadLabel(context.packageManager).toString()

        val result = MatrixCursor(projection ?: DEFAULT_ROOT_PROJECTION)
        result.newRow().apply {
            add(Root.COLUMN_ROOT_ID, packageName)
            add(Root.COLUMN_DOCUMENT_ID, packageName)
            add(Root.COLUMN_SUMMARY, packageName)
            add(Root.COLUMN_FLAGS, Root.FLAG_SUPPORTS_CREATE or Root.FLAG_SUPPORTS_IS_CHILD)
            add(Root.COLUMN_TITLE, applicationName)
            add(Root.COLUMN_MIME_TYPES, "*/*")
            add(Root.COLUMN_ICON, applicationInfo.icon)
        }
        return result
    }

    @Throws(FileNotFoundException::class)
    override fun queryDocument(documentId: String, projection: Array<String>?): Cursor {
        val result = MatrixCursor(projection ?: DEFAULT_DOCUMENT_PROJECTION)
        includeFile(result, documentId)
        return result
    }

    @Throws(FileNotFoundException::class)
    override fun queryChildDocuments(
        parentDocumentId: String,
        projection: Array<String>?,
        sortOrder: String?
    ): Cursor {
        var parentId = parentDocumentId
        if (parentId.endsWith("/")) {
            parentId = parentId.substring(0, parentId.length - 1)
        }

        val result = MatrixCursor(projection ?: DEFAULT_DOCUMENT_PROJECTION)
        val parent = getFileForDocId(parentId)

        if (parent == null) {
            // 根目录，显示所有可用的子目录
            includeFileIfSafe(result, "$parentId/data")
            androidDataDir?.takeIf { it.exists() }?.let {
                includeFileIfSafe(result, "$parentId/android_data")
            }
            androidObbDir?.takeIf { it.exists() }?.let {
                includeFileIfSafe(result, "$parentId/android_obb")
            }
            userDeDataDir?.takeIf { it.exists() }?.let {
                includeFileIfSafe(result, "$parentId/user_de_data")
            }
        } else {
            parent.listFiles()?.forEach { file ->
                includeFileIfSafe(result, appendDocumentId(parentId, file.name))
            }
        }

        return result
    }

    @Throws(FileNotFoundException::class)
    override fun openDocument(
        documentId: String,
        mode: String,
        signal: CancellationSignal?
    ): ParcelFileDescriptor {
        val file = getFileForDocId(documentId, checkExists = false)
            ?: throw FileNotFoundException("$documentId not found")
        val fileMode = parseFileMode(mode)
        return ParcelFileDescriptor.open(file, fileMode)
    }

    private fun parseFileMode(mode: String): Int = when (mode) {
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

    @Throws(FileNotFoundException::class)
    override fun createDocument(
        parentDocumentId: String,
        mimeType: String,
        displayName: String
    ): String {
        val safeDisplayName = sanitizeDisplayName(displayName)
        val parent = getFileForDocId(parentDocumentId)
        if (parent != null) {
            var targetDocumentId = appendDocumentId(parentDocumentId, safeDisplayName)
            var newFile = getFileForDocId(targetDocumentId, checkExists = false)
                ?: throw FileNotFoundException("Failed to create document in $parentDocumentId with name $displayName")
            var noConflictId = 2
            while (newFile.exists()) {
                val conflictName = "$safeDisplayName ($noConflictId)"
                targetDocumentId = appendDocumentId(parentDocumentId, conflictName)
                newFile = getFileForDocId(targetDocumentId, checkExists = false)
                    ?: throw FileNotFoundException("Failed to create document in $parentDocumentId with name $displayName")
                noConflictId++
            }

            try {
                val succeeded = if (Document.MIME_TYPE_DIR == mimeType) {
                    newFile.mkdir()
                } else {
                    newFile.createNewFile()
                }

                if (succeeded) {
                    return targetDocumentId
                }
            } catch (e: IOException) {
                e.printStackTrace()
            }
        }
        throw FileNotFoundException("Failed to create document in $parentDocumentId with name $displayName")
    }

    @Throws(FileNotFoundException::class)
    override fun deleteDocument(documentId: String) {
        val file = getFileForDocId(documentId)
        if (file == null || !deleteFile(file)) {
            throw FileNotFoundException("Failed to delete document $documentId")
        }
    }

    private fun deleteFile(file: File): Boolean {
        if (file.isDirectory && !isSymbolicLink(file)) {
            file.listFiles()?.forEach { child ->
                if (!deleteFile(child)) {
                    return false
                }
            }
        }
        return file.delete()
    }

    private fun isSymbolicLink(file: File): Boolean = try {
        val stat = Os.lstat(file.path)
        (stat.st_mode and S_IFMT) == S_IFLNK
    } catch (e: ErrnoException) {
        e.printStackTrace()
        false
    }

    @Throws(FileNotFoundException::class)
    override fun removeDocument(documentId: String, parentDocumentId: String) {
        deleteDocument(documentId)
    }

    @Throws(FileNotFoundException::class)
    override fun renameDocument(documentId: String, displayName: String): String {
        val safeDisplayName = sanitizeDisplayName(displayName)
        val file = getFileForDocId(documentId)
        if (file != null) {
            val parentDocumentId = getParentDocumentId(documentId)
            val targetDocumentId = appendDocumentId(parentDocumentId, safeDisplayName)
            val target = getFileForDocId(targetDocumentId, checkExists = false)
                ?: throw FileNotFoundException("Failed to rename document $documentId to $displayName")
            if (file.renameTo(target)) {
                return targetDocumentId
            }
        }
        throw FileNotFoundException("Failed to rename document $documentId to $displayName")
    }

    @Throws(FileNotFoundException::class)
    override fun moveDocument(
        sourceDocumentId: String,
        sourceParentDocumentId: String,
        targetParentDocumentId: String
    ): String {
        val sourceFile = getFileForDocId(sourceDocumentId)
        val targetDir = getFileForDocId(targetParentDocumentId)

        if (sourceFile != null && targetDir != null) {
            val targetDocumentId = appendDocumentId(targetParentDocumentId, sourceFile.name)
            val targetFile = getFileForDocId(targetDocumentId, checkExists = false)
                ?: throw FileNotFoundException("Failed to move document $sourceDocumentId to $targetParentDocumentId")
            if (!targetFile.exists() && sourceFile.renameTo(targetFile)) {
                return targetDocumentId
            }
        }
        throw FileNotFoundException("Failed to move document $sourceDocumentId to $targetParentDocumentId")
    }

    @Throws(FileNotFoundException::class)
    override fun getDocumentType(documentId: String): String {
        val file = getFileForDocId(documentId)
        return if (file == null) Document.MIME_TYPE_DIR else getMimeType(file)
    }

    override fun isChildDocument(parentDocumentId: String, documentId: String): Boolean = runCatching {
        val parent = getFileForDocId(parentDocumentId, checkExists = false)
        val child = getFileForDocId(documentId, checkExists = false)
        if (parent == null) {
            documentId == packageName || child != null
        } else {
            child != null && isSameOrChild(parent.canonicalFile, canonicalCandidate(child))
        }
    }.getOrDefault(false)

    private fun getMimeType(file: File): String = if (file.isDirectory) {
        Document.MIME_TYPE_DIR
    } else {
        val name = file.name
        val lastDot = name.lastIndexOf('.')
        if (lastDot >= 0) {
            val extension = name.substring(lastDot + 1).lowercase()
            MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension)
        } else {
            null
        } ?: "application/octet-stream"
    }

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle? {
        val result = super.call(method, arg, extras)
        if (result != null) {
            return result
        }

        if (!method.startsWith("mt:")) {
            return null
        }

        val out = Bundle()
        try {
            val safeExtras = extras ?: run {
                out.putBoolean("result", false)
                out.putString("message", "Missing extras")
                return out
            }

            val uri = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                safeExtras.getParcelable("uri", Uri::class.java)
            } else {
                @Suppress("DEPRECATION")
                safeExtras.getParcelable("uri")
            }
            if (uri == null) {
                out.putBoolean("result", false)
                out.putString("message", "Missing uri parameter")
                return out
            }

            val documentId = getDocumentIdFromUri(uri) ?: run {
                out.putBoolean("result", false)
                out.putString("message", "Invalid uri parameter")
                return out
            }

            when (method) {
                METHOD_SET_LAST_MODIFIED -> {
                    val file = getFileForDocId(documentId)
                    if (file == null) {
                        out.putBoolean("result", false)
                    } else {
                        val time = safeExtras.getLong("time")
                        out.putBoolean("result", file.setLastModified(time))
                    }
                }
                METHOD_SET_PERMISSIONS -> {
                    val file = getFileForDocId(documentId)
                    if (file == null) {
                        out.putBoolean("result", false)
                    } else {
                        val permissions = safeExtras.getInt("permissions") and 0x1FF
                        try {
                            Os.chmod(file.path, permissions)
                            out.putBoolean("result", true)
                        } catch (e: ErrnoException) {
                            out.putBoolean("result", false)
                            out.putString("message", e.message)
                        }
                    }
                }
                METHOD_CREATE_SYMLINK -> {
                    val root = parseDocumentId(documentId).type?.let { getRootForType(it) }
                    val file = getFileForDocId(documentId, checkExists = false)
                    if (file == null || root == null) {
                        out.putBoolean("result", false)
                    } else {
                        val path = safeExtras.getString("path")
                        if (path == null) {
                            out.putBoolean("result", false)
                            out.putString("message", "Missing path parameter")
                        } else {
                            try {
                                val safeTarget = requireSymlinkTargetInsideRoot(root, file, path, documentId)
                                Os.symlink(safeTarget, file.path)
                                out.putBoolean("result", true)
                            } catch (e: ErrnoException) {
                                out.putBoolean("result", false)
                                out.putString("message", e.message)
                            }
                        }
                    }
                }
                else -> {
                    out.putBoolean("result", false)
                    out.putString("message", "Unsupported method: $method")
                }
            }
        } catch (e: Exception) {
            out.putBoolean("result", false)
            out.putString("message", e.toString())
        }
        return out
    }

    /**
     * 将文件信息添加到游标中
     */
    @Throws(FileNotFoundException::class)
    private fun includeFile(result: MatrixCursor, docId: String) {
        val targetFile = getFileForDocId(docId)

        if (targetFile == null) {
            // 根目录
            result.newRow().apply {
                add(Document.COLUMN_DOCUMENT_ID, packageName)
                add(Document.COLUMN_DISPLAY_NAME, packageName)
                add(Document.COLUMN_SIZE, 0L)
                add(Document.COLUMN_MIME_TYPE, Document.MIME_TYPE_DIR)
                add(Document.COLUMN_LAST_MODIFIED, 0)
                add(Document.COLUMN_FLAGS, 0)
            }
            return
        }

        var flags = 0
        if (targetFile.isDirectory) {
            if (targetFile.canWrite()) {
                flags = flags or Document.FLAG_DIR_SUPPORTS_CREATE
            }
        } else if (targetFile.canWrite()) {
            flags = flags or Document.FLAG_SUPPORTS_WRITE
        }

        targetFile.parentFile?.let { parentFile ->
            if (parentFile.canWrite()) {
                flags = flags or Document.FLAG_SUPPORTS_DELETE
                flags = flags or Document.FLAG_SUPPORTS_RENAME
            }
        }

        val path = targetFile.path
        val displayName: String
        var addExtras = false

        when (path) {
            dataDir.path -> displayName = "data"
            androidDataDir?.path -> displayName = "android_data"
            androidObbDir?.path -> displayName = "android_obb"
            userDeDataDir?.path -> displayName = "user_de_data"
            else -> {
                displayName = targetFile.name
                addExtras = true
            }
        }

        result.newRow().apply {
            add(Document.COLUMN_DOCUMENT_ID, docId)
            add(Document.COLUMN_DISPLAY_NAME, displayName)
            add(Document.COLUMN_SIZE, targetFile.length())
            add(Document.COLUMN_MIME_TYPE, getMimeType(targetFile))
            add(Document.COLUMN_LAST_MODIFIED, targetFile.lastModified())
            add(Document.COLUMN_FLAGS, flags)
            add(COLUMN_MT_PATH, targetFile.absolutePath)

            if (addExtras) {
                try {
                    val stat = Os.lstat(path)
                    val sb = StringBuilder()
                    sb.append(stat.st_mode)
                        .append("|").append(stat.st_uid)
                        .append("|").append(stat.st_gid)

                    if ((stat.st_mode and S_IFMT) == S_IFLNK) {
                        sb.append("|").append(Os.readlink(path))
                    }
                    add(COLUMN_MT_EXTRAS, sb.toString())
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }
    }

    private fun includeFileIfSafe(result: MatrixCursor, docId: String) {
        try {
            includeFile(result, docId)
        } catch (_: FileNotFoundException) {
            // Skip legacy symlinks or malformed entries that no longer fit the exposed root.
        }
    }
}
