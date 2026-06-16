package com.wuxianggujun.tinaide.utils

import com.wuxianggujun.tinaide.core.i18n.Strings
import com.wuxianggujun.tinaide.core.i18n.str
import com.wuxianggujun.tinaide.core.lang.CxxFileSupport
import java.io.File
import java.io.IOException
import timber.log.Timber

/**
 * 文件操作工具类
 * 使用 Result 类型进行错误处理
 */
object FileUtils {

    private const val TAG = "FileUtils"

    /**
     * 创建文件
     */
    fun createFile(parent: File, name: String): Result<File> = runCatching {
        if (!parent.exists() || !parent.isDirectory) {
            throw IOException(Strings.fileutils_parent_not_exist.str())
        }

        val file = File(parent, name)

        if (file.exists()) {
            throw IllegalArgumentException(Strings.fileutils_file_exists.str(file.name))
        }

        if (!file.createNewFile()) {
            throw IOException(Strings.fileutils_create_file_failed.str(file.name))
        }

        Timber.tag(TAG).i("File created: %s", file.absolutePath)
        file
    }

    /**
     * 创建目录
     */
    fun createDirectory(parent: File, name: String): Result<File> = runCatching {
        if (!parent.exists() || !parent.isDirectory) {
            throw IOException(Strings.fileutils_parent_not_exist.str())
        }

        val dir = File(parent, name)

        if (dir.exists()) {
            throw IllegalArgumentException(Strings.fileutils_dir_exists.str(dir.name))
        }

        if (!dir.mkdirs()) {
            throw IOException(Strings.fileutils_create_dir_failed.str(dir.name))
        }

        Timber.tag(TAG).i("Directory created: %s", dir.absolutePath)
        dir
    }

    /**
     * 删除文件或目录（递归删除）
     */
    fun delete(file: File): Result<Boolean> = runCatching {
        if (!file.exists()) {
            throw IllegalArgumentException(Strings.fileutils_file_not_exist.str(file.name))
        }

        val result = file.deleteRecursively()

        if (!result) {
            throw IOException(Strings.fileutils_delete_failed.str(file.name))
        }

        Timber.tag(TAG).i("Deleted: %s", file.absolutePath)
        result
    }

    /**
     * 重命名文件或目录
     */
    fun rename(file: File, newName: String): Result<File> = runCatching {
        if (!file.exists()) {
            throw IllegalArgumentException(Strings.fileutils_file_not_exist.str(file.name))
        }

        if (newName.isEmpty()) {
            throw IllegalArgumentException(Strings.fileutils_new_name_empty.str())
        }

        val newFile = File(file.parent, newName)

        if (newFile.exists()) {
            throw IllegalArgumentException(Strings.fileutils_target_exists.str(newName))
        }

        if (!file.renameTo(newFile)) {
            throw IOException(Strings.fileutils_rename_failed.str(file.name, newName))
        }

        Timber.tag(TAG).i("Renamed: %s -> %s", file.name, newName)
        newFile
    }

    /**
     * 复制文件
     */
    fun copyFile(source: File, dest: File, overwrite: Boolean = false): Result<File> = runCatching {
        if (!source.exists() || !source.isFile) {
            throw IllegalArgumentException(Strings.fileutils_source_not_file.str())
        }

        if (dest.exists() && !overwrite) {
            throw IllegalArgumentException(Strings.fileutils_target_file_exists.str())
        }

        source.copyTo(dest, overwrite)

        Timber.tag(TAG).i("File copied: %s -> %s", source.name, dest.absolutePath)
        dest
    }

    /**
     * 复制目录（递归）
     */
    fun copyDirectory(source: File, dest: File, overwrite: Boolean = false): Result<File> = runCatching {
        if (!source.exists() || !source.isDirectory) {
            throw IllegalArgumentException(Strings.fileutils_source_not_dir.str())
        }

        if (dest.exists() && !overwrite) {
            throw IllegalArgumentException(Strings.fileutils_target_dir_exists.str())
        }

        source.copyRecursively(dest, overwrite)

        Timber.tag(TAG).i("Directory copied: %s -> %s", source.name, dest.absolutePath)
        dest
    }

    /**
     * 读取文件内容
     */
    fun readText(file: File): Result<String> = runCatching {
        if (!file.exists() || !file.isFile) {
            throw IllegalArgumentException(Strings.fileutils_source_not_file.str())
        }

        file.readText()
    }

    /**
     * 写入文件内容
     */
    fun writeText(file: File, text: String): Result<Unit> = runCatching {
        if (!file.exists()) {
            // 如果文件不存在，创建它
            file.parentFile?.mkdirs()
            file.createNewFile()
        }

        if (!file.isFile) {
            throw IllegalArgumentException(Strings.fileutils_not_file.str())
        }

        file.writeText(text)
        Timber.tag(TAG).i("File written: %s (%d chars)", file.absolutePath, text.length)
    }

    /**
     * 获取文件大小（格式化）
     */
    fun getFormattedSize(file: File): String {
        if (!file.exists()) return "0 B"

        val size = if (file.isDirectory) {
            file.walkTopDown().filter { it.isFile }.map { it.length() }.sum()
        } else {
            file.length()
        }

        return formatFileSize(size)
    }

    /**
     * 格式化文件大小
     */
    private fun formatFileSize(size: Long): String = when {
        size < 1024 -> "$size B"
        size < 1024 * 1024 -> String.format("%.2f KB", size / 1024.0)
        size < 1024 * 1024 * 1024 -> String.format("%.2f MB", size / (1024.0 * 1024))
        else -> String.format("%.2f GB", size / (1024.0 * 1024 * 1024))
    }

    /**
     * 检查文件名是否合法
     */
    fun isValidFileName(name: String): Boolean {
        if (name.isEmpty() || name.isBlank()) return false

        // 不允许的字符
        val invalidChars = arrayOf('/', '\\', ':', '*', '?', '"', '<', '>', '|')
        return !invalidChars.any { name.contains(it) }
    }

    /**
     * 获取文件扩展名
     */
    fun getExtension(file: File): String = file.extension.lowercase()

    /**
     * 判断是否是代码文件
     */
    fun isCodeFile(file: File): Boolean {
        val codeExtensions = CxxFileSupport.editorRelatedExtensions + setOf(
            "java",
            "kt",
            "xml",
            "json",
            "txt",
            "md"
        )
        return codeExtensions.contains(getExtension(file))
    }
}
