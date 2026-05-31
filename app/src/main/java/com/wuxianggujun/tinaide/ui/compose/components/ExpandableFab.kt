package com.wuxianggujun.tinaide.ui.compose.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.outlined.CloudDownload
import androidx.compose.material.icons.outlined.CreateNewFolder
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.FloatingActionButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.wuxianggujun.tinaide.core.i18n.Strings
import com.wuxianggujun.tinaide.tutorial.spotlight.SpotlightTargets
import com.wuxianggujun.tinaide.tutorial.spotlight.spotlightTarget

/**
 * 可展开的 FAB 菜单
 * 设计图样式：点击主 FAB 展开显示"从本地导入"、"从 Git 导入"和"新建项目"三个选项
 */
@Composable
fun TinaExpandableFab(
    isExpanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    rotation: Float,
    onNewProject: () -> Unit,
    onImportFromGit: () -> Unit,
    onImportFromLocal: () -> Unit,
    modifier: Modifier = Modifier,
    /** Spotlight 引导目标 ID */
    spotlightTargetId: String? = SpotlightTargets.FAB_PROJECT_ACTIONS
) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.End,
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        val menuEnter =
            fadeIn(animationSpec = tween(durationMillis = 120)) +
                slideInVertically(
                    animationSpec = tween(durationMillis = 220, easing = FastOutSlowInEasing),
                    initialOffsetY = { it / 3 }
                ) +
                scaleIn(
                    animationSpec = tween(durationMillis = 220, easing = FastOutSlowInEasing),
                    initialScale = 0.92f
                )

        val menuExit =
            fadeOut(animationSpec = tween(durationMillis = 90)) +
                slideOutVertically(
                    animationSpec = tween(durationMillis = 180, easing = FastOutLinearInEasing),
                    targetOffsetY = { it / 3 }
                ) +
                scaleOut(
                    animationSpec = tween(durationMillis = 180, easing = FastOutLinearInEasing),
                    targetScale = 0.92f
                )

        // 展开的菜单项
        AnimatedVisibility(
            visible = isExpanded,
            enter = menuEnter,
            exit = menuExit
        ) {
            Column(
                horizontalAlignment = Alignment.End,
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // 从本地导入
                TinaFabMenuItem(
                    label = stringResource(Strings.action_import_from_local),
                    icon = Icons.Outlined.FolderOpen,
                    onClick = onImportFromLocal
                )

                // 从 Git 导入
                TinaFabMenuItem(
                    label = stringResource(Strings.action_import_from_git),
                    icon = Icons.Outlined.CloudDownload,
                    onClick = onImportFromGit
                )

                // 新建项目
                TinaFabMenuItem(
                    label = stringResource(Strings.action_new_project),
                    icon = Icons.Outlined.CreateNewFolder,
                    onClick = onNewProject,
                    spotlightTargetId = SpotlightTargets.FAB_MENU_NEW_PROJECT
                )
            }
        }

        // 主 FAB 按钮（圆角矩形）
        val fabModifier = if (spotlightTargetId != null) {
            Modifier.spotlightTarget(spotlightTargetId)
        } else {
            Modifier
        }

        FloatingActionButton(
            onClick = { onExpandedChange(!isExpanded) },
            modifier = fabModifier,
            containerColor = MaterialTheme.colorScheme.primary,
            contentColor = MaterialTheme.colorScheme.onPrimary,
            shape = RoundedCornerShape(16.dp)
        ) {
            Icon(
                // 使用"+"旋转成"×"，视觉更连贯
                imageVector = Icons.Default.Add,
                contentDescription = stringResource(Strings.content_desc_add_project),
                modifier = Modifier.rotate(rotation)
            )
        }
    }
}

/**
 * FAB 菜单项
 */
@Composable
fun TinaFabMenuItem(
    label: String,
    icon: ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    spotlightTargetId: String? = null
) {
    val menuShape = RoundedCornerShape(24.dp)
    val rowModifier = if (spotlightTargetId != null) {
        modifier.spotlightTarget(spotlightTargetId)
    } else {
        modifier
    }
    TinaOverlayPanelSurface(
        modifier = rowModifier
            .clip(menuShape)
            .clickable(onClick = onClick),
        shape = menuShape,
        containerColor = MaterialTheme.colorScheme.surface,
        tonalElevation = 3.dp,
        shadowElevation = 3.dp
    ) {
        Row(
            modifier = Modifier.padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelLarge.copy(
                    fontWeight = FontWeight.Medium
                ),
                color = MaterialTheme.colorScheme.onSurface
            )

            FloatingActionButton(
                onClick = onClick,
                modifier = Modifier.size(40.dp),
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
                shape = RoundedCornerShape(12.dp),
                elevation = FloatingActionButtonDefaults.elevation(0.dp, 0.dp, 0.dp, 0.dp)
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp)
                )
            }
        }
    }
}
