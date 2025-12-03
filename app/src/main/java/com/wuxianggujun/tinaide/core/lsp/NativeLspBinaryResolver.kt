package com.wuxianggujun.tinaide.core.lsp

import android.content.Context
import android.util.Log
import com.wuxianggujun.tinaide.core.nativebridge.AbiResolver
import com.wuxianggujun.tinaide.core.nativebridge.SysrootInstaller
import java.io.File

/**
 * 负责推导 libclangd.so 的真实落地点，避免手动配置。
 */
object NativeLspBinaryResolver {

    private const val TAG = "NativeLspBinResolver"

    /**
     * 尝试根据 sysroot 和 ABI 映射推导 libclangd.so 的绝对路径。
     * @return 文件存在时返回路径，否则返回 null。
     */
    fun resolveClangdBinary(context: Context): String? {
        return runCatching {
            val appContext = context.applicationContext
            val sysrootDir = SysrootInstaller.ensureInstalled(appContext)
            val nativeLibDir = appContext.applicationInfo?.nativeLibraryDir
            val abiCandidates = AbiResolver.prioritizedAbis(nativeLibDir)
            for (abi in abiCandidates) {
                val triple = AbiResolver.abiToTargetTriple(abi)
                val candidate = File(sysrootDir, "usr/lib/$triple/runtime/libclangd.so")
                if (candidate.exists()) {
                    Log.i(TAG, "Resolved clangd binary for ABI=$abi at ${candidate.absolutePath}")
                    return candidate.absolutePath
                }
            }
            val fallback = File(sysrootDir, "usr/lib/libclangd.so")
            if (fallback.exists()) {
                Log.i(TAG, "Using fallback clangd binary at ${fallback.absolutePath}")
                return fallback.absolutePath
            }
            null
        }.onFailure {
            Log.w(TAG, "Failed to resolve clangd binary: ${it.message}")
        }.getOrNull()
    }
}
