package com.wuxianggujun.tinaide.core.crash

import android.os.Looper
import com.wuxianggujun.tinaide.TinaApplication
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import kotlin.system.exitProcess

/**
 * 全局崩溃捕获与日志记录。
 * 参考用户提供实现并结合当前项目结构做了轻量适配：
 * - 使用 TinaApplication.instance 获取 filesDir
 * - 将日志写入 filesDir/crash.log，无需额外权限
 */
object CrashHandler : Thread.UncaughtExceptionHandler {

    private var fallback: Thread.UncaughtExceptionHandler? = null

    fun install() {
        // 保存原始 Handler 并注册自己
        if (fallback == null) fallback = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler(this)
    }

    override fun uncaughtException(thread: Thread, ex: Throwable) {
        // 只做日志落盘，不再在主线程内循环 Looper，避免卡死
        runCatching { logErrorOrExit(ex) }.onFailure { it.printStackTrace() }
        // 交还系统默认处理，或直接退出
        try {
            (fallback ?: Thread.getDefaultUncaughtExceptionHandler())
                ?.uncaughtException(thread, ex)
                ?: exitProcess(1)
        } catch (_: Throwable) {
            exitProcess(1)
        }
    }
}

private fun logErrorOrExit(throwable: Throwable) {
    runCatching {
        val app = TinaApplication.instance
        val crashFile = File(app.filesDir, "crash.log").ensureFile()

        val sw = StringWriter()
        throwable.printStackTrace(PrintWriter(sw))

        val content = buildString {
            appendLine("===== Crash =====")
            appendLine("threadTimeMillis=${System.currentTimeMillis()}")
            appendLine(sw.toString())
            appendLine()
        }
        crashFile.appendText(content)
    }.onFailure { it.printStackTrace(); exitProcess(-1) }
}

// 仅在此处使用，保持最小化实现（KISS/YAGNI）
private fun File.ensureFile(): File {
    parentFile?.mkdirs()
    if (!exists()) createNewFile()
    return this
}

