package com.wuxianggujun.tinaide.ui.compose.components

import androidx.compose.foundation.layout.RowScope
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import com.wuxianggujun.tinaide.core.i18n.Strings

/**
 * TinaIDE 顶部栏组件库
 *
 * 顶部栏类型：
 * - TinaTopBar: 标准顶部栏
 *
 * 设计规范：
 * - 高度：56dp（系统默认）
 * - 背景色：surface
 * - 标题字体：titleLarge + SemiBold
 * - 返回按钮：左侧，使用 ArrowBack 图标
 * - 操作按钮：右侧，最多 3 个
 */

/**
 * 标准顶部栏
 *
 * @param title 标题文本
 * @param modifier Modifier
 * @param onNavigateBack 返回按钮点击回调，为 null 时不显示返回按钮
 * @param navigationIcon 自定义导航图标，优先级高于 onNavigateBack
 * @param actions 右侧操作按钮
 * @param containerColor 背景色
 * @param titleContentColor 标题颜色
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TinaTopBar(
    title: String,
    modifier: Modifier = Modifier,
    onNavigateBack: (() -> Unit)? = null,
    navigationIcon: @Composable (() -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {},
    containerColor: Color = MaterialTheme.colorScheme.surface,
    titleContentColor: Color = MaterialTheme.colorScheme.onSurface
) {
    TopAppBar(
        title = {
            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        },
        modifier = modifier,
        navigationIcon = {
            when {
                navigationIcon != null -> navigationIcon()
                onNavigateBack != null -> {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(Strings.content_desc_navigate_back)
                        )
                    }
                }
            }
        },
        actions = actions,
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = containerColor,
            titleContentColor = titleContentColor,
            navigationIconContentColor = titleContentColor,
            actionIconContentColor = titleContentColor
        )
    )
}

/**
 * 带自定义标题内容的顶部栏
 *
 * @param title 标题 Composable
 * @param modifier Modifier
 * @param onNavigateBack 返回按钮点击回调
 * @param navigationIcon 自定义导航图标
 * @param actions 右侧操作按钮
 * @param containerColor 背景色
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TinaTopBar(
    title: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    onNavigateBack: (() -> Unit)? = null,
    navigationIcon: @Composable (() -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {},
    containerColor: Color = MaterialTheme.colorScheme.surface
) {
    TopAppBar(
        title = title,
        modifier = modifier,
        navigationIcon = {
            when {
                navigationIcon != null -> navigationIcon()
                onNavigateBack != null -> {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(Strings.content_desc_navigate_back)
                        )
                    }
                }
            }
        },
        actions = actions,
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = containerColor
        )
    )
}
