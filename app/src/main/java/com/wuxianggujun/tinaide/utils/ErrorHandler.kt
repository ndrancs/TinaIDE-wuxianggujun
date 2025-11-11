package com.wuxianggujun.tinaide.utils

import android.content.Context
import android.util.Log
import com.wuxianggujun.tinaide.ui.dialog.MaterialDialogBuilder
import java.io.IOException

/**
 * 统一错误处理工具
 */
object ErrorHandler {
    
    private const val TAG = "ErrorHandler"
    
    /**
     * 处理错误并显示对话框
     */
    fun handle(
        context: Context,
        error: Throwable,
        title: String = "错误",
        onDismiss: (() -> Unit)? = null
    ) {
        val message = getErrorMessage(error)
        
        // 记录日志
        Log.e(TAG, "Error: $title - $message", error)
        
        // 显示错误对话框
        MaterialDialogBuilder.showError(
            context = context,
            title = title,
            message = message,
            onPositive = onDismiss
        )
    }
    
    /**
     * 处理错误并显示 Toast
     */
    fun handleWithToast(
        context: Context,
        error: Throwable,
        prefix: String = ""
    ) {
        val message = getErrorMessage(error)
        val fullMessage = if (prefix.isNotEmpty()) "$prefix: $message" else message
        
        // 记录日志
        Log.e(TAG, "Error: $fullMessage", error)
        
        // 显示 Toast
        ToastUtil.showError(context, fullMessage)
    }
    
    /**
     * 只记录错误日志，不显示
     */
    fun log(error: Throwable, tag: String = TAG) {
        Log.e(tag, "Error: ${error.message}", error)
    }
    
    /**
     * 获取友好的错误消息
     */
    private fun getErrorMessage(error: Throwable): String {
        return when (error) {
            is IOException -> "文件操作失败: ${error.message ?: "未知错误"}"
            is SecurityException -> "权限不足: ${error.message ?: "未知错误"}"
            is IllegalArgumentException -> "参数错误: ${error.message ?: "未知错误"}"
            is IllegalStateException -> "状态错误: ${error.message ?: "未知错误"}"
            is NullPointerException -> "数据异常: 空指针错误"
            is OutOfMemoryError -> "内存不足，请关闭其他应用后重试"
            is Exception -> error.message ?: "未知错误"
            else -> "发生了一个未知错误"
        }
    }
}

/**
 * Context 扩展函数 - 处理错误
 */
fun Context.handleError(
    error: Throwable,
    title: String = "错误",
    onDismiss: (() -> Unit)? = null
) {
    ErrorHandler.handle(this, error, title, onDismiss)
}

/**
 * Context 扩展函数 - 使用 Toast 处理错误
 */
fun Context.handleErrorWithToast(error: Throwable, prefix: String = "") {
    ErrorHandler.handleWithToast(this, error, prefix)
}
