package com.wuxianggujun.tinaide.core.compile

import android.content.Context
import androidx.annotation.StringRes
import com.wuxianggujun.tinaide.core.i18n.Strings

/**
 * 构建日志级别
 *
 * 专用于构建日志的日志级别枚举。
 * 颜色统一由 TinaSemanticColors.Log 管理，不在此处定义。
 */
enum class BuildLogLevel(
    val char: Char,
    val displayName: String,
    @param:StringRes private val displayNameResId: Int? = null
) {
    VERBOSE('V', "VERBOSE"),
    DEBUG('D', "DEBUG"),
    PROGRESS('P', "PROGRESS", Strings.build_log_level_progress),
    INFO('I', "INFO", Strings.build_log_level_info),
    WARN('W', "WARN", Strings.build_log_level_warning),
    ERROR('E', "ERROR", Strings.build_log_level_error),
    SUCCESS('S', "SUCCESS"),
    FAIL('F', "FAIL");

    /**
     * 获取本地化的显示名称
     */
    fun getDisplayName(context: Context): String = displayNameResId?.let { context.getString(it) } ?: displayName

    companion object {
        private val NINJA_PROGRESS_REGEX = Regex("""^\[\d+/\d+]""")

        /**
         * 从文本中检测日志等级
         */
        fun detect(text: String): BuildLogLevel {
            val upperText = text.uppercase()

            // 英文关键词
            val hasError = upperText.contains("ERROR") || upperText.contains("FAIL")
            val hasWarn = upperText.contains("WARN")
            val hasSuccess = upperText.contains("SUCCESS")
            val hasProgress = NINJA_PROGRESS_REGEX.containsMatchIn(text.trimStart())

            return when {
                hasError -> ERROR
                hasWarn -> WARN
                hasSuccess -> SUCCESS
                hasProgress -> PROGRESS
                upperText.contains("===") -> INFO
                else -> DEBUG
            }
        }
    }
}
