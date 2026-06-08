package com.wuxianggujun.tinaide.core.compile

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 构建日志条目
 *
 * 专门用于构建日志的数据类。
 * timestamp 在条目创建时自动记录，确保时间戳反映日志产生的真实时刻。
 */
data class BuildLogEntry(
    val level: BuildLogLevel,
    val message: String,
    val timestamp: Long = System.currentTimeMillis()
) {
    /**
     * 格式化的时间字符串 (HH:mm:ss)
     */
    val formattedTime: String by lazy {
        TIME_FORMAT.format(Date(timestamp))
    }

    /**
     * 完整的日志文本（用于复制）
     */
    val fullText: String
        get() = "[$formattedTime] [${level.name}] $message"

    companion object {
        private val TIME_FORMAT = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

        /**
         * 从 BuildLogLevel 和消息创建条目
         */
        fun create(level: BuildLogLevel, message: String): BuildLogEntry = BuildLogEntry(level, message)
    }
}
