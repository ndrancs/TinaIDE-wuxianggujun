package com.wuxianggujun.tinaide.utils

import java.io.File
import java.io.IOException

/**
 * 文件操作工具类
 * 使用 Result 类型进行错误处理
 */
object FileUtils {
    
    /**
     * 创建文件
     */
    fun createFile(parent: File, name: String): Result<File> = runCatching {
        if (!parent.exists() || !parent.isDirectory) {
            throw IOException("父目录不存在或不是目录")
        }
        
        val file = File(parent, name)
        
        if (file.exists()) {
            throw IllegalArgumentException("文件已存在: ${file.name}")
        }
        
        if (!file.createNewFile()) {
            throw IOException("创建文件失败: ${file.name}")
        }
        
        Logger.i("File created: ${file.absolutePath}")
        file
    }
    
    /**
     * 创建目录
     */
    fun createDirectory(parent: File, name: String): Result<File> = runCatching {
        if (!parent.exists() || !parent.isDirectory) {
            throw IOException("父目录不存在或不是目录")
        }
        
        val dir = File(parent, name)
        
        if (dir.exists()) {
            throw IllegalArgumentException("目录已存在: ${dir.name}")
        }
        
        if (!dir.mkdirs()) {
            throw IOException("创建目录失败: ${dir.name}")
        }
        
        Logger.i("Directory created: ${dir.absolutePath}")
        dir
    }
    
    /**
     * 删除文件或目录（递归删除）
     */
    fun delete(file: File): Result<Boolean> = runCatching {
        if (!file.exists()) {
            throw IllegalArgumentException("文件不存在: ${file.name}")
        }
        
        val result = file.deleteRecursively()
        
        if (!result) {
            throw IOException("删除失败: ${file.name}")
        }
        
        Logger.i("Deleted: ${file.absolutePath}")
        result
    }
    
    /**
     * 重命名文件或目录
     */
    fun rename(file: File, newName: String): Result<File> = runCatching {
        if (!file.exists()) {
            throw IllegalArgumentException("文件不存在: ${file.name}")
        }
        
        if (newName.isEmpty()) {
            throw IllegalArgumentException("新名称不能为空")
        }
        
        val newFile = File(file.parent, newName)
        
        if (newFile.exists()) {
            throw IllegalArgumentException("目标文件已存在: $newName")
        }
        
        if (!file.renameTo(newFile)) {
            throw IOException("重命名失败: ${file.name} -> $newName")
        }
        
        Logger.i("Renamed: ${file.name} -> $newName")
        newFile
    }
    
    /**
     * 复制文件
     */
    fun copyFile(source: File, dest: File, overwrite: Boolean = false): Result<File> = runCatching {
        if (!source.exists() || !source.isFile) {
            throw IllegalArgumentException("源文件不存在或不是文件")
        }
        
        if (dest.exists() && !overwrite) {
            throw IllegalArgumentException("目标文件已存在")
        }
        
        source.copyTo(dest, overwrite)
        
        Logger.i("File copied: ${source.name} -> ${dest.absolutePath}")
        dest
    }
    
    /**
     * 复制目录（递归）
     */
    fun copyDirectory(source: File, dest: File, overwrite: Boolean = false): Result<File> = runCatching {
        if (!source.exists() || !source.isDirectory) {
            throw IllegalArgumentException("源目录不存在或不是目录")
        }
        
        if (dest.exists() && !overwrite) {
            throw IllegalArgumentException("目标目录已存在")
        }
        
        source.copyRecursively(dest, overwrite)
        
        Logger.i("Directory copied: ${source.name} -> ${dest.absolutePath}")
        dest
    }
    
    /**
     * 读取文件内容
     */
    fun readText(file: File): Result<String> = runCatching {
        if (!file.exists() || !file.isFile) {
            throw IllegalArgumentException("文件不存在或不是文件")
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
            throw IllegalArgumentException("不是文件")
        }
        
        file.writeText(text)
        Logger.i("File written: ${file.absolutePath} (${text.length} chars)")
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
    private fun formatFileSize(size: Long): String {
        return when {
            size < 1024 -> "$size B"
            size < 1024 * 1024 -> String.format("%.2f KB", size / 1024.0)
            size < 1024 * 1024 * 1024 -> String.format("%.2f MB", size / (1024.0 * 1024))
            else -> String.format("%.2f GB", size / (1024.0 * 1024 * 1024))
        }
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
    fun getExtension(file: File): String {
        return file.extension.lowercase()
    }
    
    /**
     * 判断是否是代码文件
     */
    fun isCodeFile(file: File): Boolean {
        val codeExtensions = setOf("cpp", "c", "h", "hpp", "cc", "cxx", "java", "kt", "xml", "json", "txt", "md")
        return codeExtensions.contains(getExtension(file))
    }
}
