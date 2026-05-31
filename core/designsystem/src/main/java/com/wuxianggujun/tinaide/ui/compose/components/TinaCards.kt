package com.wuxianggujun.tinaide.ui.compose.components

import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * TinaIDE 卡片组件库
 */

private val cardShape = RoundedCornerShape(TinaShapes.CardCorner)

/**
 * 统一的卡片组件
 */
@Composable
fun TinaCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    containerColor: Color = MaterialTheme.colorScheme.surface,
    contentColor: Color = MaterialTheme.colorScheme.onSurface,
    elevation: Dp = 1.dp,
    content: @Composable ColumnScope.() -> Unit
) {
    val colors = CardDefaults.cardColors(
        containerColor = containerColor,
        contentColor = contentColor
    )
    val cardElevation = CardDefaults.cardElevation(defaultElevation = elevation)

    if (onClick != null) {
        Card(
            onClick = onClick,
            modifier = modifier,
            shape = cardShape,
            colors = colors,
            elevation = cardElevation,
            content = content
        )
    } else {
        Card(
            modifier = modifier,
            shape = cardShape,
            colors = colors,
            elevation = cardElevation,
            content = content
        )
    }
}
