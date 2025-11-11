package com.wuxianggujun.tinaide.utils

import android.content.Context
import android.widget.Toast

/**
 * Toast 工具类
 * 统一管理 Toast 显示
 */
object ToastUtil {
    
    private var currentToast: Toast? = null
    
    /**
     * 显示 Toast（自动取消之前的）
     */
    fun show(context: Context, message: String, duration: Int = Toast.LENGTH_SHORT) {
        currentToast?.cancel()
        currentToast = Toast.makeText(context, message, duration).apply { show() }
    }
    
    /**
     * 显示长时间 Toast
     */
    fun showLong(context: Context, message: String) {
        show(context, message, Toast.LENGTH_LONG)
    }
    
    /**
     * 显示成功消息
     */
    fun showSuccess(context: Context, message: String) {
        show(context, "✅ $message", Toast.LENGTH_SHORT)
    }
    
    /**
     * 显示错误消息
     */
    fun showError(context: Context, message: String) {
        show(context, "❌ $message", Toast.LENGTH_LONG)
    }
    
    /**
     * 显示警告消息
     */
    fun showWarning(context: Context, message: String) {
        show(context, "⚠️ $message", Toast.LENGTH_SHORT)
    }
    
    /**
     * 显示信息消息
     */
    fun showInfo(context: Context, message: String) {
        show(context, "ℹ️ $message", Toast.LENGTH_SHORT)
    }
    
    /**
     * 取消当前显示的 Toast
     */
    fun cancel() {
        currentToast?.cancel()
        currentToast = null
    }
}
