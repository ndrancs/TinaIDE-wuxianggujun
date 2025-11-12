package com.wuxianggujun.tinaide.core.nativebridge

import android.util.Log
import com.wuxianggujun.tinaide.TinaApplication

object NativeLoader {
    @Volatile
    private var loaded = false

    private fun preloadLibcxxOnce() {
        // 仅允许使用 APK/jniLibs 的 libc++_shared，彻底关闭 sysroot 回退，确保进程内始终只有一份 C++ 运行时。
        try {
            System.loadLibrary("c++_shared")
            Log.i("NativeLoader", "Preloaded libc++_shared from jniLibs")
        } catch (t: Throwable) {
            val msg = "Missing libc++_shared in APK/jniLibs (required). Please ensure it is packaged. (${t.message})"
            Log.e("NativeLoader", msg)
            throw UnsatisfiedLinkError(msg)
        }
    }

    fun loadIfNeeded() {
        if (loaded) return
        // 先预加载 libc++_shared（优先 jniLibs，失败再回退 sysroot），确保全局唯一运行时
        preloadLibcxxOnce()
        // 加载 LLVM 主库（仅 sysroot runtime），再加载 clang-cpp
        var llvmLoaded = false
        try {
            val ctx = TinaApplication.instance
            val base = java.io.File(ctx.filesDir, "sysroot")
            val abi = android.os.Build.SUPPORTED_ABIS.firstOrNull() ?: ""
            val triple = when {
                abi.contains("arm64", true) -> "aarch64-linux-android"
                abi.contains("x86_64", true) -> "x86_64-linux-android"
                else -> "aarch64-linux-android"
            }
            val llvmPath = java.io.File(base, "usr/lib/$triple/runtime/libLLVM-17.so")
            if (llvmPath.exists()) { System.load(llvmPath.absolutePath); llvmLoaded = true }
        } catch (_: Throwable) { }
        try {
            val ctx = TinaApplication.instance
            val base = java.io.File(ctx.filesDir, "sysroot")
            val abi = android.os.Build.SUPPORTED_ABIS.firstOrNull() ?: ""
            val triple = when {
                abi.contains("arm64", true) -> "aarch64-linux-android"
                abi.contains("x86_64", true) -> "x86_64-linux-android"
                else -> "aarch64-linux-android"
            }
            val clangPath = java.io.File(base, "usr/lib/$triple/runtime/libclang-cpp.so")
            if (clangPath.exists()) {
                System.load(clangPath.absolutePath)
                Log.i("NativeLoader", "Loaded clang-cpp from sysroot runtime")
            } else {
                throw UnsatisfiedLinkError("clang-cpp not found in sysroot runtime: ${clangPath.absolutePath}")
            }
        } catch (t: Throwable) {
            Log.w("NativeLoader", "Failed to load clang-cpp: ${t.message}")
        }
        try {
            System.loadLibrary("native_compiler")
            loaded = true
            Log.i("NativeLoader", "Loaded native_compiler successfully")
        } catch (t: Throwable) {
            Log.w("NativeLoader", "Failed to load native_compiler: ${t.message}")
        }
    }

    fun isLoaded(): Boolean = loaded

    // ---- Helpers: preload arbitrary libs from sysroot by name (without hardcoding absolute paths) ----
    private fun abiToTriple(): String {
        val abi = android.os.Build.SUPPORTED_ABIS.firstOrNull() ?: ""
        return when {
            abi.contains("arm64", true) -> "aarch64-linux-android"
            abi.contains("x86_64", true) -> "x86_64-linux-android"
            else -> "aarch64-linux-android"
        }
    }

    private fun sysrootBase(): java.io.File = java.io.File(TinaApplication.instance.filesDir, "sysroot")

    /**
     * 从 sysroot 以“库名”方式批量预加载（构造常见候选路径并加载第一命中者）。
     * - 默认不允许加载 libc++_shared.so（避免产生第二份 C++ 运行时）；必要时可显式 allowLibcxx=true。
     * - preferRuntime=true 时优先从 usr/lib/<triple>/runtime 目录查找，其次 <api> 目录，再次 triple 根目录。
     * - names 传入完整库名（例："libLLVM-17.so"、"libclang-cpp.so"、"libfoo.so"）。
     */
    @JvmOverloads
    fun preloadFromSysrootByName(
        names: List<String>,
        preferRuntime: Boolean = true,
        allowLibcxx: Boolean = false,
        apiLevel: String? = null
    ) {
        val triple = abiToTriple()
        val base = sysrootBase()
        val api = apiLevel ?: "26" // 与打包默认一致，必要时可由调用者指定
        for (name in names) {
            if (!allowLibcxx && name == "libc++_shared.so") {
                Log.w("NativeLoader", "Skip loading libc++_shared from sysroot to keep single C++ runtime")
                continue
            }
            val candidates = buildList {
                if (preferRuntime) add(java.io.File(base, "usr/lib/$triple/runtime/$name"))
                add(java.io.File(base, "usr/lib/$triple/$api/$name"))
                add(java.io.File(base, "usr/lib/$triple/$name"))
                if (!preferRuntime) add(java.io.File(base, "usr/lib/$triple/runtime/$name"))
            }
            var loadedOne = false
            for (f in candidates) {
                if (f.exists()) {
                    try {
                        System.load(f.absolutePath)
                        Log.i("NativeLoader", "Preloaded from sysroot: ${f.absolutePath}")
                        loadedOne = true
                        break
                    } catch (t: Throwable) {
                        Log.w("NativeLoader", "Failed to load ${f.absolutePath}: ${t.message}")
                    }
                }
            }
            if (!loadedOne) {
                Log.w("NativeLoader", "No match in sysroot for $name (triple=$triple, api=$api)")
            }
        }
    }

    /** 便捷可变参数封装 */
    @JvmOverloads
    fun preloadFromSysrootVararg(
        vararg names: String,
        preferRuntime: Boolean = true,
        allowLibcxx: Boolean = false,
        apiLevel: String? = null
    ) = preloadFromSysrootByName(names.toList(), preferRuntime, allowLibcxx, apiLevel)
}
