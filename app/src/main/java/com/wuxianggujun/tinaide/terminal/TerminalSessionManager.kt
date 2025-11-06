package com.wuxianggujun.tinaide.terminal

import android.content.Context
import com.termux.terminal.TerminalEmulator
import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionClient
import java.io.File

/**
 * 终端会话管理器 - 对齐 ReTerminal：写入 init 脚本、保障 /proc 伪文件，并注入 proot 环境变量。
 */
object TerminalSessionManager {

    const val WORKING_MODE_SHELL = 0
    const val WORKING_MODE_ALPINE = 1

    fun createSession(
        context: Context,
        client: TerminalSessionClient,
        sessionId: String,
        workingMode: Int = WORKING_MODE_ALPINE,
        shellOverride: String? = null,
        argsOverride: Array<String>? = null,
        workingDir: File? = null,
        debug: Boolean = false
    ): TerminalSession {
        val appFiles = context.filesDir
        val prefixDir = appFiles.parentFile!!
        // 规范化到真实路径，避免 /data/user/0 与 /data/data 不一致导致 proot 寻址失败
        val prefixCanonical = File(prefixDir.canonicalPath)
        val localDir = File(prefixCanonical, "local").apply { if (!exists()) mkdirs() }
        val localBin = File(localDir, "bin").apply { if (!exists()) mkdirs() }
        val localLib = File(localDir, "lib").apply { if (!exists()) mkdirs() }
        val alpineDir = File(localDir, "alpine").apply { if (!exists()) mkdirs() }

        val wantsAlpine = (workingMode == WORKING_MODE_ALPINE)
        val hasRuntime = if (wantsAlpine) isAlpineRuntimeReady(context) else false

        // 落盘脚本（仅在 alpine 模式可用时）
        if (wantsAlpine && hasRuntime) {
            writeAssetIfMissing(context, "init-host.sh", File(localBin, "init-host"))
            writeAssetIfMissing(context, "init.sh", File(localBin, "init"))
            File(localBin, "init").setExecutable(true, false)
            File(localBin, "init-host").setExecutable(true, false)
        }

        // 对齐 ReTerminal：写入 /proc 伪文件（可选覆盖）
        val statFile = File(localDir, "stat").apply {
            if (!exists()) writeText(DEFAULT_STAT)
        }
        val vmstatFile = File(localDir, "vmstat").apply {
            if (!exists()) writeText(DEFAULT_VMSTAT)
        }

        // 将临时目录放在 files/ 下，规避部分 ROM 对 /data/data/<pkg>/tmp 的 SELinux 限制
        val tmpRoot = File(prefixCanonical, "files/tmp").apply { if (!exists()) mkdirs() }
        val prootTmp = File(tmpRoot, sessionId).apply { if (!exists()) mkdirs() }

        val nativeLibDirPath = context.applicationInfo.nativeLibraryDir
        val env = mutableListOf(
            "PATH=${System.getenv("PATH")}:/sbin:${localBin.absolutePath}",
            "HOME=/sdcard",
            "PUBLIC_HOME=${context.getExternalFilesDir(null)?.absolutePath}",
            "COLORTERM=truecolor",
            "TERM=xterm-256color",
            "LANG=C.UTF-8",
            "BIN=${localBin.absolutePath}",
            "DEBUG=$debug",
            "PREFIX=${prefixCanonical.path}",
            "LD_LIBRARY_PATH=${localLib.absolutePath}:$nativeLibDirPath",
            "LINKER=${pickLinker()}",
            "NATIVE_LIB_DIR=$nativeLibDirPath",
            "PKG=${context.packageName}",
            "PROOT_TMP_DIR=${prootTmp.absolutePath}",
            "TMPDIR=${tmpRoot.absolutePath}",
            "PROOT_NO_SECCOMP=1",
            "PROOT_DEBUG=3"
        )

        val nativeLibDir = File(context.applicationInfo.nativeLibraryDir)
        if (File(nativeLibDir, "libproot-loader32.so").exists())
            env += "PROOT_LOADER32=${nativeLibDir}/libproot-loader32.so"
        if (File(nativeLibDir, "libproot-loader.so").exists())
            env += "PROOT_LOADER=${nativeLibDir}/libproot-loader.so"

        val shell: String
        val args: Array<String>
        if (shellOverride != null || argsOverride != null) {
            shell = shellOverride ?: "/system/bin/sh"
            args = argsOverride ?: emptyArray()
        } else if (wantsAlpine && hasRuntime) {
            shell = "/system/bin/sh"
            args = arrayOf("-c", File(localBin, "init-host").absolutePath)
        } else {
            shell = "/system/bin/sh"
            args = emptyArray()
        }

        val cwd = (workingDir ?: if (wantsAlpine && hasRuntime) alpineDir else File("/")).absolutePath

        return TerminalSession(
            shell,
            cwd,
            args,
            env.toTypedArray(),
            TerminalEmulator.DEFAULT_TERMINAL_TRANSCRIPT_ROWS,
            client
        )
    }

    fun isAlpineRuntimeReady(context: Context): Boolean {
        val prefixDir = context.filesDir.parentFile!!
        val filesDir = File(prefixDir, "files")
        val localBinProot = File(prefixDir, "local/bin/proot")
        val filesProot = File(filesDir, "proot")
        val alpineDir = File(prefixDir, "local/alpine")
        val alpineBin = File(alpineDir, "bin")
        val alpineTar = File(filesDir, "alpine.tar.gz")
        val prootOk = localBinProot.exists() || filesProot.exists()
        val rootfsOk = alpineBin.exists() || alpineTar.exists()
        return prootOk && rootfsOk
    }

    private fun pickLinker(): String {
        val a64 = File("/system/bin/linker64")
        return if (a64.exists()) "/system/bin/linker64" else "/system/bin/linker"
    }

    private fun writeAssetIfMissing(context: Context, assetName: String, target: File) {
        if (target.exists()) return
        target.parentFile?.mkdirs()
        context.assets.open(assetName).use { input ->
            target.outputStream().use { out ->
                input.copyTo(out)
            }
        }
        target.setReadable(true, false)
        target.setExecutable(true, false)
    }

    // 最小可用的 /proc 占位（避免启动时强制绑定失败）
    private const val DEFAULT_STAT = """
        cpu  1 0 1 0 0 0 0 0 0 0
        intr 0
        ctxt 0
        btime 0
        processes 0
        procs_running 0
        procs_blocked 0
        softirq 0
    """

    private const val DEFAULT_VMSTAT = """
        nr_free_pages 0
        nr_inactive_anon 0
        nr_active_anon 0
        nr_inactive_file 0
        nr_active_file 0
        nr_unevictable 0
        nr_mlock 0
        nr_bounce 0
    """
}
