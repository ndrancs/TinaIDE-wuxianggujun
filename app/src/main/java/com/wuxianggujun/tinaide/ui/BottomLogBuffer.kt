package com.wuxianggujun.tinaide.ui

import com.wuxianggujun.tinaide.output.LogLevel
import java.util.concurrent.CopyOnWriteArraySet

/**
 * 全局底部日志缓冲区，用于在 UI 不可见时缓存日志并在面板创建后回放。
 */
object BottomLogBuffer {
    data class LogEntry(val level: LogLevel, val message: String)

    fun interface LogListener {
        fun onLog(entry: LogEntry)
    }

    private val logs = mutableListOf<LogEntry>()
    private val listeners = CopyOnWriteArraySet<LogListener>()
    private val lock = Any()

    fun append(level: LogLevel, message: String) {
        val entry = LogEntry(level, message)
        synchronized(lock) {
            logs.add(entry)
        }
        listeners.forEach { listener ->
            listener.onLog(entry)
        }
    }

    fun clear() {
        synchronized(lock) {
            logs.clear()
        }
    }

    fun replayTo(listener: LogListener) {
        val snapshot = synchronized(lock) { logs.toList() }
        snapshot.forEach { entry -> listener.onLog(entry) }
    }

    fun addListener(listener: LogListener) {
        listeners.add(listener)
    }

    fun removeListener(listener: LogListener) {
        listeners.remove(listener)
    }
}
