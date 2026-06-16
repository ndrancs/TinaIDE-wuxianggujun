package com.wuxianggujun.tinaide.core.proot

import android.content.Context
import com.wuxianggujun.tinaide.storage.ProjectPaths
import java.io.BufferedWriter
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStreamWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import timber.log.Timber

internal class PRootSessionLogger private constructor(
    val sessionFile: File,
    val errorFile: File,
    private val writer: BufferedWriter,
) : AutoCloseable {

    companion object {
        private const val TAG = "PRootSessionLogger"

        private const val MAX_RETAIN_DAYS = 7
        private const val MAX_SESSION_LOG_FILES = 200

        private val sessionFileTimestampFormat = SimpleDateFormat("yyyy-MM-dd_HHmmss", Locale.US)
        private val errorFileDateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.US)

        fun create(
            context: Context,
            prefix: String = "session",
        ): PRootSessionLogger? = try {
            val logDir = ProjectPaths.ensureDir(ProjectPaths.getPRootLogsRoot(context))
            cleanupOldLogs(logDir)

            val now = Date()
            val sessionName = "${prefix}_${sessionFileTimestampFormat.format(now)}.log"
            val errorName = "error_${errorFileDateFormat.format(now)}.log"

            val sessionFile = File(logDir, sessionName)
            val errorFile = File(logDir, errorName)

            val writer = BufferedWriter(
                OutputStreamWriter(
                    FileOutputStream(sessionFile, true),
                    Charsets.UTF_8
                )
            )

            PRootSessionLogger(sessionFile, errorFile, writer)
        } catch (e: Exception) {
            Timber.tag(TAG).w(e, "Failed to create session log")
            null
        }

        private fun cleanupOldLogs(logDir: File) {
            try {
                val cutoff = System.currentTimeMillis() - MAX_RETAIN_DAYS * 24 * 60 * 60 * 1000L
                val files = logDir.listFiles { file ->
                    file.isFile && file.name.endsWith(".log")
                } ?: return

                // 1) 删除过期日志
                files.filter { it.lastModified() < cutoff }.forEach { it.delete() }

                // 2) 限制 session_* 数量（error_* 不计入）
                val sessionFiles = files
                    .filter { it.isFile && it.name.startsWith("session_") && it.name.endsWith(".log") }
                    .sortedByDescending { it.lastModified() }

                if (sessionFiles.size > MAX_SESSION_LOG_FILES) {
                    sessionFiles.drop(MAX_SESSION_LOG_FILES).forEach { it.delete() }
                }
            } catch (e: Exception) {
                Timber.tag(TAG).w(e, "Failed to cleanup proot logs")
            }
        }
    }

    private val lock = Any()
    private val timestampFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)

    fun logDir(): File = sessionFile.parentFile ?: File(".")

    fun writeInfo(message: String) {
        write("INFO", message)
    }

    fun writeCommand(command: String) {
        write("CMD", command)
    }

    fun writeStdout(line: String) {
        write("STDOUT", line)
    }

    fun writeStderr(line: String) {
        write("STDERR", line, alsoToErrorFile = true)
    }

    fun writeExit(exitCode: Int, timedOut: Boolean, durationMs: Long) {
        write("EXIT", "code=$exitCode timedOut=$timedOut durationMs=$durationMs")
        if (timedOut || exitCode != 0) {
            write("ERROR", "process ended abnormally", alsoToErrorFile = true)
        }
    }

    private fun write(tag: String, message: String, alsoToErrorFile: Boolean = false) {
        val safeMessage = message.replace("\r", "\\r").replace("\n", "\\n")
        val ts = timestampFormat.format(Date())
        val line = "[$ts] [$tag] $safeMessage"

        synchronized(lock) {
            try {
                writer.appendLine(line)
            } catch (e: Exception) {
                Timber.tag(TAG).d(e, "Failed to write session log line")
            }
        }

        if (!alsoToErrorFile) return
        runCatching {
            errorFile.appendText(line + "\n", Charsets.UTF_8)
        }
    }

    override fun close() {
        synchronized(lock) {
            runCatching { writer.flush() }
            runCatching { writer.close() }
        }
    }
}
