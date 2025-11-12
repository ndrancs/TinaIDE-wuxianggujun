package com.wuxianggujun.tinaide.core.nativebridge

import android.util.Log
import com.wuxianggujun.tinaide.TinaApplication

object NativeLoader {
    @Volatile
    private var loaded = false

    private fun preloadLibcxxOnce() {
        // 优先使用 APK/jniLibs 自带的 libc++_shared（进入 app 默认命名空间，避免产生重复副本）。
        try {
            System.loadLibrary("c++_shared")
            Log.i("NativeLoader", "Preloaded libc++_shared from jniLibs")
            return
        } catch (_: Throwable) {
            // fallback to sysroot absolute path
        }
        try {
            val ctx = TinaApplication.instance
            val base = java.io.File(ctx.filesDir, "sysroot")
            val abi = android.os.Build.SUPPORTED_ABIS.firstOrNull() ?: ""
            val triple = when {
                abi.contains("arm64", true) -> "aarch64-linux-android"
                abi.contains("x86_64", true) -> "x86_64-linux-android"
                else -> "aarch64-linux-android"
            }
            val candidates = listOf(
                java.io.File(base, "usr/lib/$triple/libc++_shared.so"),
                java.io.File(base, "usr/lib/$triple/26/libc++_shared.so")
            )
            for (f in candidates) {
                if (f.exists()) {
                    try {
                        System.load(f.absolutePath)
                        Log.i("NativeLoader", "Preloaded libc++_shared from ${f.absolutePath}")
                        return
                    } catch (_: Throwable) {
                        // try next candidate
                    }
                }
            }
        } catch (_: Throwable) {
            // ignore
        }
        // 若两者皆不可用，由上层安装流程提示缺失
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
}
