package com.wuxianggujun.tinaide.ui.compose.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * TinaIDE 按钮组件库
 * 
 * 按钮类型：
 * - Primary: 主要操作按钮（填充样式）
 * - Secondary: 次要操作按钮（色调样式）
 * - Outlined: 轮廓按钮
 * - Text: 文本按钮（低强调）
 * - Danger: 危险操作按钮
 */

/**
 * 主要操作按钮（填充样式）
 * 
 * 用于页面中最重要的操作，如"确认"、"下一步"、"保存"等
 */
@Composable
fun TinaPrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    icon: Painter? = null,
    contentPadding: PaddingValues = ButtonDefaults.ContentPadding
) {
    Button(
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
        shape = RoundedCornerShape(TinaShapes.ButtonCorner),
        colors = ButtonDefaults.buttonColors(
            containerColor = MaterialTheme.colorScheme.primary,
            contentColor = MaterialTheme.colorScheme.onPrimary
        ),
        contentPadding = contentPadding
    ) {
        ButtonContent(text = text, icon = icon)
    }
}

/**
 * 大号主要按钮（用于页面底部的主操作）
 */
@Composable
fun TinaPrimaryButtonLarge(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    icon: Painter? = null
) {
    Button(
        onClick = onClick,
        modifier = modifier
            .fillMaxWidth()
            .height(56.dp),
        enabled = enabled,
        shape = RoundedCornerShape(TinaShapes.ButtonCorner),
        colors = ButtonDefaults.buttonColors(
            containerColor = MaterialTheme.colorScheme.primary,
            contentColor = MaterialTheme.colorScheme.onPrimary
        )
    ) {
        ButtonContent(
            text = text,
            icon = icon,
            textStyle = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Medium)
        )
    }
}

/**
 * 次要操作按钮（色调样式）
 * 
 * 用于次要操作，如"编辑"、"查看详情"等
 */
@Composable
fun TinaSecondaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    icon: Painter? = null,
    contentPadding: PaddingValues = ButtonDefaults.ContentPadding
) {
    FilledTonalButton(
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
        shape = RoundedCornerShape(TinaShapes.ButtonCorner),
        contentPadding = contentPadding
    ) {
        ButtonContent(text = text, icon = icon)
    }
}

/**
 * 轮廓按钮
 *
 * 用于中等强调的操作，如"取消"、"返回"等
 */
@Composable
fun TinaOutlinedButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    icon: Painter? = null,
    leadingIcon: androidx.compose.ui.graphics.vector.ImageVector? = null,
    contentPadding: PaddingValues = ButtonDefaults.ContentPadding
) {
    OutlinedButton(
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
        shape = RoundedCornerShape(TinaShapes.ButtonCorner),
        contentPadding = contentPadding
    ) {
        if (leadingIcon != null) {
            Icon(
                imageVector = leadingIcon,
                contentDescription = null,
                modifier = Modifier.size(18.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
        }
        ButtonContent(text = text, icon = icon)
    }
}

/**
 * 文本按钮（低强调）
 * 
 * 用于低优先级操作，如"跳过"、"稍后"等
 */
@Composable
fun TinaTextButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    icon: Painter? = null,
    contentPadding: PaddingValues = ButtonDefaults.TextButtonContentPadding
) {
    TextButton(
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
        shape = RoundedCornerShape(TinaShapes.ButtonCorner),
        contentPadding = contentPadding
    ) {
        ButtonContent(text = text, icon = icon)
    }
}

/**
 * 危险操作按钮
 * 
 * 用于删除、清除等危险操作
 */
@Composable
fun TinaDangerButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    icon: Painter? = null,
    contentPadding: PaddingValues = ButtonDefaults.ContentPadding
) {
    Button(
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
        shape = RoundedCornerShape(TinaShapes.ButtonCorner),
        colors = ButtonDefaults.buttonColors(
            containerColor = MaterialTheme.colorScheme.error,
            contentColor = MaterialTheme.colorScheme.onError
        ),
        contentPadding = contentPadding
    ) {
        ButtonContent(text = text, icon = icon)
    }
}

/**
 * 危险轮廓按钮 (支持 ImageVector 图标)
 */
@Composable
fun TinaDangerOutlinedButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    leadingIcon: androidx.compose.ui.graphics.vector.ImageVector? = null,
    contentPadding: PaddingValues = ButtonDefaults.ContentPadding
) {
    OutlinedButton(
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
        shape = RoundedCornerShape(TinaShapes.ButtonCorner),
        colors = ButtonDefaults.outlinedButtonColors(
            contentColor = MaterialTheme.colorScheme.error
        ),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.error),
        contentPadding = contentPadding
    ) {
        if (leadingIcon != null) {
            Icon(
                imageVector = leadingIcon,
                contentDescription = null,
                modifier = Modifier.size(18.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
        }
        Text(
            text = text,
            color = MaterialTheme.colorScheme.error
        )
    }
}

/**
 * 按钮内容组件
 */
@Composable
internal fun RowScope.ButtonContent(
    text: String,
    icon: Painter? = null,
    textStyle: androidx.compose.ui.text.TextStyle = LocalTextStyle.current,
    textColor: Color = Color.Unspecified
) {
    if (icon != null) {
        Icon(
            painter = icon,
            contentDescription = null,
            modifier = Modifier.size(18.dp)
        )
        Spacer(modifier = Modifier.width(8.dp))
    }
    Text(
        text = text,
        style = textStyle,
        color = textColor
    )
}
