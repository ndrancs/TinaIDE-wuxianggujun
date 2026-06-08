package com.wuxianggujun.tinaide.core.logging

import android.annotation.SuppressLint
import android.content.Context
import android.util.Log
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import timber.log.Timber

/**
 * TinaIDE Timber 日志管理器
 *
 * 功能：
 * - 统一管理 Timber 初始化
 * - Debug 版本：输出到 Logcat + 文件
 * - Release 版本：仅保存到文件（隐藏敏感调试信息）
 * - 自动日志轮转和清理
 *
 * 使用方式：
 * ```
 * // 在 Application.onCreate() 中初始化
 * TinaTimber.initialize(applicationContext)
 *
 * // 使用 Timber 记录日志
 * Timber.d("Debug message")
 * Timber.tag("MyTag").i("Info message")
 * Timber.e(exception, "Error occurred")
 *
 * // 在 Application.onTerminate() 中关闭
 * TinaTimber.shutdown()
 * ```
 *
 * 注意：初始化阶段和 Tree 内部使用 android.util.Log，避免 Timber 未初始化或无限递归
 */
@SuppressLint("LogNotTimber")
object TinaTimber {

    private const val TAG = "TinaTimber"

    private val isInitialized = AtomicBoolean(false)
    private var fileLoggingTree: FileLoggingTree? = null

    /**
     * 初始化 Timber
     *
     * @param context Application Context
     * @param isDebug 是否为调试模式（由调用方传入，消除对 BuildConfig 的依赖）
     * @param logDir 日志文件目录（由调用方传入，消除对 ProjectPaths 的依赖）
     * @param enableFileLogging 是否启用文件日志（默认 true）
     * @param fileLogMinPriority 文件日志最低级别（默认 Debug）
     * @param logRetainDays 日志保留天数（默认 7 天）
     */
    @JvmStatic
    @JvmOverloads
    fun initialize(
        context: Context,
        isDebug: Boolean,
        logDir: File,
        enableFileLogging: Boolean = true,
        fileLogMinPriority: Int = Log.DEBUG,
        logRetainDays: Int = 7
    ) {
        if (!isInitialized.compareAndSet(false, true)) {
            Timber.tag(TAG).w("TinaTimber already initialized")
            return
        }

        // 1. Debug 版本：添加 DebugTree（输出到 Logcat）
        if (isDebug) {
            Timber.plant(TinaDebugTree())
        } else {
            // Release 版本：添加 ReleaseTree（仅输出 Warning 及以上到 Logcat）
            Timber.plant(ReleaseTree())
        }

        // 2. 文件日志：始终启用（用于问题诊断）
        if (enableFileLogging) {
            runCatching {
                if (!logDir.exists()) logDir.mkdirs()
                val tree = FileLoggingTree(
                    logDir = logDir,
                    minPriority = fileLogMinPriority,
                    maxRetainDays = logRetainDays
                )
                Timber.plant(tree)
                fileLoggingTree = tree
                Timber.tag(TAG).i(
                    "FileLoggingTree enabled (minPriority=${priorityName(fileLogMinPriority)}, retainDays=$logRetainDays)"
                )
            }.onFailure { t ->
                Log.e(TAG, "Failed to initialize FileLoggingTree", t)
            }
        }

        Timber.i("TinaTimber initialized successfully")
    }

    /**
     * 关闭 Timber
     *
     * 应在 Application.onTerminate() 中调用
     */
    @JvmStatic
    fun shutdown() {
        if (!isInitialized.get()) {
            return
        }

        Timber.i("TinaTimber shutting down...")

        // 关闭文件日志
        fileLoggingTree?.shutdown()
        fileLoggingTree = null

        // 移除所有 Tree
        Timber.tag(TAG).i("Uprooting all Timber trees")
        Timber.uprootAll()

        isInitialized.set(false)
    }

    /**
     * 立即刷新日志缓冲区
     *
     * 在应用崩溃或退出前调用，确保日志写入磁盘
     */
    @JvmStatic
    fun flush() {
        fileLoggingTree?.flush()
    }

    /**
     * 获取当前日志文件
     */
    @JvmStatic
    fun getCurrentLogFile() = fileLoggingTree?.getCurrentLogFile()

    /**
     * 获取所有日志文件
     */
    @JvmStatic
    fun getAllLogFiles() = fileLoggingTree?.getAllLogFiles() ?: emptyList()

    /**
     * 检查是否已初始化
     */
    @JvmStatic
    fun isInitialized() = isInitialized.get()

    /**
     * 获取 FileLoggingTree 实例（用于高级操作）
     */
    @JvmStatic
    fun getFileLoggingTree(): FileLoggingTree? = fileLoggingTree

    private fun priorityName(priority: Int): String = when (priority) {
        Log.VERBOSE -> "VERBOSE"
        Log.DEBUG -> "DEBUG"
        Log.INFO -> "INFO"
        Log.WARN -> "WARN"
        Log.ERROR -> "ERROR"
        Log.ASSERT -> "ASSERT"
        else -> "UNKNOWN"
    }

    /**
     * TinaIDE 自定义 DebugTree
     *
     * 功能：
     * - 自动提取类名作为 Tag
     * - 输出所有日志到 Logcat
     */
    private class TinaDebugTree : Timber.DebugTree() {
        override fun createStackElementTag(element: StackTraceElement): String {
            // 格式：ClassName:LineNumber
            return "${super.createStackElementTag(element)}:${element.lineNumber}"
        }
    }

    /**
     * Release 版本 Tree
     *
     * 功能：
     * - 仅输出 Warning 及以上级别到 Logcat
     * - 隐藏敏感调试信息
     */
    private class ReleaseTree : Timber.Tree() {
        override fun isLoggable(tag: String?, priority: Int): Boolean {
            // Release 版本仅输出 Warning 及以上
            return priority >= Log.WARN
        }

        override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
            when (priority) {
                Log.WARN -> Log.w(tag ?: "TinaIDE", message, t)
                Log.ERROR -> Log.e(tag ?: "TinaIDE", message, t)
                Log.ASSERT -> Log.wtf(tag ?: "TinaIDE", message, t)
            }
        }
    }
}
