package com.wuxianggujun.tinaide.terminal.theme

import android.graphics.Color

/**
 * 终端配色主题
 *
 * 定义终端的颜色方案，包括前景色、背景色、光标色和 ANSI 16 色。
 */
sealed class TerminalTheme(
    val name: String,
    val defaultForeground: Int,
    val defaultBackground: Int,
    val cursorColor: Int,
    val ansiColors: IntArray
) {
    /**
     * 默认主题（深色）
     */
    object Default : TerminalTheme(
        name = "Default",
        defaultForeground = Color.parseColor("#CCCCCC"),
        defaultBackground = Color.parseColor("#1E1E1E"),
        cursorColor = Color.parseColor("#FFFFFF"),
        ansiColors = intArrayOf(
            // Normal colors (0-7)
            Color.parseColor("#000000"), // Black
            Color.parseColor("#CD3131"), // Red
            Color.parseColor("#0DBC79"), // Green
            Color.parseColor("#E5E510"), // Yellow
            Color.parseColor("#2472C8"), // Blue
            Color.parseColor("#BC3FBC"), // Magenta
            Color.parseColor("#11A8CD"), // Cyan
            Color.parseColor("#E5E5E5"), // White
            // Bright colors (8-15)
            Color.parseColor("#666666"), // Bright Black
            Color.parseColor("#F14C4C"), // Bright Red
            Color.parseColor("#23D18B"), // Bright Green
            Color.parseColor("#F5F543"), // Bright Yellow
            Color.parseColor("#3B8EEA"), // Bright Blue
            Color.parseColor("#D670D6"), // Bright Magenta
            Color.parseColor("#29B8DB"), // Bright Cyan
            Color.parseColor("#FFFFFF") // Bright White
        )
    )

    /**
     * Monokai 主题
     */
    object Monokai : TerminalTheme(
        name = "Monokai",
        defaultForeground = Color.parseColor("#F8F8F2"),
        defaultBackground = Color.parseColor("#272822"),
        cursorColor = Color.parseColor("#F8F8F0"),
        ansiColors = intArrayOf(
            // Normal colors (0-7)
            Color.parseColor("#272822"), // Black
            Color.parseColor("#F92672"), // Red
            Color.parseColor("#A6E22E"), // Green
            Color.parseColor("#F4BF75"), // Yellow
            Color.parseColor("#66D9EF"), // Blue
            Color.parseColor("#AE81FF"), // Magenta
            Color.parseColor("#A1EFE4"), // Cyan
            Color.parseColor("#F8F8F2"), // White
            // Bright colors (8-15)
            Color.parseColor("#75715E"), // Bright Black
            Color.parseColor("#F92672"), // Bright Red
            Color.parseColor("#A6E22E"), // Bright Green
            Color.parseColor("#F4BF75"), // Bright Yellow
            Color.parseColor("#66D9EF"), // Bright Blue
            Color.parseColor("#AE81FF"), // Bright Magenta
            Color.parseColor("#A1EFE4"), // Bright Cyan
            Color.parseColor("#F9F8F5") // Bright White
        )
    )

    /**
     * Solarized Dark 主题
     */
    object SolarizedDark : TerminalTheme(
        name = "Solarized Dark",
        defaultForeground = Color.parseColor("#839496"),
        defaultBackground = Color.parseColor("#002B36"),
        cursorColor = Color.parseColor("#93A1A1"),
        ansiColors = intArrayOf(
            // Normal colors (0-7)
            Color.parseColor("#073642"), // Black
            Color.parseColor("#DC322F"), // Red
            Color.parseColor("#859900"), // Green
            Color.parseColor("#B58900"), // Yellow
            Color.parseColor("#268BD2"), // Blue
            Color.parseColor("#D33682"), // Magenta
            Color.parseColor("#2AA198"), // Cyan
            Color.parseColor("#EEE8D5"), // White
            // Bright colors (8-15)
            Color.parseColor("#002B36"), // Bright Black
            Color.parseColor("#CB4B16"), // Bright Red
            Color.parseColor("#586E75"), // Bright Green
            Color.parseColor("#657B83"), // Bright Yellow
            Color.parseColor("#839496"), // Bright Blue
            Color.parseColor("#6C71C4"), // Bright Magenta
            Color.parseColor("#93A1A1"), // Bright Cyan
            Color.parseColor("#FDF6E3") // Bright White
        )
    )

    /**
     * Dracula 主题
     */
    object Dracula : TerminalTheme(
        name = "Dracula",
        defaultForeground = Color.parseColor("#F8F8F2"),
        defaultBackground = Color.parseColor("#282A36"),
        cursorColor = Color.parseColor("#F8F8F2"),
        ansiColors = intArrayOf(
            // Normal colors (0-7)
            Color.parseColor("#21222C"), // Black
            Color.parseColor("#FF5555"), // Red
            Color.parseColor("#50FA7B"), // Green
            Color.parseColor("#F1FA8C"), // Yellow
            Color.parseColor("#BD93F9"), // Blue
            Color.parseColor("#FF79C6"), // Magenta
            Color.parseColor("#8BE9FD"), // Cyan
            Color.parseColor("#F8F8F2"), // White
            // Bright colors (8-15)
            Color.parseColor("#6272A4"), // Bright Black
            Color.parseColor("#FF6E6E"), // Bright Red
            Color.parseColor("#69FF94"), // Bright Green
            Color.parseColor("#FFFFA5"), // Bright Yellow
            Color.parseColor("#D6ACFF"), // Bright Blue
            Color.parseColor("#FF92DF"), // Bright Magenta
            Color.parseColor("#A4FFFF"), // Bright Cyan
            Color.parseColor("#FFFFFF") // Bright White
        )
    )

    /**
     * Nord 主题
     */
    object Nord : TerminalTheme(
        name = "Nord",
        defaultForeground = Color.parseColor("#D8DEE9"),
        defaultBackground = Color.parseColor("#2E3440"),
        cursorColor = Color.parseColor("#D8DEE9"),
        ansiColors = intArrayOf(
            // Normal colors (0-7)
            Color.parseColor("#3B4252"), // Black
            Color.parseColor("#BF616A"), // Red
            Color.parseColor("#A3BE8C"), // Green
            Color.parseColor("#EBCB8B"), // Yellow
            Color.parseColor("#81A1C1"), // Blue
            Color.parseColor("#B48EAD"), // Magenta
            Color.parseColor("#88C0D0"), // Cyan
            Color.parseColor("#E5E9F0"), // White
            // Bright colors (8-15)
            Color.parseColor("#4C566A"), // Bright Black
            Color.parseColor("#BF616A"), // Bright Red
            Color.parseColor("#A3BE8C"), // Bright Green
            Color.parseColor("#EBCB8B"), // Bright Yellow
            Color.parseColor("#81A1C1"), // Bright Blue
            Color.parseColor("#B48EAD"), // Bright Magenta
            Color.parseColor("#8FBCBB"), // Bright Cyan
            Color.parseColor("#ECEFF4") // Bright White
        )
    )

    companion object {
        /**
         * 所有可用主题
         */
        val allThemes: List<TerminalTheme> by lazy {
            listOf(
                Default,
                Monokai,
                SolarizedDark,
                Dracula,
                Nord
            )
        }

        /**
         * 根据名称获取主题
         */
        fun fromName(name: String): TerminalTheme {
            // 直接匹配，避免依赖 allThemes 列表的初始化顺序
            return when (name) {
                "Default" -> Default
                "Monokai" -> Monokai
                "Solarized Dark" -> SolarizedDark
                "Dracula" -> Dracula
                "Nord" -> Nord
                else -> Default
            }
        }
    }
}
