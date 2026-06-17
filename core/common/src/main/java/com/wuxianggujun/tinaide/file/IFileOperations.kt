package com.wuxianggujun.tinaide.file

import java.io.File

/**
 * 文件与目录基础操作接口。
 */
interface IFileOperations {
    fun createFile(parent: File, name: String): File
    fun createDirectory(parent: File, name: String): File
    fun deleteFile(file: File): Boolean
    fun deleteFile(file: File, recursive: Boolean): Boolean {
        if (file.isDirectory && !recursive) return false
        return deleteFile(file)
    }
    fun renameFile(file: File, newName: String): Boolean
    fun copyFile(source: File, destination: File): Boolean
    fun moveFile(source: File, destination: File): Boolean
    fun moveFile(source: File, destination: File, overwrite: Boolean): Boolean {
        if (!source.exists()) return false
        if (sameFile(source, destination)) return true
        if (destination.exists()) {
            if (!overwrite) return false
            if (!deleteFile(destination)) return false
        }
        return moveFile(source, destination)
    }
    fun searchFiles(query: String): List<File>

    private fun sameFile(left: File, right: File): Boolean =
        runCatching { left.canonicalFile == right.canonicalFile }
            .getOrDefault(left.absoluteFile == right.absoluteFile)
}
