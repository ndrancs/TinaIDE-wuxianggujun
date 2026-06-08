package com.wuxianggujun.tinaide.ui.workspace.log

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wuxianggujun.tinaide.core.i18n.Strings
import com.wuxianggujun.tinaide.core.i18n.strOr
import com.wuxianggujun.tinaide.core.proot.InstallLogEntry
import com.wuxianggujun.tinaide.core.proot.InstallLogLevel

/**
 * 纯 Compose 实现的安装日志面板
 *
 * 使用 LazyColumn 替代 AndroidView，避免布局冲突问题
 *
 * 功能：
 * - 高性能日志显示（虚拟化列表）
 * - 自动滚动到底部
 * - 长按复制日志条目
 * - 根据日志级别显示不同颜色
 * - 支持带标签的日志
 *
 * 注意：日志级别颜色使用固定值以保持一致的可读性（VERBOSE/DEBUG/INFO/WARN/ERROR 等）
 */
@Composable
fun ComposeInstallLogPanel(
    entries: List<InstallLogEntry>,
    modifier: Modifier = Modifier,
    backgroundColor: Color = MaterialTheme.colorScheme.background,
    autoScroll: Boolean = true,
    fontSizeSp: Float = 13f,
    onEntryClick: ((InstallLogEntry) -> Unit)? = null
) {
    val context = LocalContext.current
    val listState = rememberLazyListState()

    // 记住选中的条目（用 id 避免列表滚动/裁剪后 index 变化）
    var selectedEntryId by remember { mutableStateOf<Long?>(null) }

    // 自动滚动到底部
    LaunchedEffect(entries.size, autoScroll) {
        if (autoScroll && entries.isNotEmpty()) {
            // 频繁新增日志时使用无动画滚动，避免反复动画导致“抖动/跳动”
            listState.scrollToItem(entries.size - 1)
        }
    }

    LazyColumn(
        state = listState,
        modifier = modifier
            .fillMaxSize()
            .background(backgroundColor),
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)
    ) {
        items(
            items = entries,
            key = { entry -> entry.id }
        ) { entry ->
            val isSelected = selectedEntryId == entry.id

            InstallLogItem(
                entry = entry,
                isSelected = isSelected,
                fontSize = fontSizeSp.sp,
                onClick = {
                    selectedEntryId = if (isSelected) null else entry.id
                    onEntryClick?.invoke(entry)
                },
                onLongClick = {
                    selectedEntryId = entry.id
                    copyToClipboard(context, entry)
                }
            )
        }
    }
}

/**
 * 单个安装日志条目
 */
@Composable
private fun InstallLogItem(
    entry: InstallLogEntry,
    isSelected: Boolean,
    fontSize: androidx.compose.ui.unit.TextUnit,
    onClick: () -> Unit,
    onLongClick: () -> Unit
) {
    // 时间戳颜色 - 使用主题色
    val timestampColor = MaterialTheme.colorScheme.onSurfaceVariant
    // 标签颜色 - 使用主题色
    val tagColor = MaterialTheme.colorScheme.onSurface
    // 消息颜色 - 根据级别（保持固定颜色以便识别日志级别）
    val messageColor = getLevelColor(entry.level)

    val itemBackgroundColor = if (isSelected) {
        MaterialTheme.colorScheme.surfaceContainerHigh
    } else {
        Color.Transparent
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(itemBackgroundColor)
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick
            )
            .padding(vertical = 4.dp, horizontal = 4.dp)
    ) {
        // 时间戳 - 灰色，清晰可见
        Text(
            text = entry.formattedTime,
            color = timestampColor,
            fontSize = fontSize,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier.padding(end = 12.dp)
        )

        // 标签（如果有）- 白色
        if (entry.tag.isNotEmpty()) {
            Text(
                text = "${entry.tag}:",
                color = tagColor,
                fontSize = fontSize,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier.padding(end = 6.dp)
            )
        }

        // 日志消息（通过颜色区分级别）
        Text(
            text = entry.message,
            color = messageColor,
            fontSize = fontSize,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier.weight(1f),
            lineHeight = (fontSize.value * 1.4).sp
        )
    }
}

/**
 * 根据日志级别获取颜色 - 与设计图一致
 */
@Composable
private fun getLevelColor(level: InstallLogLevel): Color = when (level) {
    InstallLogLevel.VERBOSE -> Color(0xFF9CA3AF) // 灰色
    InstallLogLevel.DEBUG -> Color(0xFF60A5FA) // 蓝色
    InstallLogLevel.INFO -> Color(0xFF4ADE80) // 绿色（与设计图一致）
    InstallLogLevel.WARN -> Color(0xFFFBBF24) // 黄色（与设计图一致）
    InstallLogLevel.ERROR -> Color(0xFFF87171) // 红色
    InstallLogLevel.SUCCESS -> Color(0xFF4ADE80) // 亮绿色（与设计图一致）
    InstallLogLevel.FAIL -> Color(0xFFF87171) // 亮红色
    InstallLogLevel.COMMAND -> Color(0xFF93C5FD) // 浅蓝色（命令颜色）
}

/**
 * 复制日志条目到剪贴板
 */
private fun copyToClipboard(context: Context, entry: InstallLogEntry) {
    val clipboardManager = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    val clip = ClipData.newPlainText("Install Log", entry.fullText)
    clipboardManager.setPrimaryClip(clip)
    Toast.makeText(context, Strings.toast_copied.strOr(context), Toast.LENGTH_SHORT).show()
}
