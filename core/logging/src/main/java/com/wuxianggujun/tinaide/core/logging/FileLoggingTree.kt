package com.wuxianggujun.tinaide.core.logging

import android.annotation.SuppressLint
import android.util.Log
import java.io.BufferedWriter
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStreamWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean
import timber.log.Timber

/**
 * Timber 文件日志 Tree
 *
 * 功能：
 * - 将日志写入到 Android/data/<package>/files/logs/ 目录
 * - 每日一个日志文件（tinaide_2025-01-08.log）
 * - 自动清理过期日志（默认保留 7 天）
 * - 异步写入，不阻塞主线程
 * - 线程安全
 *
 * 日志格式：
 * [2025-01-08 14:30:45.123] [D/MainActivity] 日志消息
 *
 * 使用方式：
 * ```
 * Timber.plant(FileLoggingTree(context))
 * ```
 *
 * 注意：内部错误处理使用 android.util.Log 而非 Timber，避免无限递归
 */
@SuppressLint("LogNotTimber")
class FileLoggingTree(
    private val logDir: File,
    private val minPriority: Int = Log.DEBUG,
    private val maxRetainDays: Int = 7
) : Timber.Tree() {

    companion object {
        private const val TAG = "FileLoggingTree"
        private const val FILE_PREFIX = "tinaide_"
        private const val FILE_SUFFIX = ".log"
        private const val DATE_FORMAT = "yyyy-MM-dd"
        private const val TIMESTAMP_FORMAT = "yyyy-MM-dd HH:mm:ss.SSS"
        private const val FLUSH_INTERVAL_MS = 5000L // 5秒刷新一次
    }

    private val dateFormat = SimpleDateFormat(DATE_FORMAT, Locale.US)
    private val timestampFormat = SimpleDateFormat(TIMESTAMP_FORMAT, Locale.US)

    // 日志缓冲队列（无锁线程安全队列）
    private val logQueue = ConcurrentLinkedQueue<String>()

    // 写入线程控制
    private val isRunning = AtomicBoolean(true)
    private val writerThread: Thread

    // 当前日志文件和写入器
    @Volatile
    private var currentDate: String = ""

    @Volatile
    private var currentWriter: BufferedWriter? = null

    init {
        // 启动后台写入线程
        writerThread = Thread({
            runWriteLoop()
        }, "FileLoggingTree-Writer").apply {
            isDaemon = true
            priority = Thread.MIN_PRIORITY
            start()
        }

        // 启动时清理过期日志
        cleanupOldLogs()
    }

    override fun isLoggable(tag: String?, priority: Int): Boolean = priority >= minPriority

    override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
        val timestamp = timestampFormat.format(Date())
        val levelChar = getLevelChar(priority)
        val effectiveTag = tag ?: "NoTag"

        val logLine = buildString {
            append("[$timestamp] ")
            append("[$levelChar/$effectiveTag] ")
            append(message)
            if (t != null) {
                append("\n")
                append(Log.getStackTraceString(t))
            }
        }

        // 加入队列（无锁）
        logQueue.offer(logLine)
    }

    /**
     * 后台写入循环
     */
    private fun runWriteLoop() {
        while (isRunning.get() || logQueue.isNotEmpty()) {
            try {
                // 批量写入
                var count = 0
                while (count < 100) { // 每次最多处理 100 条
                    val line = logQueue.poll() ?: break
                    writeLogLine(line)
                    count++
                }

                if (count > 0) {
                    // 刷新缓冲区
                    currentWriter?.flush()
                }

                // 等待一段时间
                if (isRunning.get() && logQueue.isEmpty()) {
                    Thread.sleep(FLUSH_INTERVAL_MS)
                }
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                break
            } catch (e: Exception) {
                // 写入失败时静默忽略（避免日志循环）
                Log.e(TAG, "Failed to write log", e)
            }
        }

        // 关闭写入器
        closeWriter()
    }

    /**
     * 写入单行日志
     */
    private fun writeLogLine(line: String) {
        try {
            val today = dateFormat.format(Date())

            // 检查是否需要切换日志文件
            if (today != currentDate) {
                closeWriter()
                currentDate = today
                val logFile = File(logDir, "$FILE_PREFIX$today$FILE_SUFFIX")
                currentWriter = BufferedWriter(
                    OutputStreamWriter(
                        FileOutputStream(logFile, true), // append mode
                        Charsets.UTF_8
                    )
                )
            }

            currentWriter?.apply {
                write(line)
                newLine()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to write log line", e)
        }
    }

    /**
     * 关闭写入器
     */
    private fun closeWriter() {
        try {
            currentWriter?.close()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to close writer", e)
        }
        currentWriter = null
    }

    /**
     * 清理过期日志
     */
    private fun cleanupOldLogs() {
        Thread {
            try {
                val cutoffDate = System.currentTimeMillis() - (maxRetainDays * 24 * 60 * 60 * 1000L)
                val files = logDir.listFiles { file ->
                    file.isFile && file.name.startsWith(FILE_PREFIX) && file.name.endsWith(FILE_SUFFIX)
                } ?: return@Thread

                for (file in files) {
                    if (file.lastModified() < cutoffDate) {
                        if (file.delete()) {
                            Log.d(TAG, "Deleted old log: ${file.name}")
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to cleanup old logs", e)
            }
        }.apply {
            isDaemon = true
            priority = Thread.MIN_PRIORITY
            start()
        }
    }

    /**
     * 获取日志级别字符
     */
    private fun getLevelChar(priority: Int): Char = when (priority) {
        Log.VERBOSE -> 'V'
        Log.DEBUG -> 'D'
        Log.INFO -> 'I'
        Log.WARN -> 'W'
        Log.ERROR -> 'E'
        Log.ASSERT -> 'A'
        else -> '?'
    }

    /**
     * 关闭日志树
     *
     * 应在 Application.onTerminate() 或适当时机调用
     */
    fun shutdown() {
        isRunning.set(false)
        writerThread.interrupt()
        try {
            writerThread.join(3000) // 等待最多 3 秒
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    /**
     * 获取当前日志文件
     */
    fun getCurrentLogFile(): File? {
        val today = dateFormat.format(Date())
        val file = File(logDir, "$FILE_PREFIX$today$FILE_SUFFIX")
        return if (file.exists()) file else null
    }

    /**
     * 获取所有日志文件
     */
    fun getAllLogFiles(): List<File> = logDir.listFiles { file ->
        file.isFile && file.name.startsWith(FILE_PREFIX) && file.name.endsWith(FILE_SUFFIX)
    }?.sortedByDescending { it.name } ?: emptyList()

    /**
     * 立即刷新缓冲区
     */
    fun flush() {
        // 唤醒写入线程处理队列
        writerThread.interrupt()
    }
}
