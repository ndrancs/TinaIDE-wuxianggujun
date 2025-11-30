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

    @Volatile

    private var nativeLogListener: ((String, Boolean) -> Unit)? = null

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

            // 注意：libc++_shared.so 应该已经在 Application.onCreate() 中加载

            // 如果没有，libxmake_runner.so 会加载失败

            val sysrootCandidates = resolveSysrootLibraryCandidates(ctx)

            var lastError: Throwable? = null

            for (candidate in sysrootCandidates) {

                if (!candidate.exists()) continue

                try {

                    System.load(candidate.absolutePath)

                    Log.i(TAG, "Loaded xmake_runner from sysroot: ${candidate.absolutePath}")

                    // 标记 NativeEnv 的 native 库已加载

                    NativeEnv.markNativeLoaded()

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

        val triple = runCatching { NativeEnv.getTargetTriple() }.getOrElse {

            val fallbackAbi = AbiResolver.prioritizedAbis(ctx.applicationInfo.nativeLibraryDir).firstOrNull()

            AbiResolver.abiToTargetTriple(fallbackAbi ?: "arm64-v8a")

        }

        return listOf(

            File(sysrootBase, "usr/lib/$triple/runtime/libxmake_runner.so"),

            File(sysrootBase, "usr/lib/$triple/libxmake_runner.so"),

            File(sysrootBase, "usr/bin/libxmake_runner.so")

        )

    }

    fun setNativeLogListener(listener: ((String, Boolean) -> Unit)?) {

        nativeLogListener = listener

    }

    @JvmStatic

    fun handleNativeOutput(message: String, isError: Boolean) {

        val listener = nativeLogListener

        if (listener != null) {

            listener.invoke(message, isError)

        } else {

            val prefix = if (isError) "[xmake:stderr]" else "[xmake]"

            if (isError) {

                Log.e(TAG, "$prefix $message")

            } else {

                Log.i(TAG, "$prefix $message")

            }

        }

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

            // 设置 xmake 所需的所有环境变量

            setupXmakeEnvironment(ctx, sysrootDir)

        } catch (e: Throwable) {

            Log.w(TAG, "Failed to init native process bridge: ${e.message}")

        }

    }

    /**

     * 设置 xmake 运行所需的所有环境变量

     */

    private fun setupXmakeEnvironment(ctx: Context, sysrootDir: String) {

        val filesDir = ctx.filesDir.absolutePath

        val cacheDir = ctx.cacheDir.absolutePath

        // === 临时目录 ===

        // Android 上 /tmp 不可用，使用应用缓存目录

        NativeEnv.setEnv("TMPDIR", cacheDir)

        NativeEnv.setEnv("TEMP", cacheDir)

        NativeEnv.setEnv("TMP", cacheDir)

        Log.i(TAG, "  TMPDIR: $cacheDir")

        // === 用户目录 ===

        // xmake 需要 HOME 目录来存储全局配置

        NativeEnv.setEnv("HOME", filesDir)

        NativeEnv.setEnv("USER", "tina")

        NativeEnv.setEnv("LOGNAME", "tina")

        Log.i(TAG, "  HOME: $filesDir")

        // === xmake 特定环境变量 ===

        // XMAKE_GLOBALDIR: 全局配置目录（默认 ~/.xmake）

        val xmakeGlobalDir = "$filesDir/.xmake"

        NativeEnv.setEnv("XMAKE_GLOBALDIR", xmakeGlobalDir)

        Log.i(TAG, "  XMAKE_GLOBALDIR: $xmakeGlobalDir")

        // XMAKE_CONFIGDIR: 项目配置目录（解决 /.xmake/linux/arm64/project.lock 问题）

        val xmakeConfigDir = "$cacheDir/xmake/config"

        NativeEnv.setEnv("XMAKE_CONFIGDIR", xmakeConfigDir)

        Log.i(TAG, "  XMAKE_CONFIGDIR: $xmakeConfigDir")

        // XMAKE_TMPDIR: xmake 临时目录（覆盖 TMPDIR）

        val xmakeTmpDir = "$cacheDir/xmake/tmp"

        NativeEnv.setEnv("XMAKE_TMPDIR", xmakeTmpDir)

        Log.i(TAG, "  XMAKE_TMPDIR: $xmakeTmpDir")

        // XMAKE_PKG_CACHEDIR: 包缓存目录

        val pkgCacheDir = "$cacheDir/xmake/packages"

        NativeEnv.setEnv("XMAKE_PKG_CACHEDIR", pkgCacheDir)

        Log.i(TAG, "  XMAKE_PKG_CACHEDIR: $pkgCacheDir")

        // XMAKE_PKG_INSTALLDIR: 包安装目录

        val pkgInstallDir = "$filesDir/.xmake/packages"

        NativeEnv.setEnv("XMAKE_PKG_INSTALLDIR", pkgInstallDir)

        Log.i(TAG, "  XMAKE_PKG_INSTALLDIR: $pkgInstallDir")

        // XMAKE_ROOT: 允许以 root 身份运行（Android 上需要）

        NativeEnv.setEnv("XMAKE_ROOT", "y")
        NativeEnv.setEnv("TINA_BRIDGE_ONLY", "y")
        // Force xmake to skip real tool detection and rely on ProcessBridge.

        // === 工具链占位 ===
        // 平台 android 默认优先 toolchain("envs")，需要显式注入 CC/CXX 等占位命令。
        val virtualToolchainEnv = linkedMapOf(
            "CC" to "clang",
            "CXX" to "clang++",
            "CPP" to "clang",
            "LD" to "clang",
            "SH" to "clang",
            "AR" to "llvm-ar",
            "AS" to "clang",
            "STRIP" to "llvm-strip"
        )
        virtualToolchainEnv.forEach { (key, value) ->
            NativeEnv.setEnv(key, value)
        }
        Log.i(
            TAG,
            "  Toolchain env: " + virtualToolchainEnv.entries.joinToString { "${it.key}=${it.value}" }
        )

        // XMAKE_STATS: 禁用统计功能（避免网络请求和子进程调用）

        NativeEnv.setEnv("XMAKE_STATS", "false")

        // === 禁用颜色和交互式输出 ===

        // 使用 plain 主题避免 ANSI 转义码

        NativeEnv.setEnv("XMAKE_THEME", "plain")

        NativeEnv.setEnv("XMAKE_COLORTERM", "nocolor")

        NativeEnv.setEnv("COLORTERM", "")

        NativeEnv.setEnv("NO_COLOR", "1")

        // === Shell 相关 ===

        NativeEnv.setEnv("SHELL", "/system/bin/sh")

        NativeEnv.setEnv("TERM", "dumb")

        // === 语言环境 ===

        NativeEnv.setEnv("LANG", "C")

        NativeEnv.setEnv("LC_ALL", "C")

        // === 禁用可能导致子进程调用的功能 ===

        // 禁用更新检查

        NativeEnv.setEnv("XMAKE_UPDATE_CHECK", "false")

        // 确保必要的目录存在

        try {

            java.io.File(xmakeGlobalDir).mkdirs()

            java.io.File(xmakeConfigDir).mkdirs()

            java.io.File(xmakeTmpDir).mkdirs()

            java.io.File(pkgCacheDir).mkdirs()

            java.io.File(pkgInstallDir).mkdirs()

        } catch (e: Exception) {

            Log.w(TAG, "Failed to create xmake directories: ${e.message}")

        }

        Log.i(TAG, "Xmake environment setup complete")

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

        val args = mutableListOf("xmake", "f", "-P", projectDir)

        args.addAll(options)

        return xmake_run(args.size, args.toTypedArray())

    }

    /**

     * 获取 xmake 版本

     */

    fun version(): Int {

        loadIfNeeded()

        return xmake_run(2, arrayOf("xmake", "--version"))

    }

}
