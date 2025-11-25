package com.wuxianggujun.tinaide.core.nativebridge

import android.util.Log
import com.wuxianggujun.tinaide.TinaApplication
import java.io.File

/**
 * xmake 构建工具 JNI 接口
 * 
 * 通过 libxmake_runner.so 调用 xmake 功能（从 sysroot 加载）
 */
object XmakeRunner {
    private const val TAG = "XmakeRunner"
    
    @Volatile
    private var loaded = false
    
    /**
     * 从 sysroot 加载 xmake_runner 库
     */
    fun loadIfNeeded() {
        if (loaded) return
        synchronized(this) {
            if (loaded) return
            try {
                // 从 sysroot 加载 libxmake_runner.so
                val ctx = TinaApplication.instance
                val sysrootBase = File(ctx.filesDir, "sysroot")
                val abi = android.os.Build.SUPPORTED_ABIS.firstOrNull() ?: ""
                val triple = when {
                    abi.contains("arm64", true) -> "aarch64-linux-android"
                    abi.contains("x86_64", true) -> "x86_64-linux-android"
                    else -> "aarch64-linux-android"
                }
                
                // 尝试多个可能的路径
                val candidates = listOf(
                    File(sysrootBase, "usr/lib/$triple/libxmake_runner.so"),
                    File(sysrootBase, "usr/lib/$triple/runtime/libxmake_runner.so"),
                    File(sysrootBase, "usr/bin/libxmake_runner.so")
                )
                
                var loadedPath: String? = null
                for (candidate in candidates) {
                    if (candidate.exists()) {
                        System.load(candidate.absolutePath)
                        loadedPath = candidate.absolutePath
                        break
                    }
                }
                
                if (loadedPath != null) {
                    loaded = true
                    Log.i(TAG, "Loaded xmake_runner from sysroot: $loadedPath")
                } else {
                    throw UnsatisfiedLinkError("libxmake_runner.so not found in sysroot. Tried: ${candidates.map { it.absolutePath }}")
                }
            } catch (t: Throwable) {
                Log.e(TAG, "Failed to load xmake_runner: ${t.message}")
                throw t
            }
        }
    }
    
    fun isLoaded(): Boolean = loaded
    
    /**
     * 运行 xmake 命令
     * 
     * @param argc 参数数量
     * @param argv 参数数组（例如 ["xmake", "build", "-v"]）
     * @return 返回码（0 表示成功）
     */
    external fun xmake_run(argc: Int, argv: Array<String>): Int
    
    /**
     * 便捷方法：运行 xmake 命令
     * 
     * @param args 命令参数（不包含 "xmake" 本身）
     * @return 返回码
     */
    fun run(vararg args: String): Int {
        loadIfNeeded()
        val argv = arrayOf("xmake") + args
        return xmake_run(argv.size, argv)
    }
    
    /**
     * 构建项目
     * 
     * @param projectDir 项目目录
     * @param verbose 是否显示详细输出
     * @return 返回码
     */
    fun build(projectDir: String, verbose: Boolean = false): Int {
        loadIfNeeded()
        val args = mutableListOf("xmake", "-P", projectDir)
        if (verbose) args.add("-v")
        return xmake_run(args.size, args.toTypedArray())
    }
    
    /**
     * 清理项目
     */
    fun clean(projectDir: String): Int {
        loadIfNeeded()
        val args = arrayOf("xmake", "clean", "-P", projectDir)
        return xmake_run(args.size, args)
    }
    
    /**
     * 配置项目
     */
    fun config(projectDir: String, vararg options: String): Int {
        loadIfNeeded()
        val args = arrayOf("xmake", "f", "-P", projectDir) + options
        return xmake_run(args.size, args)
    }
}
