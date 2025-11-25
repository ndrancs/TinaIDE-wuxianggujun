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
            
            // 设置环境变量（供 Native 层使用）
            try {
                nativeSetEnv("TINA_NATIVE_LIB_DIR", nativeLibDir)
                nativeSetEnv("TINA_SYSROOT", sysrootDir)
                nativeSetEnv("TINA_IDE_MODE", "1")
                Log.i(TAG, "Environment variables set")
            } catch (e: UnsatisfiedLinkError) {
                Log.w(TAG, "Native method not available yet: ${e.message}")
            }
            
            initialized = true
            Log.i(TAG, "NativeEnv initialized")
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
    
    // 静态初始化块，加载 native 库
    init {
        try {
            // 尝试加载包含 nativeSetEnv 的库
            // 这个方法会在 xmake_runner 或 native_compiler 中实现
        } catch (e: Throwable) {
            Log.w(TAG, "Native library not loaded yet")
        }
    }
}
