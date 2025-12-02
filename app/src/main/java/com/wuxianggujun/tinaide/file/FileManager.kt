package com.wuxianggujun.tinaide.file

import android.content.Context
import android.os.Build
import android.os.FileObserver
import android.util.Log
import com.wuxianggujun.tinaide.core.ServiceLifecycle
import com.wuxianggujun.tinaide.core.ServiceLocator
import com.wuxianggujun.tinaide.core.config.IConfigManager
import com.wuxianggujun.tinaide.core.config.ConfigKeys
import com.wuxianggujun.tinaide.core.get
import java.io.File

/**
 * 文件管理器实现
 */
class FileManager(private val context: Context) : IFileManager, ServiceLifecycle {
    companion object {
        private const val TAG = "FileManager"
        private const val MAX_RECENT_FILES = 10
    }

    private val configManager: IConfigManager by lazy { ServiceLocator.get<IConfigManager>() }

    private var currentProject: Project? = null
    private val fileWatchers = mutableMapOf<String, FileObserver>()
    private val fileListeners = mutableMapOf<String, MutableList<FileChangeListener>>()
    private val recentFiles = mutableListOf<File>()

    override fun onCreate() {
        loadRecentFiles()
    }

    override fun onDestroy() {
        fileWatchers.values.forEach { it.stopWatching() }
        fileWatchers.clear()
        fileListeners.clear()
        saveRecentFiles()
    }

    override fun openProject(path: String): Project {
        val projectDir = File(path)
        require(projectDir.exists() && projectDir.isDirectory) { "Invalid project path: $path" }

        closeProject()

        // 避免在主线程递归扫描整个项目目录，先仅加载顶层文件，文件树按需懒加载
        val files = projectDir.listFiles()?.toList() ?: emptyList()
        val project = Project(
            name = projectDir.name,
            rootPath = projectDir.absolutePath,
            files = files
        )
        currentProject = project

        configManager.set(ConfigKeys.CurrentProject, path)

        addFileWatcher(path, object : FileChangeListener {
            override fun onFileCreated(file: File) { Log.d(TAG, "File created: ${file.absolutePath}") }
            override fun onFileModified(file: File) { Log.d(TAG, "File modified: ${file.absolutePath}") }
            override fun onFileDeleted(file: File) { Log.d(TAG, "File deleted: ${file.absolutePath}") }
        })

        return project
    }

    override fun closeProject() {
        currentProject?.let { project ->
            removeFileWatcher(project.rootPath)
            currentProject = null
            configManager.remove(ConfigKeys.CurrentProject.key)
        }
    }

    override fun getCurrentProject(): Project? {
        if (currentProject == null) {
            try {
                val lastPath = configManager.get(ConfigKeys.CurrentProject)
                if (lastPath.isNotEmpty()) {
                    val dir = File(lastPath)
                    if (dir.exists() && dir.isDirectory) {
                        // 恢复时同样避免深度遍历
                        val top = dir.listFiles()?.toList() ?: emptyList()
                        currentProject = Project(dir.name, dir.absolutePath, top)
                        if (!fileWatchers.containsKey(dir.absolutePath)) {
                            addFileWatcher(dir.absolutePath, object : FileChangeListener {
                                override fun onFileCreated(file: File) { Log.d(TAG, "File created: ${file.absolutePath}") }
                                override fun onFileModified(file: File) { Log.d(TAG, "File modified: ${file.absolutePath}") }
                                override fun onFileDeleted(file: File) { Log.d(TAG, "File deleted: ${file.absolutePath}") }
                            })
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error restoring current project", e)
            }
        }
        return currentProject
    }

    override fun createFile(parent: File, name: String): File {
        require(parent.exists() && parent.isDirectory) { "Parent directory does not exist: ${parent.absolutePath}" }

        val newFile = File(parent, name)
        require(!newFile.exists()) { "File already exists: ${newFile.absolutePath}" }

        if (newFile.createNewFile()) {
            notifyFileCreated(newFile)
            return newFile
        } else {
            throw IllegalStateException("Failed to create file: ${newFile.absolutePath}")
        }
    }

    override fun createDirectory(parent: File, name: String): File {
        require(parent.exists() && parent.isDirectory) { "Parent directory does not exist: ${parent.absolutePath}" }
        val newDir = File(parent, name)
        require(!newDir.exists()) { "Directory already exists: ${newDir.absolutePath}" }
        if (newDir.mkdirs()) {
            notifyFileCreated(newDir)
            return newDir
        } else {
            throw IllegalStateException("Failed to create directory: ${newDir.absolutePath}")
        }
    }

    override fun deleteFile(file: File): Boolean {
        if (!file.exists()) return false
        val deleted = if (file.isDirectory) file.deleteRecursively() else file.delete()
        if (deleted) {
            notifyFileDeleted(file)
            recentFiles.remove(file)
        }
        return deleted
    }

    override fun renameFile(file: File, newName: String): Boolean {
        if (!file.exists()) return false
        val newFile = File(file.parent, newName)
        require(!newFile.exists()) { "File with new name already exists: ${newFile.absolutePath}" }
        val renamed = file.renameTo(newFile)
        if (renamed) {
            notifyFileDeleted(file)
            notifyFileCreated(newFile)
            val idx = recentFiles.indexOf(file)
            if (idx >= 0) recentFiles[idx] = newFile
        }
        return renamed
    }

    override fun copyFile(source: File, destination: File): Boolean {
        if (!source.exists()) return false
        try {
            if (source.isDirectory) source.copyRecursively(destination, overwrite = false)
            else source.copyTo(destination, overwrite = false)
            notifyFileCreated(destination)
            return true
        } catch (e: Exception) {
            Log.e(TAG, "Error copying file", e)
            return false
        }
    }

    override fun moveFile(source: File, destination: File): Boolean {
        if (!source.exists()) return false
        val moved = source.renameTo(destination)
        if (moved) {
            notifyFileDeleted(source)
            notifyFileCreated(destination)
            val idx = recentFiles.indexOf(source)
            if (idx >= 0) recentFiles[idx] = destination
        }
        return moved
    }

    override fun searchFiles(query: String): List<File> {
        val project = currentProject ?: return emptyList()
        val projectDir = File(project.rootPath)
        return searchFilesRecursive(projectDir, query)
    }

    override fun getRecentFiles(): List<File> = recentFiles.toList()

    override fun addFileWatcher(path: String, listener: FileChangeListener) {
        fileListeners.getOrPut(path) { mutableListOf() }.add(listener)
        if (!fileWatchers.containsKey(path)) {
            val watchDir = File(path)
            val mask = FileObserver.CREATE or FileObserver.MOVED_TO or FileObserver.MODIFY or
                FileObserver.CLOSE_WRITE or FileObserver.DELETE or FileObserver.DELETE_SELF or
                FileObserver.MOVED_FROM
            val observer = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                object : FileObserver(watchDir, mask) {
                    override fun onEvent(event: Int, child: String?) {
                        handleFileEvent(watchDir, event, child)
                    }
                }
            } else {
                @Suppress("DEPRECATION")
                object : FileObserver(path, mask) {
                    override fun onEvent(event: Int, child: String?) {
                        handleFileEvent(watchDir, event, child)
                    }
                }
            }
            observer.startWatching()
            fileWatchers[path] = observer
        } else {
            // watcher 已存在，只需记录额外 listener
        }
    }

    private fun handleFileEvent(directory: File, event: Int, child: String?) {
        val file = if (child != null) File(directory, child) else directory
        try {
            if ((event and FileObserver.CREATE) != 0 || (event and FileObserver.MOVED_TO) != 0) {
                notifyFileCreated(file)
            }
            if ((event and FileObserver.MODIFY) != 0 || (event and FileObserver.CLOSE_WRITE) != 0) {
                notifyFileModified(file)
            }
            if ((event and FileObserver.DELETE) != 0 || (event and FileObserver.DELETE_SELF) != 0 ||
                (event and FileObserver.MOVED_FROM) != 0
            ) {
                notifyFileDeleted(file)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error handling file event: $event for $file", e)
        }
    }
    override fun removeFileWatcher(path: String) {
        fileWatchers[path]?.let { it.stopWatching() }
        fileWatchers.remove(path)
        fileListeners.remove(path)
    }

    fun addToRecentFiles(file: File) {
        if (!file.exists() || file.isDirectory) return
        recentFiles.remove(file)
        recentFiles.add(0, file)
        while (recentFiles.size > MAX_RECENT_FILES) {
            recentFiles.removeAt(recentFiles.size - 1)
        }
        saveRecentFiles()
    }

    private fun collectFiles(dir: File): List<File> {
        val files = mutableListOf<File>()
        dir.listFiles()?.forEach { f ->
            files.add(f)
            if (f.isDirectory) files.addAll(collectFiles(f))
        }
        return files
    }

    private fun searchFilesRecursive(dir: File, query: String): List<File> {
        val results = mutableListOf<File>()
        dir.listFiles()?.forEach { f ->
            if (f.name.contains(query, ignoreCase = true)) results.add(f)
            if (f.isDirectory) results.addAll(searchFilesRecursive(f, query))
        }
        return results
    }

    private fun notifyFileCreated(file: File) {
        fileListeners.values.flatten().forEach { l ->
            try { l.onFileCreated(file) } catch (e: Exception) { Log.e(TAG, "Error notifying file created", e) }
        }
    }

    private fun notifyFileModified(file: File) {
        fileListeners.values.flatten().forEach { l ->
            try { l.onFileModified(file) } catch (e: Exception) { Log.e(TAG, "Error notifying file modified", e) }
        }
    }

    private fun notifyFileDeleted(file: File) {
        fileListeners.values.flatten().forEach { l ->
            try { l.onFileDeleted(file) } catch (e: Exception) { Log.e(TAG, "Error notifying file deleted", e) }
        }
    }

    private fun loadRecentFiles() {
        try {
            val paths = configManager.get(ConfigKeys.RecentFiles)
            if (paths.isNotEmpty()) {
                paths.split(";").forEach { p ->
                    val f = File(p)
                    if (f.exists()) recentFiles.add(f)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error loading recent files", e)
        }
    }

    private fun saveRecentFiles() {
        try {
            val paths = recentFiles.joinToString(";") { it.absolutePath }
            configManager.set(ConfigKeys.RecentFiles, paths)
        } catch (e: Exception) {
            Log.e(TAG, "Error saving recent files", e)
        }
    }
}
