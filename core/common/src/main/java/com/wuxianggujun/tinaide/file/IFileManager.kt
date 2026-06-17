package com.wuxianggujun.tinaide.file

import java.io.File

/**
 * 文件相关公共模型与监听接口。
 */

/**
 * 项目数据类
 */
data class Project(
    val id: String,
    val name: String,
    val rootPath: String,
    val workspaceRootPath: String,
    val files: List<File>,
    val buildDirPath: String
)

/**
 * 文件变更监听器
 */
interface FileChangeListener {
    fun onFileCreated(file: File)
    fun onFileModified(file: File)
    fun onFileDeleted(file: File)
    fun onFileRenamed(oldFile: File, newFile: File) = Unit
}
