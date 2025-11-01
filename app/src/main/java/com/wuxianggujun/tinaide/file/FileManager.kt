package com.wuxianggujun.tinaide.file

import android.content.Context
import android.os.FileObserver
import android.util.Log
import com.wuxianggujun.tinaide.core.ServiceLifecycle
import com.wuxianggujun.tinaide.core.ServiceLocator
import com.wuxianggujun.tinaide.core.config.IConfigManager
import com.wuxianggujun.tinaide.core.get
import java.io.File
import java.util.UUID

/**
 * 文件管理器实现
 */
class FileManager(private val context: Context) : IFileManager, ServiceLifecycle {
    companion object {
        private const val TAG = "FileManager"
        private const val KEY_CURRENT_PROJECT = "file.current_project"
        private const val KEY_RECENT_FILES = "file.recent_files"
        private const val MAX_RECENT_FILES = 10
    }
    
    private val configManager: IConfigManager by lazy {
        ServiceLocator.get<IConfigManager>()
    }
    
    private var currentProject: Project? = null
    private val fileWatchers = mutableMapOf<String, FileObserver>()
    private val fileListeners = mutableMapOf<String, MutableList<FileChangeListener>>()
    private val recentFiles = mutableListOf<File>()
    
    override fun onCreate() {
        // 恢复最近打开的文件列表
        loadRecentFiles()
    }
    
    override fun onDestroy() {
        // 清理所有文件监听器
        fileWatchers.values.forEach { it.stopWatching() }
        fileWatchers.clear()
        fileListeners.clear()
        
        // 保存最近打开的文件列表
        saveRecentFiles()
    }
    
    override fun openProject(path: String): Project {
        val projectDir = File(path)
        if (!projectDir.exists() || !projectDir.isDirectory) {
            throw IllegalArgumentException("Invalid project path: $path")
        }
        
        // 关闭当前项目
        closeProject()
        
        // 创建新项目
        val files = collectFiles(projectDir)
        val project = Project(
            name = projectDir.name,
            rootPath = projectDir.absolutePath,
            files = files
        )
        
        currentProject = project
        
        // 保存当前项目路径
        configManager.set(KEY_CURRENT_PROJECT, path)
        
        // 添加文件监听
        addFileWatcher(path, object : FileChangeListener {
            override fun onFileCreated(file: File) {
                Log.d(TAG, "File created: ${file.absolutePath}")
            }
            
            override fun onFileModified(file: File) {
                Log.d(TAG, "File modified: ${file.absolutePath}")
            }
            
            override fun onFileDeleted(file: File) {
                Log.d(TAG, "File deleted: ${file.absolutePath}")
            }
        })
        
        return project
    }
    
    override fun closeProject() {
        currentProject?.let { project ->
            // 移除文件监听
            removeFileWatcher(project.rootPath)
            currentProject = null
            
            // 清除当前项目配置
            configManager.remove(KEY_CURRENT_PROJECT)
        }
    }
    
    override fun getCurrentProject(): Project? {
        return currentProject
    }
    
    override fun createFile(parent: File, name: String): File {
        if (!parent.exists() || !parent.isDirectory) {
            throw IllegalArgumentException("Parent directory does not exist: ${parent.absolutePath}")
        }
        
        val newFile = File(parent, name)
        if (newFile.exists()) {
            throw IllegalArgumentException("File already exists: ${newFile.absolutePath}")
        }
        
        try {
            if (newFile.createNewFile()) {
                notifyFileCreated(newFile)
                return newFile
            } else {
                throw IllegalStateException("Failed to create file: ${newFile.absolutePath}")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error creating file", e)
            throw e
        }
    }
    
    override fun createDirectory(parent: File, name: String): File {
        if (!parent.exists() || !parent.isDirectory) {
            throw IllegalArgumentException("Parent directory does not exist: ${parent.absolutePath}")
        }
        
        val newDir = File(parent, name)
        if (newDir.exists()) {
            throw IllegalArgumentException("Directory already exists: ${newDir.absolutePath}")
        }
        
        try {
            if (newDir.mkdirs()) {
                notifyFileCreated(newDir)
                return newDir
            } else {
                throw IllegalStateException("Failed to create directory: ${newDir.absolutePath}")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error creating directory", e)
            throw e
        }
    }
    
    override fun deleteFile(file: File): Boolean {
        if (!file.exists()) {
            return false
        }
        
        try {
            val deleted = if (file.isDirectory) {
                file.deleteRecursively()
            } else {
                file.delete()
            }
            
            if (deleted) {
                notifyFileDeleted(file)
                // 从最近文件列表中移除
                recentFiles.remove(file)
            }
            
            return deleted
        } catch (e: Exception) {
            Log.e(TAG, "Error deleting file", e)
            return false
        }
    }
    
    override fun renameFile(file: File, newName: String): Boolean {
        if (!file.exists()) {
            return false
        }
        
        val newFile = File(file.parent, newName)
        if (newFile.exists()) {
            throw IllegalArgumentException("File with new name already exists: ${newFile.absolutePath}")
        }
        
        try {
            val renamed = file.renameTo(newFile)
            if (renamed) {
                notifyFileDeleted(file)
                notifyFileCreated(newFile)
                
                // 更新最近文件列表
                val index = recentFiles.indexOf(file)
                if (index >= 0) {
                    recentFiles[index] = newFile
                }
            }
            return renamed
        } catch (e: Exception) {
            Log.e(TAG, "Error renaming file", e)
            return false
        }
    }
    
    override fun copyFile(source: File, destination: File): Boolean {
        if (!source.exists()) {
            return false
        }
        
        try {
            if (source.isDirectory) {
                source.copyRecursively(destination, overwrite = false)
            } else {
                source.copyTo(destination, overwrite = false)
            }
            notifyFileCreated(destination)
            return true
        } catch (e: Exception) {
            Log.e(TAG, "Error copying file", e)
            return false
        }
    }
    
    override fun moveFile(source: File, destination: File): Boolean {
        if (!source.exists()) {
            return false
        }
        
        try {
            val moved = source.renameTo(destination)
            if (moved) {
                notifyFileDeleted(source)
                notifyFileCreated(destination)
                
                // 更新最近文件列表
                val index = recentFiles.indexOf(source)
                if (index >= 0) {
                    recentFiles[index] = destination
                }
            }
            return moved
        } catch (e: Exception) {
            Log.e(TAG, "Error moving file", e)
            return false
        }
    }
    
    override fun searchFiles(query: String): List<File> {
        val project = currentProject ?: return emptyList()
        val projectDir = File(project.rootPath)
        
        return searchFilesRecursive(projectDir, query)
    }
    
    override fun getRecentFiles(): List<File> {
        return recentFiles.toList()
    }
    
    override fun addFileWatcher(path: String, listener: FileChangeListener) {
        // 添加监听器
        fileListeners.getOrPut(path) { mutableListOf() }.add(listener)
        
        // 如果还没有 FileObserver，创建一个
        if (!fileWatchers.containsKey(path)) {
            val observer = object : FileObserver(path, ALL_EVENTS) {
                override fun onEvent(event: Int, path: String?) {
                    path ?: return
                    val file = File(path)
                    
                    when (event and ALL_EVENTS) {
                        CREATE -> notifyFileCreated(file)
                        MODIFY -> notifyFileModified(file)
                        DELETE, DELETE_SELF -> notifyFileDeleted(file)
                    }
                }
            }
            observer.startWatching()
            fileWatchers[path] = observer
        }
    }
    
    override fun removeFileWatcher(path: String) {
        fileWatchers[path]?.let { observer ->
            observer.stopWatching()
            fileWatchers.remove(path)
        }
        fileListeners.remove(path)
    }
    
    /**
     * 添加文件到最近打开列表
     */
    fun addToRecentFiles(file: File) {
        if (!file.exists() || file.isDirectory) {
            return
        }
        
        // 移除已存在的
        recentFiles.remove(file)
        
        // 添加到开头
        recentFiles.add(0, file)
        
        // 限制列表大小
        while (recentFiles.size > MAX_RECENT_FILES) {
            recentFiles.removeAt(recentFiles.size - 1)
        }
        
        saveRecentFiles()
    }
    
    /**
     * 递归收集文件
     */
    private fun collectFiles(dir: File): List<File> {
        val files = mutableListOf<File>()
        
        dir.listFiles()?.forEach { file ->
            files.add(file)
            if (file.isDirectory) {
                files.addAll(collectFiles(file))
            }
        }
        
        return files
    }
    
    /**
     * 递归搜索文件
     */
    private fun searchFilesRecursive(dir: File, query: String): List<File> {
        val results = mutableListOf<File>()
        
        dir.listFiles()?.forEach { file ->
            if (file.name.contains(query, ignoreCase = true)) {
                results.add(file)
            }
            
            if (file.isDirectory) {
                results.addAll(searchFilesRecursive(file, query))
            }
        }
        
        return results
    }
    
    /**
     * 通知文件创建
     */
    private fun notifyFileCreated(file: File) {
        fileListeners.values.flatten().forEach { listener ->
            try {
                listener.onFileCreated(file)
            } catch (e: Exception) {
                Log.e(TAG, "Error notifying file created", e)
            }
        }
    }
    
    /**
     * 通知文件修改
     */
    private fun notifyFileModified(file: File) {
        fileListeners.values.flatten().forEach { listener ->
            try {
                listener.onFileModified(file)
            } catch (e: Exception) {
                Log.e(TAG, "Error notifying file modified", e)
            }
        }
    }
    
    /**
     * 通知文件删除
     */
    private fun notifyFileDeleted(file: File) {
        fileListeners.values.flatten().forEach { listener ->
            try {
                listener.onFileDeleted(file)
            } catch (e: Exception) {
                Log.e(TAG, "Error notifying file deleted", e)
            }
        }
    }
    
    /**
     * 加载最近打开的文件列表
     */
    private fun loadRecentFiles() {
        try {
            val paths = configManager.get(KEY_RECENT_FILES, "")
            if (paths.isNotEmpty()) {
                paths.split(";").forEach { path ->
                    val file = File(path)
                    if (file.exists()) {
                        recentFiles.add(file)
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error loading recent files", e)
        }
    }
    
    /**
     * 保存最近打开的文件列表
     */
    private fun saveRecentFiles() {
        try {
            val paths = recentFiles.joinToString(";") { it.absolutePath }
            configManager.set(KEY_RECENT_FILES, paths)
        } catch (e: Exception) {
            Log.e(TAG, "Error saving recent files", e)
        }
    }
}
