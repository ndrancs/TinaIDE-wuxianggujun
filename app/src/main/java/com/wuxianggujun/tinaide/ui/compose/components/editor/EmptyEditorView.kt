package com.wuxianggujun.tinaide.ui.compose.components.editor

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.wuxianggujun.tinaide.core.i18n.Drawables
import com.wuxianggujun.tinaide.core.i18n.Strings
import com.wuxianggujun.tinaide.ui.compose.components.TinaPrimaryButton
import com.wuxianggujun.tinaide.ui.compose.icons.rememberTinaPainter

/**
 * 空编辑器视图
 *
 * 当没有打开任何文件时显示此视图
 */
@Composable
fun EmptyEditorView(
    onOpenFileTree: () -> Unit,
    modifier: Modifier = Modifier,
    onViewRecentProjects: () -> Unit = {}
) {
    Column(
        modifier = modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        // 文件夹图标卡片 + 加号徽章
        Box(
            contentAlignment = Alignment.Center
        ) {
            // 圆角卡片背景
            Surface(
                modifier = Modifier.size(120.dp),
                shape = RoundedCornerShape(24.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
            ) {
                Box(
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        painter = rememberTinaPainter(Drawables.ic_folder_open_outline),
                        contentDescription = null,
                        modifier = Modifier.size(56.dp),
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
            }

            // 右上角的 + 徽章
            Surface(
                modifier = Modifier
                    .size(36.dp)
                    .align(Alignment.TopEnd)
                    .offset(x = 8.dp, y = (-8).dp),
                shape = CircleShape,
                color = MaterialTheme.colorScheme.primary
            ) {
                Box(
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Add,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp),
                        tint = MaterialTheme.colorScheme.onPrimary
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(32.dp))

        // 标题
        Text(
            text = stringResource(Strings.editor_no_file_open),
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onSurface
        )

        Spacer(modifier = Modifier.height(12.dp))

        // 描述文字
        Text(
            text = stringResource(Strings.editor_hint_line1),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = stringResource(Strings.editor_hint_line2),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(modifier = Modifier.height(32.dp))

        // 打开文件树按钮
        TinaPrimaryButton(
            text = stringResource(Strings.open_file_tree),
            onClick = onOpenFileTree,
            modifier = Modifier
                .width(280.dp)
                .height(52.dp),
            icon = rememberTinaPainter(Drawables.ic_folder_open_outline)
        )

        Spacer(modifier = Modifier.height(16.dp))

        // 查看最近打开的项目链接
        Text(
            text = stringResource(Strings.editor_view_recent),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.clickable { onViewRecentProjects() }
        )
    }
}
