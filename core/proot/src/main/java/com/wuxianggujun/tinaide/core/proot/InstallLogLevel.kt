package com.wuxianggujun.tinaide.core.proot

import androidx.annotation.ColorInt

/**
 * 安装日志级别
 *
 * 专用于安装日志界面的日志级别枚举
 */
enum class InstallLogLevel(
    val char: Char,
    val displayName: String,
    @param:ColorInt val color: Int
) {
    VERBOSE('V', "VERBOSE", 0xFF9E9E9E.toInt()), // 灰色
    DEBUG('D', "DEBUG", 0xFF2196F3.toInt()), // 蓝色
    INFO('I', "INFO", 0xFF4CAF50.toInt()), // 绿色
    WARN('W', "WARN", 0xFFFF9800.toInt()), // 橙色
    ERROR('E', "ERROR", 0xFFF44336.toInt()), // 红色
    SUCCESS('S', "SUCCESS", 0xFF00E676.toInt()), // 亮绿色
    FAIL('F', "FAIL", 0xFFFF1744.toInt()), // 亮红色
    COMMAND('>', "CMD", 0xFF64B5F6.toInt()); // 浅蓝色（命令）

    companion object {
        /**
         * 从文本中检测日志等级
         */
        fun detect(text: String): InstallLogLevel? {
            val upperText = text.uppercase()
            return when {
                upperText.contains("ERROR") || upperText.contains("FAIL") -> ERROR
                upperText.contains("WARN") -> WARN
                upperText.contains("SUCCESS") -> SUCCESS
                upperText.contains("DEBUG") -> DEBUG
                upperText.contains("VERBOSE") -> VERBOSE
                upperText.startsWith(">") -> COMMAND
                else -> null
            }
        }
    }
}
