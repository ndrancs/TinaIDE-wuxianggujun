package com.wuxianggujun.tinaide.core.proot

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong

/**
 * 安装日志条目
 *
 * 专用于安装日志界面的日志数据类
 */
data class InstallLogEntry(
    val id: Long,
    val level: InstallLogLevel,
    val timestamp: Long,
    val tag: String,
    val message: String
) {
    /**
     * 格式化的时间（短格式：HH:mm:ss）
     */
    val formattedTime: String
        get() = SHORT_TIME_FORMAT.get()?.format(Date(timestamp)) ?: ""

    /**
     * 格式化的时间（完整格式：yyyy-MM-dd HH:mm:ss）
     */
    val formattedFullTime: String
        get() = FULL_TIME_FORMAT.get()?.format(Date(timestamp)) ?: ""

    /**
     * 完整的日志文本
     */
    val fullText: String
        get() = if (tag.isNotEmpty()) {
            "$formattedTime $tag: $message"
        } else {
            "$formattedTime $message"
        }

    /**
     * 用于文件输出的格式
     */
    val fileFormat: String
        get() = if (tag.isNotEmpty()) {
            "$formattedFullTime ${level.char} $tag: $message"
        } else {
            "$formattedFullTime ${level.char} $message"
        }

    companion object {
        /**
         * 线程安全的日期格式化器（短格式）
         */
        private val SHORT_TIME_FORMAT = object : ThreadLocal<SimpleDateFormat>() {
            override fun initialValue(): SimpleDateFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
        }

        /**
         * 线程安全的日期格式化器（完整格式）
         */
        private val FULL_TIME_FORMAT = object : ThreadLocal<SimpleDateFormat>() {
            override fun initialValue(): SimpleDateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
        }

        /**
         * 线程安全的 ID 计数器
         */
        private val idCounter = AtomicLong(0L)

        /**
         * 生成唯一 ID（线程安全）
         */
        private fun nextId(): Long = idCounter.incrementAndGet()

        /**
         * 创建新的日志条目
         */
        fun create(
            level: InstallLogLevel,
            message: String,
            tag: String = "",
            timestamp: Long = System.currentTimeMillis()
        ): InstallLogEntry = InstallLogEntry(
            id = nextId(),
            level = level,
            timestamp = timestamp,
            tag = tag,
            message = message
        )
    }
}
