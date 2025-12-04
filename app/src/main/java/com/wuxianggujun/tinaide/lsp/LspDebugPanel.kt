package com.wuxianggujun.tinaide.lsp

import android.content.Context
import android.util.Log
import com.wuxianggujun.tinaide.output.LogLevel
import com.wuxianggujun.tinaide.ui.BottomLogBuffer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * LSP 调试面板
 * 用于追踪 Hover/Completion 请求的每个环节
 */
object LspDebugPanel {
    
    private const val TAG = "LspDebugPanel"
    private val timeFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault())
    
    @Volatile
    var enabled = true  // 默认开启调试
    
    private fun timestamp(): String = timeFormat.format(Date())
    
    // ========== Hover 请求追踪 ==========
    
    fun onHoverTriggered(filePath: String, line: Int, column: Int) {
        if (!enabled) return
        log(LogLevel.INFO, "🎯 Hover 触发: $filePath:$line:$column")
    }
    
    fun onHoverRequestSent(requestId: Long, fileUri: String, line: Int, column: Int) {
        if (!enabled) return
        log(LogLevel.DEBUG, "📤 Hover 请求已发送: ID=$requestId, URI=$fileUri, Pos=$line:$column")
    }
    
    fun onHoverPolling(requestId: Long, attempt: Int) {
        if (!enabled) return
        if (attempt % 10 == 0) {  // 每 10 次轮询打印一次
            log(LogLevel.DEBUG, "⏳ Hover 轮询中: ID=$requestId, 尝试=$attempt")
        }
    }
    
    fun onHoverResultReceived(requestId: Long, content: String) {
        if (!enabled) return
        val preview = content.take(50).replace("\n", " ")
        log(LogLevel.SUCCESS, "✅ Hover 结果: ID=$requestId, 内容=\"$preview...\"")
    }
    
    fun onHoverTimeout(requestId: Long) {
        if (!enabled) return
        log(LogLevel.WARN, "⏰ Hover 超时: ID=$requestId")
    }
    
    fun onHoverError(requestId: Long, error: Throwable) {
        if (!enabled) return
        log(LogLevel.ERROR, "❌ Hover 错误: ID=$requestId, ${error.message}")
    }
    
    // ========== Completion 请求追踪 ==========
    
    fun onCompletionTriggered(filePath: String, line: Int, column: Int) {
        if (!enabled) return
        log(LogLevel.INFO, "🎯 Completion 触发: $filePath:$line:$column")
    }
    
    fun onCompletionRequestSent(requestId: Long, fileUri: String, line: Int, column: Int) {
        if (!enabled) return
        log(LogLevel.DEBUG, "📤 Completion 请求已发送: ID=$requestId, URI=$fileUri, Pos=$line:$column")
    }
    
    fun onCompletionPolling(requestId: Long, attempt: Int) {
        if (!enabled) return
        if (attempt % 10 == 0) {
            log(LogLevel.DEBUG, "⏳ Completion 轮询中: ID=$requestId, 尝试=$attempt")
        }
    }
    
    fun onCompletionResultReceived(requestId: Long, itemCount: Int) {
        if (!enabled) return
        log(LogLevel.SUCCESS, "✅ Completion 结果: ID=$requestId, 项数=$itemCount")
    }
    
    fun onCompletionTimeout(requestId: Long) {
        if (!enabled) return
        log(LogLevel.WARN, "⏰ Completion 超时: ID=$requestId")
    }
    
    fun onCompletionError(requestId: Long, error: Throwable) {
        if (!enabled) return
        log(LogLevel.ERROR, "❌ Completion 错误: ID=$requestId, ${error.message}")
    }
    
    // ========== 文档同步追踪 ==========
    
    fun onDocumentOpened(fileUri: String) {
        if (!enabled) return
        log(LogLevel.INFO, "📂 文档已打开: $fileUri")
    }
    
    fun onDocumentChanged(fileUri: String, version: Int) {
        if (!enabled) return
        log(LogLevel.DEBUG, "📝 文档已更新: $fileUri, 版本=$version")
    }
    
    fun onDocumentClosed(fileUri: String) {
        if (!enabled) return
        log(LogLevel.INFO, "📁 文档已关闭: $fileUri")
    }
    
    // ========== LSP 初始化追踪 ==========
    
    fun onLspInitializing(clangdPath: String, workDir: String) {
        if (!enabled) return
        log(LogLevel.INFO, "🚀 LSP 初始化中: clangd=$clangdPath, workDir=$workDir")
    }
    
    fun onLspInitialized() {
        if (!enabled) return
        log(LogLevel.SUCCESS, "✅ LSP 初始化成功")
    }
    
    fun onLspInitFailed(error: String) {
        if (!enabled) return
        log(LogLevel.ERROR, "❌ LSP 初始化失败: $error")
    }
    
    fun onLspShutdown() {
        if (!enabled) return
        log(LogLevel.INFO, "🛑 LSP 已关闭")
    }
    
    // ========== Native 层追踪 ==========
    
    fun onNativeCallStart(method: String, params: String) {
        if (!enabled) return
        log(LogLevel.DEBUG, "🔧 Native 调用: $method($params)")
    }
    
    fun onNativeCallEnd(method: String, result: String) {
        if (!enabled) return
        log(LogLevel.DEBUG, "🔧 Native 返回: $method -> $result")
    }
    
    // ========== 通用日志 ==========
    
    private fun log(level: LogLevel, message: String) {
        val fullMessage = "[${timestamp()}] $message"
        
        // 输出到 Logcat
        when (level) {
            LogLevel.ERROR -> Log.e(TAG, fullMessage)
            LogLevel.WARN -> Log.w(TAG, fullMessage)
            LogLevel.INFO -> Log.i(TAG, fullMessage)
            LogLevel.DEBUG -> Log.d(TAG, fullMessage)
            LogLevel.SUCCESS -> Log.i(TAG, fullMessage)
            LogLevel.VERBOSE -> Log.v(TAG, fullMessage)
            LogLevel.FAIL -> Log.e(TAG, fullMessage)
        }
        
        BottomLogBuffer.append(level, fullMessage)
    }
    
    fun logRaw(message: String) {
        if (!enabled) return
        log(LogLevel.INFO, message)
    }
}
