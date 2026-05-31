package com.wuxianggujun.tinaide.provider

import android.content.Context
import android.content.pm.ProviderInfo
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Bundle
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
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
        var filename = docId
        if (filename.startsWith(packageName)) {
            filename = filename.substring(packageName.length)
        } else {
            throw FileNotFoundException("$docId not found")
        }

        if (filename.startsWith("/")) {
            filename = filename.substring(1)
        }

        if (filename.isEmpty()) {
            return null
        }

        val separatorIndex = filename.indexOf('/')
        val type: String
        val subPath: String

        if (separatorIndex == -1) {
            type = filename
            subPath = ""
        } else {
            type = filename.substring(0, separatorIndex)
            subPath = filename.substring(separatorIndex + 1)
        }

        val file: File? = when {
            type.equals("data", ignoreCase = true) -> File(dataDir, subPath)
            type.equals("android_data", ignoreCase = true) && androidDataDir != null ->
                File(androidDataDir!!, subPath)
            type.equals("android_obb", ignoreCase = true) && androidObbDir != null ->
                File(androidObbDir!!, subPath)
            type.equals("user_de_data", ignoreCase = true) && userDeDataDir != null ->
                File(userDeDataDir!!, subPath)
            else -> null
        }

        if (file == null) {
            throw FileNotFoundException("$docId not found")
        }

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
        includeFile(result, documentId, null)
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
            includeFile(result, "$parentId/data", dataDir)
            androidDataDir?.takeIf { it.exists() }?.let {
                includeFile(result, "$parentId/android_data", it)
            }
            androidObbDir?.takeIf { it.exists() }?.let {
                includeFile(result, "$parentId/android_obb", it)
            }
            userDeDataDir?.takeIf { it.exists() }?.let {
                includeFile(result, "$parentId/user_de_data", it)
            }
        } else {
            parent.listFiles()?.forEach { file ->
                includeFile(result, "$parentId/${file.name}", file)
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
        val parent = getFileForDocId(parentDocumentId)
        if (parent != null) {
            var newFile = File(parent, displayName)
            var noConflictId = 2
            while (newFile.exists()) {
                newFile = File(parent, "$displayName ($noConflictId)")
                noConflictId++
            }

            try {
                val succeeded = if (Document.MIME_TYPE_DIR == mimeType) {
                    newFile.mkdir()
                } else {
                    newFile.createNewFile()
                }

                if (succeeded) {
                    return if (parentDocumentId.endsWith("/")) {
                        parentDocumentId + newFile.name
                    } else {
                        "$parentDocumentId/${newFile.name}"
                    }
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
        val file = getFileForDocId(documentId)
        if (file != null) {
            val target = File(file.parentFile, displayName)
            if (file.renameTo(target)) {
                val lastSlash = documentId.lastIndexOf('/', documentId.length - 2)
                return "${documentId.substring(0, lastSlash)}/$displayName"
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
            val targetFile = File(targetDir, sourceFile.name)
            if (!targetFile.exists() && sourceFile.renameTo(targetFile)) {
                return if (targetParentDocumentId.endsWith("/")) {
                    targetParentDocumentId + targetFile.name
                } else {
                    "$targetParentDocumentId/${targetFile.name}"
                }
            }
        }
        throw FileNotFoundException("Failed to move document $sourceDocumentId to $targetParentDocumentId")
    }

    @Throws(FileNotFoundException::class)
    override fun getDocumentType(documentId: String): String {
        val file = getFileForDocId(documentId)
        return if (file == null) Document.MIME_TYPE_DIR else getMimeType(file)
    }

    override fun isChildDocument(parentDocumentId: String, documentId: String): Boolean = documentId.startsWith(parentDocumentId)

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

            val pathSegments = uri.pathSegments
            val documentId = if (pathSegments.size >= 4) pathSegments[3] else pathSegments[1]

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
                        val permissions = safeExtras.getInt("permissions")
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
                    val file = getFileForDocId(documentId, checkExists = false)
                    if (file == null) {
                        out.putBoolean("result", false)
                    } else {
                        val path = safeExtras.getString("path")
                        if (path == null) {
                            out.putBoolean("result", false)
                            out.putString("message", "Missing path parameter")
                        } else {
                            try {
                                Os.symlink(path, file.path)
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
    private fun includeFile(result: MatrixCursor, docId: String, file: File?) {
        var targetFile = file
        if (targetFile == null) {
            targetFile = getFileForDocId(docId)
        }

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
}
