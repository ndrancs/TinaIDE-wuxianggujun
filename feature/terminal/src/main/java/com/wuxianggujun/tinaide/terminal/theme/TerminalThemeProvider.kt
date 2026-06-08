package com.wuxianggujun.tinaide.terminal.theme

import com.wuxianggujun.tinaide.core.terminal.ITerminalThemeProvider
import com.wuxianggujun.tinaide.core.terminal.TerminalThemeData

/**
 * 终端主题提供者实现
 *
 * 将 TerminalTheme 适配为 ITerminalThemeProvider 接口。
 */
class TerminalThemeProvider : ITerminalThemeProvider {

    override fun getAllThemes(): List<TerminalThemeData> = TerminalTheme.allThemes.map { it.toData() }

    override fun getThemeByName(name: String): TerminalThemeData = TerminalTheme.fromName(name).toData()

    private fun TerminalTheme.toData(): TerminalThemeData = TerminalThemeData(
        name = this.name,
        defaultForeground = this.defaultForeground,
        defaultBackground = this.defaultBackground,
        cursorColor = this.cursorColor,
        ansiColors = this.ansiColors
    )
}
