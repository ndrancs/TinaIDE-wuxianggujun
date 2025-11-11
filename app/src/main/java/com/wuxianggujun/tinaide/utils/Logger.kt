package com.wuxianggujun.tinaide.utils

import android.util.Log
import com.wuxianggujun.tinaide.BuildConfig

/**
 * 统一日志工具
 * 在 Release 版本中自动禁用调试日志
 */
object Logger {
    
    private const val TAG = "TinaIDE"
    
    /**
     * Debug 日志（仅在 Debug 版本显示）
     */
    fun d(message: String, tag: String = TAG) {
        if (BuildConfig.DEBUG) {
            Log.d(tag, message)
        }
    }
    
    /**
     * Info 日志
     */
    fun i(message: String, tag: String = TAG) {
        Log.i(tag, message)
    }
    
    /**
     * Warning 日志
     */
    fun w(message: String, tag: String = TAG) {
        Log.w(tag, message)
    }
    
    /**
     * Error 日志
     */
    fun e(message: String, throwable: Throwable? = null, tag: String = TAG) {
        if (throwable != null) {
            Log.e(tag, message, throwable)
        } else {
            Log.e(tag, message)
        }
    }
    
    /**
     * Verbose 日志（仅在 Debug 版本显示）
     */
    fun v(message: String, tag: String = TAG) {
        if (BuildConfig.DEBUG) {
            Log.v(tag, message)
        }
    }
    
    /**
     * What a Terrible Failure 日志
     */
    fun wtf(message: String, throwable: Throwable? = null, tag: String = TAG) {
        if (throwable != null) {
            Log.wtf(tag, message, throwable)
        } else {
            Log.wtf(tag, message)
        }
    }
}
