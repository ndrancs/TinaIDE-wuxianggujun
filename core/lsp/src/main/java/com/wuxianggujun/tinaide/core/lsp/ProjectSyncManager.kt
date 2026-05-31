package com.wuxianggujun.tinaide.core.lsp

import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.GZIPOutputStream
import timber.log.Timber
import com.wuxianggujun.tinaide.core.i18n.Strings
import com.wuxianggujun.tinaide.core.i18n.str
import com.wuxianggujun.tinaide.core.lang.CxxFileSupport
import com.wuxianggujun.tinaide.core.lang.ProjectPathFilters

/**
 * 项目同步状态
 */
enum class ProjectSyncState {
    IDLE,           // 空闲
    SCANNING,       // 扫描项目
    COMPRESSING,    // 压缩文件
    UPLOADING,      // 上传中
    SYNCED,         // 已同步
    ERROR           // 错误
}

/**
 * 项目同步进度
 */
data class ProjectSyncProgress(
    val state: ProjectSyncState = ProjectSyncState.IDLE,
    val currentFile: String = "",
    val processedFiles: Int = 0,
    val totalFiles: Int = 0,
    val processedBytes: Long = 0,
    val totalBytes: Long = 0,
    val currentChunk: Int = 0,
    val totalChunks: Int = 0,
    val errorMessage: String? = null
) {
    val progressPercent: Float
        get() = if (totalFiles > 0) processedFiles.toFloat() / totalFiles else 0f

    val bytesProgressPercent: Float
        get() = if (totalBytes > 0) processedBytes.toFloat() / totalBytes else 0f

    val chunkProgressPercent: Float
        get() = if (totalChunks > 0) currentChunk.toFloat() / totalChunks else 0f
}

/**
 * 项目文件信息
 */
data class ProjectFileInfo(
    val relativePath: String,
    val content: String,
    val size: Long
)

/**
 * 分块同步配置
 */
data class ChunkConfig(
    val maxChunkSize: Long = 512 * 1024,  // 每个块最大 512KB
    val maxFilesPerChunk: Int = 50,        // 每个块最多 50 个文件
    val enabled: Boolean = true            // 是否启用分块传输
)

/**
 * 同步块信息
 */
data class SyncChunk(
    val chunkIndex: Int,
    val totalChunks: Int,
    val files: List<ProjectFileInfo>,
    val isLast: Boolean
)

/**
 * 项目同步管理器
 *
 * 负责：
 * 1. 扫描项目目录
 * 2. 过滤不需要同步的文件
 * 3. 压缩项目文件
 * 4. 生成同步消息
 * 5. 跟踪文件变更
 */
object ProjectSyncManager {

    private const val TAG = "ProjectSyncManager"

    // 同步进度状态流
    private val _progressFlow = MutableStateFlow(ProjectSyncProgress())
    val progressFlow: StateFlow<ProjectSyncProgress> = _progressFlow.asStateFlow()

    // 已同步的项目根目录
    private var syncedProjectRoot: String? = null

    // 已同步的文件列表（用于增量同步）
    private val syncedFiles = mutableMapOf<String, Long>() // path -> lastModified

    // 默认忽略的目录和文件
    private val defaultIgnorePatterns = ProjectPathFilters.SYNC_IGNORE_PATTERNS

    // 源代码文件扩展名（优先同步）
    private val sourceExtensions: Set<String> =
        CxxFileSupport.editorRelatedExtensions + setOf(
            // 一些项目会把头文件/片段写成 .inc
            "inc",
            // Java/Kotlin
            "java", "kt", "kts",
            // Python
            "py", "pyi",
            // JavaScript/TypeScript
            "js", "jsx", "ts", "tsx",
            // Rust
            "rs",
            // Go
            "go",
            // 配置文件
            "json", "xml", "yaml", "yml", "toml",
            // CMake
            "cmake", "txt" // CMakeLists.txt
        )

    /**
     * 扫描项目目录
     *
     * @param projectRoot 项目根目录
     * @param customIgnorePatterns 自定义忽略模式
     * @return 项目文件列表
     */
    suspend fun scanProject(
        projectRoot: File,
        customIgnorePatterns: List<String> = emptyList()
    ): List<ProjectFileInfo> = withContext(Dispatchers.IO) {
        val files = mutableListOf<ProjectFileInfo>()
        val ignorePatterns = defaultIgnorePatterns + customIgnorePatterns

        updateProgress(ProjectSyncState.SCANNING, totalFiles = 0)

        try {
            scanDirectory(projectRoot, projectRoot, files, ignorePatterns)
            Timber.tag(TAG).d("Scanned ${files.size} files from ${projectRoot.absolutePath}")
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Failed to scan project")
            updateProgress(ProjectSyncState.ERROR, errorMessage = e.message)
        }

        files
    }

    /**
     * 递归扫描目录
     */
    private fun scanDirectory(
        root: File,
        current: File,
        files: MutableList<ProjectFileInfo>,
        ignorePatterns: List<String>
    ) {
        val children = current.listFiles() ?: return

        for (child in children) {
            val relativePath = child.relativeTo(root).path.replace('\\', '/')

            // 检查是否应该忽略
            if (shouldIgnore(relativePath, child.isDirectory, ignorePatterns)) {
                continue
            }

            if (child.isDirectory) {
                scanDirectory(root, child, files, ignorePatterns)
            } else if (child.isFile && isSourceFile(child)) {
                try {
                    val content = child.readText()
                    files.add(
                        ProjectFileInfo(
                            relativePath = relativePath,
                            content = content,
                            size = child.length()
                        )
                    )
                    updateProgress(
                        ProjectSyncState.SCANNING,
                        currentFile = relativePath,
                        processedFiles = files.size
                    )
                } catch (e: Exception) {
                    Timber.tag(TAG).w(e, "Failed to read file: $relativePath")
                }
            }
        }
    }

    /**
     * 检查是否应该忽略该路径
     */
    private fun shouldIgnore(path: String, isDirectory: Boolean, patterns: List<String>): Boolean {
        val pathToCheck = if (isDirectory) "$path/" else path

        for (pattern in patterns) {
            when {
                // 目录模式（以 / 结尾）
                pattern.endsWith("/") -> {
                    if (isDirectory && (pathToCheck.startsWith(pattern) || pathToCheck.contains("/$pattern"))) {
                        return true
                    }
                }
                // 通配符模式
                pattern.startsWith("*") -> {
                    val suffix = pattern.substring(1)
                    if (path.endsWith(suffix)) {
                        return true
                    }
                }
                // 精确匹配
                else -> {
                    if (path == pattern || path.endsWith("/$pattern")) {
                        return true
                    }
                }
            }
        }
        return false
    }

    /**
     * 检查是否是源代码文件
     */
    private fun isSourceFile(file: File): Boolean {
        val extension = file.extension.lowercase()
        // CMakeLists.txt 特殊处理
        if (file.name == "CMakeLists.txt" || file.name == "compile_commands.json") {
            return true
        }
        return extension in sourceExtensions
    }

    /**
     * 生成项目同步消息（JSON-RPC 格式）
     *
     * @param projectName 项目名称
     * @param files 项目文件列表
     * @param compress 是否压缩内容
     * @return JSON-RPC 消息字符串
     */
    suspend fun generateSyncMessage(
        projectName: String,
        files: List<ProjectFileInfo>,
        compress: Boolean = true
    ): Pair<String, Long> = withContext(Dispatchers.IO) {
        updateProgress(ProjectSyncState.COMPRESSING, totalFiles = files.size)

        val totalSize = files.sumOf { it.size }
        val filesArray = JSONArray()

        files.forEachIndexed { index, file ->
            val fileObj = JSONObject().apply {
                put("path", file.relativePath)
                put("content", if (compress) compressContent(file.content) else file.content)
                if (compress) {
                    put("compressed", true)
                }
            }
            filesArray.put(fileObj)

            updateProgress(
                ProjectSyncState.COMPRESSING,
                currentFile = file.relativePath,
                processedFiles = index + 1,
                totalFiles = files.size
            )
        }

        val params = JSONObject().apply {
            put("projectName", projectName)
            put("files", filesArray)
            put("totalSize", totalSize)
            put("fileCount", files.size)
            put("compressed", compress)
        }

        val message = JSONObject().apply {
            put("jsonrpc", "2.0")
            put("method", "tina/syncProject")
            put("params", params)
        }

        val messageStr = message.toString()
        Pair(messageStr, messageStr.length.toLong())
    }

    /**
     * 压缩内容（gzip + base64）
     */
    private fun compressContent(content: String): String {
        val byteArrayOutputStream = ByteArrayOutputStream()
        GZIPOutputStream(byteArrayOutputStream).use { gzip ->
            gzip.write(content.toByteArray(Charsets.UTF_8))
        }
        return Base64.encodeToString(byteArrayOutputStream.toByteArray(), Base64.NO_WRAP)
    }

    /**
     * 将文件列表分割成多个块
     *
     * @param files 项目文件列表
     * @param config 分块配置
     * @return 分块列表
     */
    fun splitIntoChunks(
        files: List<ProjectFileInfo>,
        config: ChunkConfig = ChunkConfig()
    ): List<SyncChunk> {
        if (!config.enabled || files.isEmpty()) {
            return listOf(SyncChunk(0, 1, files, true))
        }

        val chunks = mutableListOf<SyncChunk>()
        var currentChunkFiles = mutableListOf<ProjectFileInfo>()
        var currentChunkSize = 0L

        for (file in files) {
            // 检查是否需要开始新的块
            val shouldStartNewChunk = currentChunkFiles.isNotEmpty() && (
                currentChunkSize + file.size > config.maxChunkSize ||
                currentChunkFiles.size >= config.maxFilesPerChunk
            )

            if (shouldStartNewChunk) {
                chunks.add(SyncChunk(
                    chunkIndex = chunks.size,
                    totalChunks = 0, // 稍后更新
                    files = currentChunkFiles.toList(),
                    isLast = false
                ))
                currentChunkFiles = mutableListOf()
                currentChunkSize = 0L
            }

            currentChunkFiles.add(file)
            currentChunkSize += file.size
        }

        // 添加最后一个块
        if (currentChunkFiles.isNotEmpty()) {
            chunks.add(SyncChunk(
                chunkIndex = chunks.size,
                totalChunks = 0,
                files = currentChunkFiles.toList(),
                isLast = true
            ))
        }

        // 更新 totalChunks
        val totalChunks = chunks.size
        return chunks.mapIndexed { index, chunk ->
            chunk.copy(
                totalChunks = totalChunks,
                isLast = index == totalChunks - 1
            )
        }
    }

    /**
     * 生成分块同步消息
     *
     * @param projectName 项目名称
     * @param chunk 同步块
     * @param sessionId 同步会话 ID（用于服务器端组装）
     * @param compress 是否压缩
     * @return JSON-RPC 消息字符串
     */
    suspend fun generateChunkSyncMessage(
        projectName: String,
        chunk: SyncChunk,
        sessionId: String,
        compress: Boolean = true
    ): String = withContext(Dispatchers.IO) {
        val filesArray = JSONArray()

        chunk.files.forEach { file ->
            val fileObj = JSONObject().apply {
                put("path", file.relativePath)
                put("content", if (compress) compressContent(file.content) else file.content)
                if (compress) {
                    put("compressed", true)
                }
            }
            filesArray.put(fileObj)
        }

        val params = JSONObject().apply {
            put("projectName", projectName)
            put("sessionId", sessionId)
            put("chunkIndex", chunk.chunkIndex)
            put("totalChunks", chunk.totalChunks)
            put("files", filesArray)
            put("fileCount", chunk.files.size)
            put("isLast", chunk.isLast)
            put("compressed", compress)
        }

        JSONObject().apply {
            put("jsonrpc", "2.0")
            put("method", "tina/syncProjectChunk")
            put("params", params)
        }.toString()
    }

    /**
     * 生成分块同步开始消息
     *
     * @param projectName 项目名称
     * @param sessionId 同步会话 ID
     * @param totalFiles 总文件数
     * @param totalSize 总大小
     * @param totalChunks 总块数
     * @return JSON-RPC 消息字符串
     */
    fun generateChunkSyncStartMessage(
        projectName: String,
        sessionId: String,
        totalFiles: Int,
        totalSize: Long,
        totalChunks: Int
    ): String {
        val params = JSONObject().apply {
            put("projectName", projectName)
            put("sessionId", sessionId)
            put("totalFiles", totalFiles)
            put("totalSize", totalSize)
            put("totalChunks", totalChunks)
        }

        return JSONObject().apply {
            put("jsonrpc", "2.0")
            put("method", "tina/syncProjectStart")
            put("params", params)
        }.toString()
    }

    /**
     * 生成唯一的同步会话 ID
     */
    fun generateSessionId(): String {
        return "sync-${System.currentTimeMillis()}-${(Math.random() * 10000).toInt()}"
    }

    /**
     * 判断是否应该使用分块传输
     *
     * @param files 文件列表
     * @param config 分块配置
     * @return 是否应该使用分块传输
     */
    fun shouldUseChunkedTransfer(
        files: List<ProjectFileInfo>,
        config: ChunkConfig = ChunkConfig()
    ): Boolean {
        if (!config.enabled) return false

        val totalSize = files.sumOf { it.size }
        val fileCount = files.size

        // 如果总大小超过 1MB 或文件数超过 100，使用分块传输
        return totalSize > 1024 * 1024 || fileCount > 100
    }

    /**
     * 生成文件变更消息
     *
     * @param type 变更类型：created, deleted, renamed, modified
     * @param path 文件路径
     * @param content 文件内容（仅 created 和 modified 需要）
     * @param oldPath 旧路径（仅 renamed 需要）
     * @return JSON-RPC 消息字符串
     */
    fun generateFileChangedMessage(
        type: String,
        path: String,
        content: String? = null,
        oldPath: String? = null
    ): String {
        val params = JSONObject().apply {
            put("type", type)
            put("path", path)
            content?.let { put("content", it) }
            oldPath?.let { put("oldPath", it) }
        }

        return JSONObject().apply {
            put("jsonrpc", "2.0")
            put("method", "tina/fileChanged")
            put("params", params)
        }.toString()
    }

    /**
     * 检测项目特征，判断应该使用哪种同步模式
     *
     * @param projectRoot 项目根目录
     * @return 推荐的同步模式和原因
     */
    suspend fun detectSyncMode(projectRoot: File): Pair<RemoteLspSyncMode, String> = withContext(Dispatchers.IO) {
        // 检查是否存在 CMakeLists.txt 或 compile_commands.json
        val hasCMake = File(projectRoot, "CMakeLists.txt").exists()
        val hasCompileCommands = hasProjectCompileCommands(projectRoot)

        if (hasCMake || hasCompileCommands) {
            return@withContext Pair(
                RemoteLspSyncMode.PROJECT,
                if (hasCMake) Strings.lsp_sync_reason_has_cmake.str()
                else Strings.lsp_sync_reason_has_compile_commands.str()
            )
        }

        // 统计项目文件
        var fileCount = 0
        var totalSize = 0L

        fun countFiles(dir: File) {
            dir.listFiles()?.forEach { file ->
                if (file.isDirectory) {
                    if (!shouldIgnore(file.name + "/", true, defaultIgnorePatterns)) {
                        countFiles(file)
                    }
                } else if (isSourceFile(file)) {
                    fileCount++
                    totalSize += file.length()
                }
            }
        }

        countFiles(projectRoot)

        // 判断规则
        return@withContext when {
            fileCount > 20 || totalSize > 1024 * 1024 -> {
                Pair(
                    RemoteLspSyncMode.PROJECT,
                    Strings.lsp_sync_reason_project_recommended.str(fileCount, formatSize(totalSize))
                )
            }
            else -> {
                Pair(
                    RemoteLspSyncMode.LIGHTWEIGHT,
                    Strings.lsp_sync_reason_lightweight.str(fileCount, formatSize(totalSize))
                )
            }
        }
    }

    private fun hasProjectCompileCommands(projectRoot: File): Boolean {
        val candidates = listOf(
            "compile_commands.json",
            "build/compile_commands.json",
            "build/debug/compile_commands.json",
            "build/release/compile_commands.json",
            "cmake-build-debug/compile_commands.json",
            "cmake-build-release/compile_commands.json",
            "out/build/compile_commands.json"
        )
        return candidates.any { relative ->
            File(projectRoot, relative).let { it.isFile && it.length() > 0L }
        }
    }

    /**
     * 格式化文件大小
     */
    private fun formatSize(bytes: Long): String {
        return when {
            bytes < 1024 -> "$bytes B"
            bytes < 1024 * 1024 -> "${bytes / 1024} KB"
            else -> "${bytes / (1024 * 1024)} MB"
        }
    }

    /**
     * 更新同步进度
     */
    private fun updateProgress(
        state: ProjectSyncState,
        currentFile: String = _progressFlow.value.currentFile,
        processedFiles: Int = _progressFlow.value.processedFiles,
        totalFiles: Int = _progressFlow.value.totalFiles,
        processedBytes: Long = _progressFlow.value.processedBytes,
        totalBytes: Long = _progressFlow.value.totalBytes,
        currentChunk: Int = _progressFlow.value.currentChunk,
        totalChunks: Int = _progressFlow.value.totalChunks,
        errorMessage: String? = null
    ) {
        _progressFlow.value = ProjectSyncProgress(
            state = state,
            currentFile = currentFile,
            processedFiles = processedFiles,
            totalFiles = totalFiles,
            processedBytes = processedBytes,
            totalBytes = totalBytes,
            currentChunk = currentChunk,
            totalChunks = totalChunks,
            errorMessage = errorMessage
        )
    }

    /**
     * 更新分块上传进度（公开方法，供外部调用）
     */
    fun updateChunkProgress(currentChunk: Int, totalChunks: Int) {
        updateProgress(
            state = ProjectSyncState.UPLOADING,
            currentChunk = currentChunk,
            totalChunks = totalChunks
        )
    }

    /**
     * 重置同步状态
     */
    fun reset() {
        _progressFlow.value = ProjectSyncProgress()
        syncedProjectRoot = null
        syncedFiles.clear()
    }

    /**
     * 标记项目已同步
     */
    fun markSynced(projectRoot: String, files: List<ProjectFileInfo>) {
        syncedProjectRoot = projectRoot
        syncedFiles.clear()
        files.forEach { file ->
            syncedFiles[file.relativePath] = System.currentTimeMillis()
        }
        updateProgress(ProjectSyncState.SYNCED, processedFiles = files.size, totalFiles = files.size)
    }

    /**
     * 检查文件是否已同步
     */
    fun isFileSynced(relativePath: String): Boolean {
        return syncedFiles.containsKey(relativePath)
    }

    /**
     * 获取已同步的项目根目录
     */
    fun getSyncedProjectRoot(): String? = syncedProjectRoot
}
