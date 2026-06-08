package com.wuxianggujun.tinaide.core.format

/**
 * 格式化风格
 */
sealed class FormatStyle {
    /** 使用项目中的 .clang-format 文件 */
    object FILE : FormatStyle()

    /** LLVM 风格 */
    object LLVM : FormatStyle()

    /** Google 风格 */
    object GOOGLE : FormatStyle()

    /** Chromium 风格 */
    object CHROMIUM : FormatStyle()

    /** Mozilla 风格 */
    object MOZILLA : FormatStyle()

    /** WebKit 风格 */
    object WEBKIT : FormatStyle()

    /** Microsoft 风格 */
    object MICROSOFT : FormatStyle()

    /** GNU 风格 */
    object GNU : FormatStyle()

    /** 自定义风格配置 */
    data class Custom(val config: String) : FormatStyle()

    companion object {
        /**
         * 所有预定义风格列表（不包括 FILE 和 Custom）
         */
        val predefinedStyles = listOf(
            LLVM,
            GOOGLE,
            CHROMIUM,
            MOZILLA,
            WEBKIT,
            MICROSOFT,
            GNU
        )

        /**
         * 获取风格的显示名称
         */
        fun getDisplayName(style: FormatStyle): String = when (style) {
            FILE -> "File (.clang-format)"
            LLVM -> "LLVM"
            GOOGLE -> "Google"
            CHROMIUM -> "Chromium"
            MOZILLA -> "Mozilla"
            WEBKIT -> "WebKit"
            MICROSOFT -> "Microsoft"
            GNU -> "GNU"
            is Custom -> "Custom"
        }

        /**
         * 从字符串解析风格
         */
        fun fromString(name: String): FormatStyle = when (name.uppercase()) {
            "FILE" -> FILE
            "LLVM" -> LLVM
            "GOOGLE" -> GOOGLE
            "CHROMIUM" -> CHROMIUM
            "MOZILLA" -> MOZILLA
            "WEBKIT" -> WEBKIT
            "MICROSOFT" -> MICROSOFT
            "GNU" -> GNU
            else -> LLVM
        }

        /**
         * 将风格转换为字符串（用于存储）
         */
        fun toString(style: FormatStyle): String = when (style) {
            FILE -> "FILE"
            LLVM -> "LLVM"
            GOOGLE -> "GOOGLE"
            CHROMIUM -> "CHROMIUM"
            MOZILLA -> "MOZILLA"
            WEBKIT -> "WEBKIT"
            MICROSOFT -> "MICROSOFT"
            GNU -> "GNU"
            is Custom -> "CUSTOM"
        }
    }
}
