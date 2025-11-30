package com.wuxianggujun.tinaide.core.nativebridge

import android.content.Context
import android.os.Build
import android.util.Log
import com.wuxianggujun.tinaide.BuildConfig
import com.wuxianggujun.tinaide.core.config.Prefs
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.util.LinkedHashSet
import java.util.Locale
import java.util.zip.ZipInputStream

object SysrootInstaller {
    private const val TAG = "SysrootInstaller"
    private val BRIDGE_PLACEHOLDER_TOOLS = arrayOf("clang", "clang++", "llvm-ar")
    private const val OVERRIDES_ASSET_ROOT = "xmake-overrides"
    private const val OVERRIDE_MARK_FILE = ".tina_overrides"

    /**
     * Ensure <files>/sysroot is present by copying from assets/sysroot on first run.
     * Idempotent and minimal (no versioning yet – YAGNI).
     */
    fun ensureInstalled(context: Context): File {
        val dst = File(context.filesDir, "sysroot")
        val cSentinel = File(dst, "usr/include/stdio.h")
        val cppSentinel = File(dst, "usr/include/c++/v1/__ios/fpos.h")
        val clangResSentinel = File(dst, "lib/clang/17/include/stdarg.h")
        val needInstall = !(cSentinel.exists() && cppSentinel.exists() && clangResSentinel.exists())
        return if (needInstall) {
            forceReinstall(context)
        } else {
            ensureBridgeToolPlaceholders(dst)
            applyXmakeOverrides(context, dst, Prefs.forceSysrootOverrides)
            dst
        }
    }

    /**
     * 强制重新安装 sysroot（删除旧的并重新解压）
     * 用于更新 APK 后刷新 sysroot 内容
     */
    fun forceReinstall(context: Context): File {
        val dst = File(context.filesDir, "sysroot")
        try {
            dst.deleteRecursively()
        } catch (_: Throwable) {
        }
        val archive = try {
            openSysrootArchive(context)
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to find sysroot archive in assets: ${t.message}")
            throw IllegalStateException("sysroot archive missing or invalid", t)
        }
        Log.i(
            TAG,
            "Installing sysroot using assets/${archive.assetName} (abis=${Build.SUPPORTED_ABIS?.joinToString()})"
        )
        try {
            archive.stream.use { input ->
                extractZip(input, dst)
            }
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to unpack assets/${archive.assetName}: ${t.message}")
            throw IllegalStateException("sysroot archive corrupted (${archive.assetName})", t)
        }
        try {
            fixExecPermissions(dst)
        } catch (_: Throwable) {
        }
        ensureBridgeToolPlaceholders(dst)
        applyXmakeOverrides(context, dst, true)
        Log.i(TAG, "sysroot installed/refreshed at ${dst.absolutePath}")
        return dst
    }

    private fun fixExecPermissions(root: File) {
        val binDir = File(root, "usr/bin")
        if (binDir.isDirectory) {
            binDir.listFiles()?.forEach { f ->
                if (f.isFile) {
                    try {
                        f.setExecutable(true, false)
                    } catch (_: Throwable) {
                    }
                }
            }
        }
    }

    /**
     * Create lightweight placeholder executables (clang/clang++/llvm-ar) so xmake tool detection
     * succeeds even though actual compilation is bridged到 NativeCompiler.
     */
    private fun ensureBridgeToolPlaceholders(root: File) {
        val binDir = File(root, "usr/bin")
        if (!binDir.exists()) {
            try {
                binDir.mkdirs()
            } catch (_: Throwable) {
            }
        }
        if (!binDir.exists()) return
        BRIDGE_PLACEHOLDER_TOOLS.forEach { name ->
            val tool = File(binDir, name)
            if (!tool.exists()) {
                try {
                    tool.writeText("#!/system/bin/sh\nexit 0\n")
                    tool.setExecutable(true, false)
                } catch (t: Throwable) {
                    Log.w(TAG, "Failed to create placeholder tool $name", t)
                }
            }
        }
    }

    private fun applyXmakeOverrides(context: Context, root: File, force: Boolean) {
        try {
            val assets = context.assets
            val hasOverrides = assets.list(OVERRIDES_ASSET_ROOT)?.isNotEmpty() == true
            if (!hasOverrides) {
                Log.d(TAG, "xmake overrides skipped: no assets under $OVERRIDES_ASSET_ROOT")
                return
            }
            if (!force && !overridesNeedRefresh(root)) {
                Log.d(TAG, "xmake overrides already up-to-date (marker=${File(root, OVERRIDE_MARK_FILE)})")
                return
            }
            copyAssetTree(context, OVERRIDES_ASSET_ROOT, root)
            writeOverrideMarker(root)
            Log.i(TAG, "xmake overrides applied (force=$force)")
        } catch (t: Throwable) {
            Log.w(TAG, "Failed to apply xmake overrides: ${t.message}")
        }
    }

    private fun copyAssetTree(context: Context, assetPath: String, dest: File) {
        val assets = context.assets
        val entries = assets.list(assetPath)
        if (entries == null || entries.isEmpty()) {
            if (assetPath == OVERRIDES_ASSET_ROOT) return
            dest.parentFile?.mkdirs()
            assets.open(assetPath).use { input ->
                FileOutputStream(dest).use { out -> input.copyTo(out) }
            }
            return
        }
        if (assetPath != OVERRIDES_ASSET_ROOT && !dest.exists()) {
            dest.mkdirs()
        }
        entries.forEach { child ->
            val childAssetPath = "$assetPath/$child"
            val childDest = File(dest, child)
            copyAssetTree(context, childAssetPath, childDest)
        }
    }

    private fun overridesNeedRefresh(root: File): Boolean {
        val marker = File(root, OVERRIDE_MARK_FILE)
        return try {
            if (!marker.exists()) {
                true
            } else {
                marker.readText().trim() != BuildConfig.VERSION_CODE.toString()
            }
        } catch (_: Throwable) {
            true
        }
    }

    private fun writeOverrideMarker(root: File) {
        try {
            val marker = File(root, OVERRIDE_MARK_FILE)
            marker.parentFile?.mkdirs()
            marker.writeText(BuildConfig.VERSION_CODE.toString())
        } catch (t: Throwable) {
            Log.w(TAG, "Failed to write override marker: ${t.message}")
        }
    }

    private fun extractZip(zipStream: InputStream, dstDir: File) {
        ZipInputStream(zipStream).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                val name = sanitizeZipEntry(entry.name)
                if (name.isNotEmpty()) {
                    val outPath = File(dstDir, name)
                    if (entry.isDirectory) {
                        outPath.mkdirs()
                    } else {
                        outPath.parentFile?.mkdirs()
                        FileOutputStream(outPath).use { out ->
                            val buf = ByteArray(DEFAULT_BUFFER_SIZE)
                            while (true) {
                                val read = zis.read(buf)
                                if (read <= 0) break
                                out.write(buf, 0, read)
                            }
                        }
                    }
                }
                zis.closeEntry()
                entry = zis.nextEntry
            }
        }
    }

    private fun sanitizeZipEntry(name: String): String {
        if (name.isEmpty()) return ""
        var n = name.replace('\\', '/')
        if (n.startsWith("/")) n = n.substring(1)
        if (n.contains("..")) return ""
        return n
    }

    private fun openSysrootArchive(context: Context): ArchiveHandle {
        val candidates = LinkedHashSet<String>()
        AbiResolver.prioritizedAbis(context.applicationInfo.nativeLibraryDir).forEach { abi ->
            val normalized = abi.trim()
            if (normalized.isNotEmpty()) {
                candidates += "sysroot-$normalized.zip"
                val lower = normalized.lowercase(Locale.US)
                if (lower != normalized) {
                    candidates += "sysroot-$lower.zip"
                }
            }
        }
        candidates += "sysroot.zip"

        var lastError: IOException? = null
        val assets = context.assets
        for (assetName in candidates) {
            try {
                val stream = assets.open(assetName)
                return ArchiveHandle(assetName, stream)
            } catch (ioe: IOException) {
                lastError = ioe
            }
        }
        throw IllegalStateException(
            "No sysroot archive found (looked for ${candidates.joinToString()})",
            lastError
        )
    }

    private data class ArchiveHandle(val assetName: String, val stream: InputStream)
}
