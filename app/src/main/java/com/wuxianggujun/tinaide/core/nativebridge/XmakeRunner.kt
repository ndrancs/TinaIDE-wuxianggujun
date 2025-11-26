package com.wuxianggujun.tinaide.core.nativebridge

import android.content.Context
import android.util.Log
import com.wuxianggujun.tinaide.TinaApplication
import java.io.File

/**
 * xmake 构建工具 JNI 接口
 *
 * 当前方案：在 Docker 中完成 xmake/tbox 的定制编译，将产物连同 Lua 脚本
 * 打包到应用私有目录的 sysroot 后再由 Native 编译链复用。
 *
 * 在 Android 上，xmake 内部的进程创建会通过 ProcessBridge 桥接到 Java 层，
 * 将编译器调用转换为 NativeCompiler 的进程内调用，绕过 fork/exec 限制。
 */
object XmakeRunner {
    private const val TAG = "XmakeRunner"
    
    @Volatile
    private var loaded = false
    
    @Volatile
    private var processBridgeInitialized = false
    
    /**
     * 初始化进程桥接（在加载 xmake_runner 之前调用）
     */
    fun initProcessBridge(context: Context) {
        if (processBridgeInitialized) return
        synchronized(this) {
            if (processBridgeInitialized) return
            
            // 初始化 NativeEnv
            NativeEnv.init(context)

            // 确保 sysroot 已完整解压，再下发给 ProcessBridge
            val sysrootDir = SysrootInstaller.ensureInstalled(context).absolutePath

            // 设置 ProcessBridge 路径
            ProcessBridge.nativeLibDir = context.applicationInfo.nativeLibraryDir
            ProcessBridge.sysrootDir = sysrootDir

            Log.i(TAG, "ProcessBridge initialized")
            Log.i(TAG, "  nativeLibDir: ${ProcessBridge.nativeLibDir}")
            Log.i(TAG, "  sysrootDir: ${ProcessBridge.sysrootDir}")

            processBridgeInitialized = true
        }
    }
    
    /**
     * 加载 xmake_runner 库
     *
     * 只允许从 sysroot（Docker 构建的产物）加载，彻底移除 jniLibs 回退。
     */
    fun loadIfNeeded() {
        if (loaded) return
        synchronized(this) {
            if (loaded) return

            val ctx = TinaApplication.instance

            if (!processBridgeInitialized) {
                initProcessBridge(ctx)
            }

            val sysrootCandidates = resolveSysrootLibraryCandidates(ctx)
            var lastError: Throwable? = null
            for (candidate in sysrootCandidates) {
                if (!candidate.exists()) continue
                try {
                    System.load(candidate.absolutePath)
                    Log.i(TAG, "Loaded xmake_runner from sysroot: ${candidate.absolutePath}")
                    initNativeProcessBridge(ctx)
                    loaded = true
                    return
                } catch (t: Throwable) {
                    lastError = t
                    Log.w(TAG, "Failed to load ${candidate.absolutePath}: ${t.message}")
                }
            }

            val searched = sysrootCandidates.map { it.absolutePath }
            val message = buildString {
                append("libxmake_runner.so not found in sysroot. ")
                append("Ensure Docker sysroot artifacts are synced via tools/sync scripts. ")
                append("Candidates: ")
                append(searched)
            }
            Log.e(TAG, message)
            val error = UnsatisfiedLinkError(message)
            if (lastError != null) {
                error.initCause(lastError)
            }
            throw error
        }
    }
    
    private fun resolveSysrootLibraryCandidates(ctx: Context): List<File> {
        val sysrootBase = SysrootInstaller.ensureInstalled(ctx)
        val abi = android.os.Build.SUPPORTED_ABIS.firstOrNull() ?: ""
        val triple = when {
            abi.contains("arm64", true) -> "aarch64-linux-android"
            abi.contains("x86_64", true) -> "x86_64-linux-android"
            abi.contains("armeabi", true) -> "arm-linux-androideabi"
            abi.contains("x86", true) -> "i686-linux-android"
            else -> "aarch64-linux-android"
        }
        return listOf(
            File(sysrootBase, "usr/lib/$triple/runtime/libxmake_runner.so"),
            File(sysrootBase, "usr/lib/$triple/libxmake_runner.so"),
            File(sysrootBase, "usr/bin/libxmake_runner.so")
        )
    }

    /**
     * 初始化 Native 层进程桥接
     */
    private fun initNativeProcessBridge(ctx: Context) {
        val nativeLibDir = ctx.applicationInfo.nativeLibraryDir
        val sysrootDir = SysrootInstaller.ensureInstalled(ctx).absolutePath

        try {
            nativeInitProcessBridge(nativeLibDir, sysrootDir)
            Log.i(TAG, "Native process bridge initialized")
        } catch (e: Throwable) {
            Log.w(TAG, "Failed to init native process bridge: ${e.message}")
        }
    }
    
    /**
     * 初始化 Native 层进程桥接（JNI）
     */
    private external fun nativeInitProcessBridge(nativeLibDir: String, sysrootDir: String): Boolean
    
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
    
    /**
     * 获取 xmake 版本
     */
    fun version(): Int {
        loadIfNeeded()
        return xmake_run(2, arrayOf("xmake", "--version"))
    }
}
