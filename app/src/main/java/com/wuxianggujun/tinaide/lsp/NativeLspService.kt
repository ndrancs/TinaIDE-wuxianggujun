package com.wuxianggujun.tinaide.lsp

import com.wuxianggujun.tinaide.lsp.model.CompletionResult
import com.wuxianggujun.tinaide.lsp.model.DiagnosticItem
import com.wuxianggujun.tinaide.lsp.model.HoverResult
import com.wuxianggujun.tinaide.lsp.model.Location

/**
 * NativeLspService - 兼容层
 * 
 * 将所有调用转发到 SimpleLspService，保持 API 兼容性
 */
@Suppress("unused")
object NativeLspService {
    
    private const val TAG = "NativeLspService"
    
    // ========================================================================
    // 生命周期
    // ========================================================================
    
    fun nativeInitialize(clangdPath: String, workDir: String): Boolean {
        return SimpleLspService.initialize(clangdPath, workDir)
    }
    
    fun nativeShutdown() {
        SimpleLspService.shutdown()
    }
    
    fun nativeIsInitialized(): Boolean {
        return SimpleLspService.nativeIsInitialized()
    }
    
    fun initialize(clangdPath: String = "/data/data/com.wuxianggujun.tinaide/clangd", workDir: String = "/"): Boolean {
        return SimpleLspService.initialize(clangdPath, workDir)
    }
    
    // ========================================================================
    // 配置
    // ========================================================================
    
    fun setSocketPath(socketPath: String?) {
        // 简化架构不再使用 socket，忽略
    }
    
    fun setDefaultClangdBinary(path: String?) {
        SimpleLspService.setDefaultClangdBinary(path)
    }
    
    fun getConfiguredClangdBinary(): String? = SimpleLspService.getConfiguredClangdBinary()
    
    fun defaultClangdBinaryPath(): String = SimpleLspService.defaultClangdBinaryPath()
    
    // ========================================================================
    // 文档同步
    // ========================================================================
    
    fun nativeDidOpenTextDocument(fileUri: String, content: String) {
        SimpleLspService.nativeDidOpenTextDocument(fileUri, content)
    }
    
    fun nativeDidChangeTextDocument(fileUri: String, content: String, version: Int) {
        SimpleLspService.nativeDidChangeTextDocument(fileUri, content, version)
    }
    
    fun nativeDidCloseTextDocument(fileUri: String) {
        SimpleLspService.nativeDidCloseTextDocument(fileUri)
    }
    
    // ========================================================================
    // LSP 请求（协程友好）
    // ========================================================================
    
    suspend fun requestCompletionAsync(
        fileUri: String,
        line: Int,
        character: Int,
        triggerKind: Int = 1,
        triggerCharacter: String = "",
        timeoutOverrideMs: Long? = null
    ): CompletionResult? {
        return SimpleLspService.requestCompletionAsync(
            fileUri,
            line,
            character,
            triggerKind,
            triggerCharacter,
            timeoutOverrideMs
        )
    }
    
    suspend fun requestHoverAsync(fileUri: String, line: Int, character: Int): HoverResult? {
        return SimpleLspService.requestHoverAsync(fileUri, line, character)
    }
    
    suspend fun requestDefinitionAsync(fileUri: String, line: Int, character: Int): List<Location>? {
        return SimpleLspService.requestDefinitionAsync(fileUri, line, character)
    }
    
    suspend fun requestReferencesAsync(
        fileUri: String,
        line: Int,
        character: Int,
        includeDeclaration: Boolean = true
    ): List<Location>? {
        return SimpleLspService.requestReferencesAsync(fileUri, line, character, includeDeclaration)
    }
    
    // ========================================================================
    // 旧的同步请求接口（不再支持，返回 0）
    // ========================================================================
    
    fun nativeRequestHover(fileUri: String, line: Int, character: Int): Long = 0L
    fun nativeRequestCompletion(fileUri: String, line: Int, character: Int, triggerKind: Int = 1, triggerCharacter: String = ""): Long = 0L
    fun nativeRequestDefinition(fileUri: String, line: Int, character: Int): Long = 0L
    fun nativeRequestReferences(fileUri: String, line: Int, character: Int, includeDeclaration: Boolean = true): Long = 0L
    fun nativeCancelRequest(requestId: Long) {}
    fun nativeGetHoverResult(requestId: Long): HoverResult? = null
    fun nativeGetCompletionResult(requestId: Long): CompletionResult? = null
    fun nativeGetDefinitionResult(requestId: Long): MutableList<Location>? = null
    fun nativeGetReferencesResult(requestId: Long): MutableList<Location>? = null
    
    // ========================================================================
    // 监听器
    // ========================================================================
    
    fun interface DiagnosticsListener {
        fun onDiagnostics(fileUri: String, diagnostics: List<DiagnosticItem>)
    }
    
    enum class HealthEventType {
        INIT_FAILURE, CHANNEL_ERROR, TRANSPORT_ERROR, CLANGD_EXIT, IO_ERROR
    }
    
    data class HealthEvent(val type: HealthEventType, val message: String)
    
    fun interface HealthListener {
        fun onHealthEvent(event: HealthEvent)
    }
    
    fun interface InitializationListener {
        fun onInitializationChanged(initialized: Boolean)
    }
    
    fun addDiagnosticsListener(listener: DiagnosticsListener) {
        SimpleLspService.addDiagnosticsListener { fileUri, diagnostics ->
            listener.onDiagnostics(fileUri, diagnostics)
        }
    }
    
    fun removeDiagnosticsListener(listener: DiagnosticsListener) {
        // 简化实现，不支持移除
    }
    
    fun addHealthListener(listener: HealthListener) {
        SimpleLspService.addHealthListener { type, message ->
            val eventType = try {
                HealthEventType.valueOf(type)
            } catch (e: Exception) {
                HealthEventType.TRANSPORT_ERROR
            }
            listener.onHealthEvent(HealthEvent(eventType, message))
        }
    }
    
    fun removeHealthListener(listener: HealthListener) {}
    
    fun addInitializationListener(listener: InitializationListener) {
        SimpleLspService.addInitializationListener { initialized ->
            listener.onInitializationChanged(initialized)
        }
    }
    
    fun removeInitializationListener(listener: InitializationListener) {}
    
    fun latestDiagnostics(fileUri: String): List<DiagnosticItem> = emptyList()
    
    @JvmStatic
    fun handleNativeDiagnostics(fileUri: String, diagnostics: Array<DiagnosticItem>) {
        // 转发到 SimpleLspService
    }
    
    @JvmStatic
    fun handleNativeHealthEvent(typeName: String, message: String) {
        // 转发到 SimpleLspService
    }
}
