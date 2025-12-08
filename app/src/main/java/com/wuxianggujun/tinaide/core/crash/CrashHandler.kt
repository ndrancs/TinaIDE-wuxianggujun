package com.wuxianggujun.tinaide.core.crash

import com.wuxianggujun.tinaide.TinaApplication
import com.wuxianggujun.tinaide.ui.activity.CrashActivity
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.system.exitProcess

/**
 * 全局崩溃捕获。
 * 捕获未处理异常后跳转到 CrashActivity 展示崩溃信息。
 */
object CrashHandler : Thread.UncaughtExceptionHandler {

    private var fallback: Thread.UncaughtExceptionHandler? = null
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())

    fun install() {
        if (fallback == null) {
            fallback = Thread.getDefaultUncaughtExceptionHandler()
        }
        Thread.setDefaultUncaughtExceptionHandler(this)
    }

    override fun uncaughtException(thread: Thread, ex: Throwable) {
        try {
            val stackTrace = getStackTraceString(ex)
            val crashTime = dateFormat.format(Date())

            // 写入日志文件
            saveCrashLog(stackTrace, crashTime)

            // 跳转到崩溃页面
            val app = TinaApplication.instance
            CrashActivity.start(app, stackTrace, crashTime)

            // 等待 Activity 启动
            Thread.sleep(500)
        } catch (e: Throwable) {
            e.printStackTrace()
        }

        // 结束进程
        android.os.Process.killProcess(android.os.Process.myPid())
        exitProcess(1)
    }

    private fun getStackTraceString(throwable: Throwable): String {
        val sw = StringWriter()
        throwable.printStackTrace(PrintWriter(sw))
        return sw.toString()
    }

    private fun saveCrashLog(stackTrace: String, crashTime: String) {
        runCatching {
            val app = TinaApplication.instance
            val crashFile = File(app.filesDir, "crash.log").apply {
                parentFile?.mkdirs()
                if (!exists()) createNewFile()
            }

            val content = buildString {
                appendLine("===== Crash at $crashTime =====")
                appendLine(stackTrace)
                appendLine()
            }
            crashFile.appendText(content)
        }
    }
}
