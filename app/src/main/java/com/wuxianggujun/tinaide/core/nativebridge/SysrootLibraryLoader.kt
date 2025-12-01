package com.wuxianggujun.tinaide.core.nativebridge

import android.content.Context
import android.os.Build
import android.util.Log
import java.io.File
import java.util.Collections

/**
 * Sysroot 共享库加载器
 * 
 * 统一管理从 sysroot 目录加载共享库的逻辑，避免重复加载和路径计算。
 * 
 * 使用方法：
 * ```kotlin
 * val loader = SysrootLibraryLoader.getInstance(context)
 * loader.loadLibrary("libc++_shared.so")
 * loader.loadLibrary("libLLVM-17.so")
 * loader.loadLibrary("libclang-cpp.so")
 * ```
 */
class SysrootLibraryLoader private constructor(private val context: Context) {

    companion object {
        private const val TAG = "SysrootLibraryLoader"
        
        @Volatile
        private var instance: SysrootLibraryLoader? = null
        
        fun getInstance(context: Context): SysrootLibraryLoader {
            return instance ?: synchronized(this) {
                instance ?: SysrootLibraryLoader(context.applicationContext).also { instance = it }
            }
        }
    }

    // 已加载的库路径集合（避免重复加载）
    private val loadedLibraries: MutableSet<String> = Collections.synchronizedSet(mutableSetOf())
    
    // sysroot 基础目录
    private val sysrootBase: File by lazy {
        File(context.filesDir, "sysroot")
    }
    
    // 当前设备的 triple
    val triple: String by lazy {
        val abi = Build.SUPPORTED_ABIS.firstOrNull() ?: "arm64-v8a"
        when {
            abi.contains("arm64", true) -> "aarch64-linux-android"
            abi.contains("x86_64", true) -> "x86_64-linux-android"
            abi.contains("armeabi", true) -> "arm-linux-androideabi"
            abi.contains("x86", true) -> "i686-linux-android"
            else -> "aarch64-linux-android"
        }
    }
    
    // 当前设备的 ABI
    val abi: String by lazy {
        Build.SUPPORTED_ABIS.firstOrNull() ?: "arm64-v8a"
    }
    
    // 运行时库目录
    val runtimeLibDir: File by lazy {
        File(sysrootBase, "usr/lib/$triple/runtime")
    }
    
    // API 级别库目录（默认 API 28）
    fun apiLibDir(apiLevel: Int = 28): File {
        return File(sysrootBase, "usr/lib/$triple/$apiLevel")
    }
    
    /**
     * 检查 sysroot 是否已安装
     */
    fun isSysrootInstalled(): Boolean {
        return sysrootBase.exists() && runtimeLibDir.exists()
    }
    
    /**
     * 获取库文件路径
     * 
     * @param libName 库名（如 "libc++_shared.so" 或 "c++_shared"）
     * @param preferRuntime 是否优先从 runtime 目录查找
     * @param apiLevel API 级别（用于 API 目录查找）
     * @return 库文件路径，如果不存在则返回 null
     */
    fun getLibraryPath(
        libName: String,
        preferRuntime: Boolean = true,
        apiLevel: Int = 28
    ): File? {
        val normalizedName = normalizeLibName(libName)
        
        val candidates = if (preferRuntime) {
            listOf(
                File(runtimeLibDir, normalizedName),
                File(apiLibDir(apiLevel), normalizedName),
                File(sysrootBase, "usr/lib/$triple/$normalizedName")
            )
        } else {
            listOf(
                File(apiLibDir(apiLevel), normalizedName),
                File(runtimeLibDir, normalizedName),
                File(sysrootBase, "usr/lib/$triple/$normalizedName")
            )
        }
        
        return candidates.firstOrNull { it.exists() }
    }
    
    /**
     * 加载单个库
     * 
     * @param libName 库名（如 "libc++_shared.so" 或 "c++_shared"）
     * @param preferRuntime 是否优先从 runtime 目录查找
     * @param apiLevel API 级别
     * @return true 如果加载成功或已加载
     * @throws UnsatisfiedLinkError 如果库不存在或加载失败
     */
    @Throws(UnsatisfiedLinkError::class)
    fun loadLibrary(
        libName: String,
        preferRuntime: Boolean = true,
        apiLevel: Int = 28
    ): Boolean {
        val libPath = getLibraryPath(libName, preferRuntime, apiLevel)
            ?: throw UnsatisfiedLinkError("Library not found in sysroot: $libName (triple=$triple)")
        
        return loadLibraryFromPath(libPath.absolutePath)
    }
    
    /**
     * 从绝对路径加载库
     * 
     * @param path 库的绝对路径
     * @return true 如果加载成功或已加载
     */
    fun loadLibraryFromPath(path: String): Boolean {
        val canonicalPath = try {
            File(path).canonicalPath
        } catch (_: Exception) {
            path
        }
        
        // 检查是否已加载
        if (loadedLibraries.contains(canonicalPath)) {
            Log.d(TAG, "Library already loaded: $canonicalPath")
            return true
        }
        
        return try {
            System.load(canonicalPath)
            loadedLibraries.add(canonicalPath)
            Log.i(TAG, "Loaded library: $canonicalPath")
            true
        } catch (e: UnsatisfiedLinkError) {
            // 检查是否是"已加载"错误
            if (isAlreadyLoadedError(e)) {
                loadedLibraries.add(canonicalPath)
                Log.d(TAG, "Library was already loaded: $canonicalPath")
                true
            } else {
                Log.e(TAG, "Failed to load library: $canonicalPath", e)
                throw e
            }
        }
    }
    
    /**
     * 批量加载库
     * 
     * @param libNames 库名列表
     * @param preferRuntime 是否优先从 runtime 目录查找
     * @param apiLevel API 级别
     * @param stopOnError 遇到错误时是否停止
     * @return 成功加载的库数量
     */
    fun loadLibraries(
        libNames: List<String>,
        preferRuntime: Boolean = true,
        apiLevel: Int = 28,
        stopOnError: Boolean = false
    ): Int {
        var loaded = 0
        for (name in libNames) {
            try {
                if (loadLibrary(name, preferRuntime, apiLevel)) {
                    loaded++
                }
            } catch (e: UnsatisfiedLinkError) {
                Log.w(TAG, "Failed to load $name: ${e.message}")
                if (stopOnError) break
            }
        }
        return loaded
    }
    
    /**
     * 加载所有编译器运行时库
     * 
     * 按正确的依赖顺序加载：
     * 1. libc++_shared.so (C++ 运行时)
     * 2. libLLVM-17.so (LLVM 核心)
     * 3. libclang-cpp.so (Clang C++ API)
     * 4. libclang.so (libclang C API)
     */
    fun loadCompilerRuntimeLibraries(): Boolean {
        val libs = listOf(
            "libc++_shared.so",
            "libLLVM-17.so",
            "libclang-cpp.so",
            "libclang.so"
        )
        
        return try {
            loadLibraries(libs, preferRuntime = true, stopOnError = true)
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load compiler runtime libraries", e)
            false
        }
    }
    
    /**
     * 检查库是否已加载
     */
    fun isLibraryLoaded(libName: String): Boolean {
        val path = getLibraryPath(libName)?.canonicalPath ?: return false
        return loadedLibraries.contains(path)
    }
    
    /**
     * 获取已加载的库列表
     */
    fun getLoadedLibraries(): Set<String> {
        return loadedLibraries.toSet()
    }
    
    /**
     * 规范化库名
     * "c++_shared" -> "libc++_shared.so"
     * "libc++_shared.so" -> "libc++_shared.so"
     */
    private fun normalizeLibName(name: String): String {
        var result = name
        if (!result.startsWith("lib")) {
            result = "lib$result"
        }
        if (!result.endsWith(".so")) {
            result = "$result.so"
        }
        return result
    }
    
    /**
     * 检查是否是"库已加载"错误
     */
    private fun isAlreadyLoadedError(e: Throwable): Boolean {
        val msg = e.message ?: return false
        return msg.contains("already loaded", ignoreCase = true) ||
               (msg.contains("library \"", ignoreCase = true) && 
                msg.contains("needed or already loaded", ignoreCase = true))
    }
}