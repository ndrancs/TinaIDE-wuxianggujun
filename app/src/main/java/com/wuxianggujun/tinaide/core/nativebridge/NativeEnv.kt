package com.wuxianggujun.tinaide.core.nativebridge

import android.content.Context
import android.util.Log
import java.io.File

/**
 * Native 环境管理器
 * 
 * 负责初始化和管理 Native 层需要的环境变量和路径信息。
 * 必须在使用 xmake/编译器之前调用 init()。
 */
object NativeEnv {
    private const val TAG = "NativeEnv"
    
    /**
     * nativeLibraryDir 路径
     * 例如: /data/app/com.wuxianggujun.tinaide-xxx/lib/arm64
     */
    @Volatile
    lateinit var nativeLibDir: String
        private set
    
    /**
     * sysroot 路径
     * 例如: /data/data/com.wuxianggujun.tinaide/files/sysroot
     */
    @Volatile
    lateinit var sysrootDir: String
        private set
    
    /**
     * 是否已初始化
     */
    @Volatile
    var initialized: Boolean = false
        private set
    
    /**
     * Native 库是否已加载
     */
    @Volatile
    private var nativeLoaded: Boolean = false
    
    /**
     * 初始化环境
     * 
     * @param context Application Context
     */
    fun init(context: Context) {
        if (initialized) return
        
        synchronized(this) {
            if (initialized) return
            
            // 获取 nativeLibraryDir
            nativeLibDir = context.applicationInfo.nativeLibraryDir
            Log.i(TAG, "nativeLibDir: $nativeLibDir")
            
            // 获取 sysroot 路径
            sysrootDir = File(context.filesDir, "sysroot").absolutePath
            Log.i(TAG, "sysrootDir: $sysrootDir")
            
            // 设置给 ProcessBridge
            ProcessBridge.nativeLibDir = nativeLibDir
            ProcessBridge.sysrootDir = sysrootDir
            
            initialized = true
            Log.i(TAG, "NativeEnv initialized")
        }
    }
    
    /**
     * 设置环境变量（需要 Native 库已加载）
     */
    fun setEnv(name: String, value: String) {
        ensureNativeLoaded()
        try {
            nativeSetEnv(name, value)
            Log.d(TAG, "setEnv: $name=$value")
        } catch (e: UnsatisfiedLinkError) {
            Log.w(TAG, "Native method not available: ${e.message}")
        }
    }
    
    /**
     * 获取环境变量（需要 Native 库已加载）
     */
    fun getEnv(name: String): String? {
        ensureNativeLoaded()
        return try {
            nativeGetEnv(name)
        } catch (e: UnsatisfiedLinkError) {
            Log.w(TAG, "Native method not available: ${e.message}")
            null
        }
    }
    
    /**
     * 确保 Native 库已加载
     */
    private fun ensureNativeLoaded() {
        if (nativeLoaded) return
        synchronized(this) {
            if (nativeLoaded) return
            try {
                // 尝试加载 xmake_runner（包含 nativeSetEnv/nativeGetEnv）
                System.loadLibrary("xmake_runner")
                nativeLoaded = true
            } catch (e: UnsatisfiedLinkError) {
                // 可能还没编译，忽略
                Log.d(TAG, "xmake_runner not loaded yet")
            }
        }
    }
    
    /**
     * 获取 target triple
     */
    fun getTargetTriple(): String {
        val abi = android.os.Build.SUPPORTED_ABIS.firstOrNull() ?: "arm64-v8a"
        return when {
            abi.contains("arm64", ignoreCase = true) -> "aarch64-linux-android"
            abi.contains("x86_64", ignoreCase = true) -> "x86_64-linux-android"
            abi.contains("armeabi", ignoreCase = true) -> "arm-linux-androideabi"
            abi.contains("x86", ignoreCase = true) -> "i686-linux-android"
            else -> "aarch64-linux-android"
        }
    }
    
    /**
     * 获取默认 target（带 API level）
     */
    fun getDefaultTarget(apiLevel: Int = 28): String {
        return "${getTargetTriple()}$apiLevel"
    }
    
    /**
     * 设置环境变量（JNI）
     */
    private external fun nativeSetEnv(name: String, value: String)
    
    /**
     * 获取环境变量（JNI）
     */
    private external fun nativeGetEnv(name: String): String?
}
