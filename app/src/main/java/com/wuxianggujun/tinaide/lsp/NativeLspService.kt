package com.wuxianggujun.tinaide.lsp

import android.os.Handler
import android.os.Looper
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import android.util.Log
import com.wuxianggujun.tinaide.lsp.model.CompletionResult
import com.wuxianggujun.tinaide.lsp.model.DiagnosticItem
import com.wuxianggujun.tinaide.lsp.model.HoverResult
import com.wuxianggujun.tinaide.lsp.model.Location
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArraySet
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

enum class NativeLspMode {
    MOCK,
    REAL
}

/**
 * Native LSP 服务
 *
 * 提供高性能的 LSP 客户端实现，使用 C++ 核心和 FlatBuffers 二进制协议
 *
 * 架构：
 * - 所有核心逻辑在 C++ 实现
 * - Kotlin 层仅提供简单封装
 * - 异步非阻塞接口
 * - 零拷贝传输（共享内存）
 *
 * @author Claude Code
 * @date 2025-12-03
 */
object NativeLspService {

    // ========================================================================
    // 加载 Native 库
    // ========================================================================

    init {
        try {
            System.loadLibrary("native_compiler")
            android.util.Log.d(TAG, "Native library loaded successfully")
        } catch (e: UnsatisfiedLinkError) {
            android.util.Log.e(TAG, "Failed to load native library", e)
            throw e
        }
    }

    private const val TAG = "NativeLspService"
    private const val DEFAULT_CLANGD_PATH = "/data/data/com.wuxianggujun.tinaide/clangd"

    @Volatile
    private var currentMode: NativeLspMode = NativeLspMode.MOCK

    @Volatile
    private var currentSocketOverride: String? = null

    @Volatile
    private var overrideClangdPath: String? = null
    private val diagnosticsListeners = CopyOnWriteArraySet<DiagnosticsListener>()
    private val diagnosticsCache = ConcurrentHashMap<String, List<DiagnosticItem>>()
    private val diagnosticsHandler = Handler(Looper.getMainLooper())

    // ========================================================================
    // 生命周期管理
    // ========================================================================

    /**
     * 初始化 LSP 客户端
     *
     * @param clangdPath clangd 可执行文件路径
     * @param workDir 工作目录
     * @return 是否成功
     */
    external fun nativeInitialize(clangdPath: String, workDir: String): Boolean

    /**
     * 关闭 LSP 客户端
     */
    external fun nativeShutdown()

    /**
     * 检查是否已初始化
     */
    external fun nativeIsInitialized(): Boolean

    // ========================================================================
    // 服务器模式配置
    // ========================================================================

    /**
     * 调整 Native 端使用的服务模式（Mock/Real clangd），并可选指定 socket。
     */
    fun setServerMode(mode: NativeLspMode, socketPath: String? = null) {
        applyServerMode(mode, socketPath)
    }

    fun getServerMode(): NativeLspMode = currentMode

    /**
     * 允许在 Kotlin 侧提前告知真实的 libclangd.so 路径，
     * 供 initialize() 默认使用，避免硬编码 /data/data/.../clangd。
     */
    fun setDefaultClangdBinary(path: String?) {
        if (path.isNullOrBlank()) {
            return
        }
        overrideClangdPath = path
        Log.i(TAG, "Configured clangd binary: $path")
    }

    fun getConfiguredClangdBinary(): String? = overrideClangdPath

    fun defaultClangdBinaryPath(): String = DEFAULT_CLANGD_PATH

    // ========================================================================
    // LSP 请求接口
    // ========================================================================

    /**
     * 请求 Hover 信息
     *
     * @param fileUri 文件 URI (file:///path/to/file.cpp)
     * @param line 行号 (0-based)
     * @param character 列号 (0-based)
     * @return 请求 ID
     */
    external fun nativeRequestHover(fileUri: String, line: Int, character: Int): Long

    /**
     * 请求代码补全
     *
     * @param fileUri 文件 URI
     * @param line 行号
     * @param character 列号
     * @param triggerKind 触发类型 (1=手动, 2=触发字符, 3=重新触发)
     * @param triggerCharacter 触发字符 (可选)
     * @return 请求 ID
     */
    external fun nativeRequestCompletion(
        fileUri: String,
        line: Int,
        character: Int,
        triggerKind: Int = 1,
        triggerCharacter: String = ""
    ): Long

    /**
     * 请求定义跳转
     *
     * @return 请求 ID
     */
    external fun nativeRequestDefinition(fileUri: String, line: Int, character: Int): Long

    /**
     * 请求引用查找
     *
     * @param includeDeclaration 是否包含声明
     * @return 请求 ID
     */
    external fun nativeRequestReferences(
        fileUri: String,
        line: Int,
        character: Int,
        includeDeclaration: Boolean = true
    ): Long

    /**
     * 取消请求
     *
     * @param requestId 请求 ID
     */
    external fun nativeCancelRequest(requestId: Long)

    // ========================================================================
    // 结果获取接口
    // ========================================================================

    /**
     * 获取 Hover 结果
     *
     * @param requestId 请求 ID
     * @return Hover 结果，null 表示未完成
     */
    external fun nativeGetHoverResult(requestId: Long): HoverResult?

    /**
     * 获取 Completion 结果
     */
    external fun nativeGetCompletionResult(requestId: Long): CompletionResult?

    /**
     * 获取 Definition 结果
     */
    external fun nativeGetDefinitionResult(requestId: Long): MutableList<Location>?

    /**
     * 获取 References 结果
     */
    external fun nativeGetReferencesResult(requestId: Long): MutableList<Location>?

    // ========================================================================
    // 文件管理
    // ========================================================================

    /**
     * 通知文件打开
     */
    external fun nativeDidOpenTextDocument(fileUri: String, content: String)

    /**
     * 通知文件修改
     */
    external fun nativeDidChangeTextDocument(fileUri: String, content: String, version: Int)

    /**
     * 通知文件关闭
     */
    external fun nativeDidCloseTextDocument(fileUri: String)

    private const val POLL_INTERVAL_MS = 10L
    private const val RESULT_TIMEOUT_MS = 5_000L

    private fun applyServerMode(mode: NativeLspMode, socketPath: String?) {
        currentMode = mode
        currentSocketOverride = socketPath
        val mockFlag = if (mode == NativeLspMode.MOCK) "1" else "0"
        setEnvSafe("TINAIDE_NATIVE_LSP_USE_MOCK", mockFlag)
        if (socketPath.isNullOrBlank()) {
            clearEnvSafe("TINAIDE_LSP_SOCKET")
        } else {
            setEnvSafe("TINAIDE_LSP_SOCKET", socketPath)
        }
    }

    private fun setEnvSafe(key: String, value: String) {
        try {
            Os.setenv(key, value, true)
        } catch (err: ErrnoException) {
            Log.w(TAG, "setenv($key) failed: ${err.message}")
        }
    }

    private fun clearEnvSafe(key: String) {
        try {
            Os.unsetenv(key)
        } catch (err: ErrnoException) {
            if (err.errno != OsConstants.ENOENT) {
                Log.w(TAG, "unsetenv($key) failed: ${err.message}")
            }
        }
    }

    private suspend fun <T> waitForResult(fetch: () -> T?): T? {
        val maxAttempts = (RESULT_TIMEOUT_MS / POLL_INTERVAL_MS).toInt()
        repeat(maxAttempts) {
            val result = fetch()
            if (result != null) {
                return result
            }
            delay(POLL_INTERVAL_MS)
        }
        return null
    }

    // ========================================================================
    // Kotlin 高级封装（可选）
    // ========================================================================

    /**
     * 初始化（使用默认路径）
     */
    fun initialize(
        clangdPath: String = DEFAULT_CLANGD_PATH,
        workDir: String = "/",
        mode: NativeLspMode = currentMode,
        socketPath: String? = currentSocketOverride
    ): Boolean {
        applyServerMode(mode, socketPath)
        val effectiveClangdPath = resolveClangdPath(clangdPath)
        return nativeInitialize(effectiveClangdPath, workDir)
    }

    /**
     * Hover 请求（协程友好）
     */
    suspend fun requestHoverAsync(fileUri: String, line: Int, character: Int): HoverResult? {
        return withContext(Dispatchers.IO) {
            val requestId = nativeRequestHover(fileUri, line, character)
            waitForResult { nativeGetHoverResult(requestId) }
        }
    }

    /**
     * Completion 请求（协程友好）
     */
    suspend fun requestCompletionAsync(
        fileUri: String,
        line: Int,
        character: Int,
        triggerKind: Int = 1,
        triggerCharacter: String = ""
    ): CompletionResult? {
        return withContext(Dispatchers.IO) {
            val requestId = nativeRequestCompletion(fileUri, line, character, triggerKind, triggerCharacter)
            waitForResult { nativeGetCompletionResult(requestId) }
        }
    }

    /**
     * Definition 请求（协程友好）
     */
    suspend fun requestDefinitionAsync(fileUri: String, line: Int, character: Int): List<Location>? {
        return withContext(Dispatchers.IO) {
            val requestId = nativeRequestDefinition(fileUri, line, character)
            waitForResult { nativeGetDefinitionResult(requestId)?.toList() }
        }
    }

    /**
     * References 请求（协程友好）
     */
    suspend fun requestReferencesAsync(
        fileUri: String,
        line: Int,
        character: Int,
        includeDeclaration: Boolean = true
    ): List<Location>? {
        return withContext(Dispatchers.IO) {
            val requestId = nativeRequestReferences(fileUri, line, character, includeDeclaration)
            waitForResult { nativeGetReferencesResult(requestId)?.toList() }
        }
    }

    private fun resolveClangdPath(candidate: String): String {
        val override = overrideClangdPath
        if (!override.isNullOrBlank()) {
            return override
        }
        return candidate
    }

    // ========================================================================
    // Diagnostics
    // ========================================================================

    fun interface DiagnosticsListener {
        fun onDiagnostics(fileUri: String, diagnostics: List<DiagnosticItem>)
    }

    fun addDiagnosticsListener(listener: DiagnosticsListener) {
        diagnosticsListeners.add(listener)
    }

    fun removeDiagnosticsListener(listener: DiagnosticsListener) {
        diagnosticsListeners.remove(listener)
    }

    fun latestDiagnostics(fileUri: String): List<DiagnosticItem> =
        diagnosticsCache[fileUri].orEmpty()

    @JvmStatic
    fun handleNativeDiagnostics(fileUri: String, diagnostics: Array<DiagnosticItem>) {
        val snapshot = diagnostics.toList()
        diagnosticsCache[fileUri] = snapshot
        diagnosticsHandler.post {
            diagnosticsListeners.forEach { listener ->
                listener.onDiagnostics(fileUri, snapshot)
            }
        }
    }
}
