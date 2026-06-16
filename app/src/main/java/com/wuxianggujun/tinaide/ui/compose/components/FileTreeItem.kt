package com.wuxianggujun.tinaide.ui.compose.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.compose.rememberAsyncImagePainter
import com.wuxianggujun.tinaide.core.git.FileStatus
import com.wuxianggujun.tinaide.core.i18n.Strings
import com.wuxianggujun.tinaide.ui.compose.icons.rememberTinaPainter

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun FileTreeItem(
    node: FileTreeNode,
    displayName: String,
    iconSource: FileTreeIconSource,
    isSelected: Boolean,
    gitStatus: FileGitStatus?,
    containerWidth: Dp,
    onClick: (FileTreeNode) -> Unit,
    onLongClick: (FileTreeNode, Offset) -> Unit,
    modifier: Modifier = Modifier
) {
    val boundsHolder = remember { FileTreeItemBoundsHolder() }
    val backgroundColor = if (isSelected) {
        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.75f)
    } else {
        Color.Transparent
    }

    Box(
        modifier = modifier
            .widthIn(min = containerWidth)
            .onGloballyPositioned { coordinates ->
                val bounds = coordinates.boundsInRoot()
                boundsHolder.boundsInRoot = bounds
            }
            .background(backgroundColor)
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        val down = event.changes.firstOrNull { change ->
                            change.pressed && !change.previousPressed
                        }
                        if (down != null) {
                            boundsHolder.lastPointerInRoot =
                                boundsHolder.boundsInRoot.topLeft + down.position
                        }
                    }
                }
            }
            .combinedClickable(
                onClick = { onClick(node) },
                onLongClick = { onLongClick(node, boundsHolder.longPressAnchorInRoot()) }
            )
            .padding(vertical = 8.dp)
            .padding(start = (node.level * 16).dp, end = 16.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (node.isDirectory) {
                Icon(
                    imageVector = if (node.isExpanded) Icons.Default.ExpandMore else Icons.Default.ChevronRight,
                    contentDescription = stringResource(if (node.isExpanded) Strings.content_desc_collapse else Strings.content_desc_expand),
                    modifier = Modifier.size(20.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                Spacer(modifier = Modifier.width(20.dp))
            }

            Spacer(modifier = Modifier.width(4.dp))

            val iconPainter = rememberFileTreeIconPainter(iconSource)
            Icon(
                painter = iconPainter,
                contentDescription = null,
                modifier = Modifier.size(20.dp),
                tint = Color.Unspecified // 使用图标自带的颜色
            )

            Spacer(modifier = Modifier.width(8.dp))

            Text(
                text = displayName,
                style = MaterialTheme.typography.bodyMedium,
                color = gitStatus?.let { getGitStatusTextColor(it) }
                    ?: MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )

            // Git 状态标记
            gitStatus?.let { status ->
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = status.status.symbol,
                    style = MaterialTheme.typography.labelSmall,
                    color = getGitStatusColor(status)
                )
            }
        }
    }
}

@Composable
private fun rememberFileTreeIconPainter(iconSource: FileTreeIconSource) = when (iconSource) {
    is FileTreeIconSource.AppDrawable -> rememberTinaPainter(iconSource.resId)
    is FileTreeIconSource.PluginAsset -> rememberAsyncImagePainter(model = iconSource.file)
}

private class FileTreeItemBoundsHolder {
    var boundsInRoot: Rect = Rect.Zero
    var lastPointerInRoot: Offset? = null

    fun longPressAnchorInRoot(): Offset = resolveFileTreeItemLongPressAnchor(
        boundsInRoot = boundsInRoot,
        pointerInRoot = lastPointerInRoot
    )
}

internal fun resolveFileTreeItemLongPressAnchor(
    boundsInRoot: Rect,
    pointerInRoot: Offset?
): Offset {
    if (pointerInRoot != null && pointerInRoot in boundsInRoot) {
        return pointerInRoot
    }
    return Offset(
        x = boundsInRoot.center.x,
        y = boundsInRoot.bottom
    )
}

/**
 * 获取 Git 状态颜色
 */
@Composable
private fun getGitStatusColor(status: FileGitStatus): Color = when (status.status) {
    FileStatus.MODIFIED -> Color(0xFF2196F3) // 蓝色
    FileStatus.ADDED -> Color(0xFF4CAF50) // 绿色
    FileStatus.DELETED -> Color(0xFFF44336) // 红色
    FileStatus.RENAMED -> Color(0xFF9C27B0) // 紫色
    FileStatus.COPIED -> Color(0xFF00BCD4) // 青色
    FileStatus.UNTRACKED -> Color(0xFF8BC34A) // 浅绿色
    FileStatus.IGNORED -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
}

/**
 * 获取 Git 状态文本颜色
 */
@Composable
private fun getGitStatusTextColor(status: FileGitStatus): Color = when (status.status) {
    FileStatus.MODIFIED -> Color(0xFF2196F3) // 蓝色
    FileStatus.ADDED -> Color(0xFF4CAF50) // 绿色
    FileStatus.DELETED -> Color(0xFFF44336) // 红色
    FileStatus.RENAMED -> Color(0xFF9C27B0) // 紫色
    FileStatus.UNTRACKED -> Color(0xFF8BC34A) // 浅绿色
    else -> MaterialTheme.colorScheme.onSurface
}
