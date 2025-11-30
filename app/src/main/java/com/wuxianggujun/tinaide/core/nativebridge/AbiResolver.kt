package com.wuxianggujun.tinaide.core.nativebridge

import android.os.Build
import java.io.File
import java.util.LinkedHashSet
import java.util.Locale

/**
 * Utility for resolving ABI-related metadata from runtime paths/device info.
 */
internal object AbiResolver {
    private val fallback = listOf("arm64-v8a", "armeabi-v7a", "x86_64", "x86")

    fun detectFromNativeLibDir(nativeLibDir: String?): String? {
        if (nativeLibDir.isNullOrBlank()) return null
        val leaf = File(nativeLibDir).name.lowercase(Locale.US)
        return when {
            leaf.contains("arm64") -> "arm64-v8a"
            leaf.contains("armeabi") -> "armeabi-v7a"
            leaf.contains("x86_64") -> "x86_64"
            leaf.contains("x86") -> "x86"
            else -> null
        }
    }

    fun prioritizedAbis(nativeLibDir: String?): List<String> {
        val ordered = LinkedHashSet<String>()
        detectFromNativeLibDir(nativeLibDir)?.let { ordered += it }
        Build.SUPPORTED_ABIS?.forEach { abi ->
            if (!abi.isNullOrBlank()) {
                ordered += abi.trim()
            }
        }
        ordered.addAll(fallback)
        return ordered.toList()
    }

    fun abiToTargetTriple(abi: String): String = when {
        abi.contains("arm64", ignoreCase = true) -> "aarch64-linux-android"
        abi.contains("armeabi", ignoreCase = true) || abi.contains("arm", ignoreCase = true) -> "arm-linux-androideabi"
        abi.contains("x86_64", ignoreCase = true) -> "x86_64-linux-android"
        abi.contains("x86", ignoreCase = true) -> "i686-linux-android"
        else -> "aarch64-linux-android"
    }
}
