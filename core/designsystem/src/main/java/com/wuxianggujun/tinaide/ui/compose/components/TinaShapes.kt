package com.wuxianggujun.tinaide.ui.compose.components

import androidx.compose.ui.unit.dp

/**
 * TinaIDE 统一圆角常量
 *
 * 设计规范：
 * - 按钮圆角：12dp（统一使用中等圆角，既现代又不过于圆润）
 * - 卡片圆角：16dp
 * - 对话框圆角：24dp
 * - 输入框圆角：12dp
 */
object TinaShapes {
    /** 按钮圆角 */
    val ButtonCorner = 12.dp

    /** 卡片圆角 */
    val CardCorner = 16.dp

    /** 对话框圆角 */
    val DialogCorner = 24.dp

    /** 输入框圆角 */
    val TextFieldCorner = 12.dp

    /** 小圆角（标签、徽章等） */
    val SmallCorner = 8.dp

    /** 超小圆角 */
    val ExtraSmallCorner = 4.dp
}
