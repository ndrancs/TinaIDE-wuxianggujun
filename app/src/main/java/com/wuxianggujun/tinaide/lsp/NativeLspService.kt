package com.wuxianggujun.tinaide.lsp

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
     * @return Hover 内容（Markdown），null 表示未完成
     */
    external fun nativeGetHoverResult(requestId: Long): String?

    // TODO: 添加其他结果获取方法
    // - nativeGetCompletionResult
    // - nativeGetDefinitionResult
    // - nativeGetReferencesResult

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

    // ========================================================================
    // Kotlin 高级封装（可选）
    // ========================================================================

    /**
     * 初始化（使用默认路径）
     */
    fun initialize(clangdPath: String = "/data/data/com.wuxianggujun.tinaide/clangd", workDir: String = "/"): Boolean {
        return nativeInitialize(clangdPath, workDir)
    }

    /**
     * Hover 请求（协程友好）
     */
    suspend fun requestHoverAsync(fileUri: String, line: Int, character: Int): String? {
        return kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            val requestId = nativeRequestHover(fileUri, line, character)

            // 轮询等待结果（TODO: 优化为回调机制）
            var result: String? = null
            var attempts = 0
            while (result == null && attempts < 500) {  // 最多 5 秒
                kotlinx.coroutines.delay(10)
                result = nativeGetHoverResult(requestId)
                attempts++
            }

            result
        }
    }
}
