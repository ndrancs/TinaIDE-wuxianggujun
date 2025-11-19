package com.wuxianggujun.tinaide.core.nativebridge

import android.util.Log

/**
 * Ninja 工具的 JNI 包装器
 * 
 * 通过加载 libninja_runner.so 来执行 Ninja 构建，绕过 Android SELinux 限制
 */
object NinjaRunner {
    private const val TAG = "NinjaRunner"
    private var loaded = false
    
    /**
     * 加载 libninja_runner.so
     */
    fun loadIfNeeded() {
        if (loaded) return
        try {
            System.loadLibrary("ninja_runner")
            loaded = true
            Log.i(TAG, "libninja_runner.so loaded successfully")
        } catch (e: UnsatisfiedLinkError) {
            Log.e(TAG, "Failed to load libninja_runner.so", e)
            throw RuntimeException("Ninja runner library not found", e)
        }
    }
    
    /**
     * 执行 Ninja 命令
     * 
     * @param workingDir 工作目录
     * @param args Ninja 参数（不包括 ninja 本身）
     * @return 退出码
     */
    external fun runNinja(workingDir: String, args: Array<String>): Int
    
    /**
     * 获取 Ninja 版本
     */
    external fun getNinjaVersion(): String
}
