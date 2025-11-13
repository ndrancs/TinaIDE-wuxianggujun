package com.wuxianggujun.tinaide.output

import androidx.annotation.ColorInt

/**
 * 日志等级
 */
enum class LogLevel(
    val prefix: String,
    @param:ColorInt val color: Int
) {
    ERROR("ERROR", 0xFFF44336.toInt()),   // 红色
    WARN("WARN", 0xFFFF9800.toInt()),     // 橙色
    INFO("INFO", 0xFF4CAF50.toInt()),     // 绿色
    DEBUG("DEBUG", 0xFF2196F3.toInt()),   // 蓝色
    VERBOSE("VERBOSE", 0xFF9E9E9E.toInt()),// 灰色
    SUCCESS("SUCCESS", 0xFF00E676.toInt()),// 亮绿色
    FAIL("FAIL", 0xFFFF1744.toInt());     // 亮红色
    
    companion object {
        /**
         * 从文本中检测日志等级
         */
        fun detect(text: String): LogLevel? {
            val upperText = text.uppercase()
            return values().firstOrNull { upperText.contains(it.prefix) }
        }
        
        /**
         * 检测是否包含日志等级关键字
         */
        fun containsLogLevel(text: String): Boolean {
            val upperText = text.uppercase()
            return values().any { upperText.contains(it.prefix) }
        }
    }
}
