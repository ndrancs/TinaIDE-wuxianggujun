package com.wuxianggujun.tinaide.terminal.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 快捷键序列定义
 */
object QuickKeys {
    const val ESC = "\u001b"
    const val TAB = "\t"
    const val ENTER = "\r"

    // 方向键
    const val ARROW_UP = "\u001b[A"
    const val ARROW_DOWN = "\u001b[B"
    const val ARROW_RIGHT = "\u001b[C"
    const val ARROW_LEFT = "\u001b[D"

    // 功能键
    const val HOME = "\u001b[H"
    const val END = "\u001b[F"
    const val PAGE_UP = "\u001b[5~"
    const val PAGE_DOWN = "\u001b[6~"
    const val DELETE = "\u001b[3~"
    const val INSERT = "\u001b[2~"

    // 常用符号
    const val PIPE = "|"
    const val SLASH = "/"
    const val BACKSLASH = "\\"
    const val DASH = "-"
    const val UNDERSCORE = "_"
    const val TILDE = "~"
    const val BACKTICK = "`"
    const val AMPERSAND = "&"
    const val SEMICOLON = ";"
    const val COLON = ":"
    const val DOLLAR = "$"
    const val AT = "@"
    const val HASH = "#"
    const val ASTERISK = "*"
    const val QUESTION = "?"
    const val EXCLAMATION = "!"
    const val PERCENT = "%"
    const val CARET = "^"
    const val EQUALS = "="
    const val PLUS = "+"
    const val LESS_THAN = "<"
    const val GREATER_THAN = ">"
    const val OPEN_BRACKET = "["
    const val CLOSE_BRACKET = "]"
    const val OPEN_BRACE = "{"
    const val CLOSE_BRACE = "}"
    const val OPEN_PAREN = "("
    const val CLOSE_PAREN = ")"
    const val SINGLE_QUOTE = "'"
    const val DOUBLE_QUOTE = "\""
}

/**
 * 终端快捷键栏（两行布局，类似 Termux）
 *
 * 第一行：ESC、符号键... | ↑ | 导航键
 * 第二行：CTRL、ALT、TAB... | ←↓→ | 更多符号
 */
@Composable
fun TerminalExtraKeys(
    ctrlEnabled: Boolean,
    altEnabled: Boolean,
    onCtrlToggle: () -> Unit,
    onAltToggle: () -> Unit,
    onKey: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier,
        color = Color(0xFF2D2D2D),
        tonalElevation = 4.dp
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 左侧：两行按键（可滚动）
            Column(
                modifier = Modifier.weight(1f)
            ) {
                // 第一行
                Row(
                    modifier = Modifier
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = 4.dp, vertical = 2.dp),
                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    ExtraKey("ESC") { onKey(QuickKeys.ESC) }
                    ExtraKey("/") { onKey(QuickKeys.SLASH) }
                    ExtraKey("-") { onKey(QuickKeys.DASH) }
                    ExtraKey("|") { onKey(QuickKeys.PIPE) }
                    ExtraKey("~") { onKey(QuickKeys.TILDE) }
                    ExtraKey("HOME") { onKey(QuickKeys.HOME) }
                    ExtraKey("END") { onKey(QuickKeys.END) }
                    ExtraKey("PGUP") { onKey(QuickKeys.PAGE_UP) }
                    ExtraKey("PGDN") { onKey(QuickKeys.PAGE_DOWN) }
                    ExtraKey("\\") { onKey(QuickKeys.BACKSLASH) }
                    ExtraKey("`") { onKey(QuickKeys.BACKTICK) }
                    ExtraKey("$") { onKey(QuickKeys.DOLLAR) }
                    ExtraKey("@") { onKey(QuickKeys.AT) }
                    ExtraKey("#") { onKey(QuickKeys.HASH) }
                    ExtraKey("*") { onKey(QuickKeys.ASTERISK) }
                }

                // 第二行
                Row(
                    modifier = Modifier
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = 4.dp, vertical = 2.dp),
                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    ToggleKey("CTRL", ctrlEnabled, onCtrlToggle)
                    ToggleKey("ALT", altEnabled, onAltToggle)
                    ExtraKey("TAB") { onKey(QuickKeys.TAB) }
                    ExtraKey("_") { onKey(QuickKeys.UNDERSCORE) }
                    ExtraKey("&") { onKey(QuickKeys.AMPERSAND) }
                    ExtraKey(";") { onKey(QuickKeys.SEMICOLON) }
                    ExtraKey(":") { onKey(QuickKeys.COLON) }
                    ExtraKey("?") { onKey(QuickKeys.QUESTION) }
                    ExtraKey("!") { onKey(QuickKeys.EXCLAMATION) }
                    ExtraKey("%") { onKey(QuickKeys.PERCENT) }
                    ExtraKey("^") { onKey(QuickKeys.CARET) }
                    ExtraKey("=") { onKey(QuickKeys.EQUALS) }
                    ExtraKey("+") { onKey(QuickKeys.PLUS) }
                    ExtraKey("<") { onKey(QuickKeys.LESS_THAN) }
                    ExtraKey(">") { onKey(QuickKeys.GREATER_THAN) }
                    ExtraKey("[") { onKey(QuickKeys.OPEN_BRACKET) }
                    ExtraKey("]") { onKey(QuickKeys.CLOSE_BRACKET) }
                    ExtraKey("{") { onKey(QuickKeys.OPEN_BRACE) }
                    ExtraKey("}") { onKey(QuickKeys.CLOSE_BRACE) }
                    ExtraKey("(") { onKey(QuickKeys.OPEN_PAREN) }
                    ExtraKey(")") { onKey(QuickKeys.CLOSE_PAREN) }
                    ExtraKey("'") { onKey(QuickKeys.SINGLE_QUOTE) }
                    ExtraKey("\"") { onKey(QuickKeys.DOUBLE_QUOTE) }
                }
            }

            // 右侧：方向键十字布局（固定位置）
            ArrowKeyPad(onKey = onKey)
        }
    }
}

/**
 * 方向键十字布局
 *     ↑
 *   ← ↓ →
 */
@Composable
private fun ArrowKeyPad(onKey: (String) -> Unit) {
    Box(
        modifier = Modifier
            .width(100.dp)
            .padding(end = 4.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // 上箭头
            ArrowKey(Icons.Filled.KeyboardArrowUp) { onKey(QuickKeys.ARROW_UP) }

            // 左、下、右箭头
            Row(
                horizontalArrangement = Arrangement.spacedBy(0.dp)
            ) {
                ArrowKey(Icons.AutoMirrored.Filled.KeyboardArrowLeft) { onKey(QuickKeys.ARROW_LEFT) }
                ArrowKey(Icons.Filled.KeyboardArrowDown) { onKey(QuickKeys.ARROW_DOWN) }
                ArrowKey(Icons.AutoMirrored.Filled.KeyboardArrowRight) { onKey(QuickKeys.ARROW_RIGHT) }
            }
        }
    }
}

@Composable
private fun ArrowKey(icon: ImageVector, onClick: () -> Unit) {
    IconButton(
        onClick = onClick,
        modifier = Modifier.size(32.dp)
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = Color.White,
            modifier = Modifier.size(24.dp)
        )
    }
}

@Composable
private fun ExtraKey(label: String, onClick: () -> Unit) {
    TextButton(
        onClick = onClick,
        modifier = Modifier
            .widthIn(min = 36.dp)
            .height(36.dp),
        shape = RoundedCornerShape(4.dp)
    ) {
        Text(
            text = label,
            fontSize = 12.sp,
            color = Color.White
        )
    }
}

@Composable
private fun ToggleKey(label: String, enabled: Boolean, onClick: () -> Unit) {
    TextButton(
        onClick = onClick,
        modifier = Modifier
            .widthIn(min = 44.dp)
            .height(36.dp)
            .background(
                color = if (enabled) Color(0xFF4CAF50).copy(alpha = 0.3f) else Color.Transparent,
                shape = RoundedCornerShape(4.dp)
            ),
        shape = RoundedCornerShape(4.dp)
    ) {
        Text(
            text = label,
            fontSize = 11.sp,
            color = if (enabled) Color(0xFF4CAF50) else Color.White
        )
    }
}

@Composable
private fun KeyDivider() {
    Text(
        text = "│",
        color = Color.White.copy(alpha = 0.3f),
        fontSize = 14.sp,
        modifier = Modifier.padding(horizontal = 2.dp)
    )
}
