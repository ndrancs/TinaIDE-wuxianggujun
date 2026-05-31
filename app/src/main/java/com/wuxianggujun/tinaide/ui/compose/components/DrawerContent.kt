package com.wuxianggujun.tinaide.ui.compose.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LocalRippleConfiguration
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.wuxianggujun.tinaide.ai.api.MessageContext
import com.wuxianggujun.tinaide.ai.viewmodel.AiChatViewModel
import com.wuxianggujun.tinaide.core.commands.HostCommandExecutor
import com.wuxianggujun.tinaide.core.git.GitStatus
import com.wuxianggujun.tinaide.core.i18n.Strings
import com.wuxianggujun.tinaide.plugin.PluginManager
import com.wuxianggujun.tinaide.ui.compose.icons.TinaTabIcons
import java.io.File

/**
 * 按功能分组侧滑栏回调，避免调用侧继续膨胀。
 */
internal class DrawerFileCallbacks(
    val onFileClick: (File) -> Unit,
    val onContextAction: (FileContextAction) -> Unit,
    val onAddFileClick: (File?) -> Unit,
)

internal class DrawerGitCallbacks(
    val onRefresh: () -> Unit,
    val onStageAll: () -> Unit,
    val onUnstageAll: () -> Unit = {},
    val onCommitWithMessage: (String) -> Unit = {},
    val onStageFile: (String) -> Unit,
    val onUnstageFile: (String) -> Unit,
    val onDiscardChanges: (String) -> Unit,
    val onFileClick: (String) -> Unit,
    val onShowDiff: (String, Boolean) -> Unit,
    val onInitRepository: () -> Unit = {},
    val onOpenSyncDialog: () -> Unit = {},
    val onOpenRemoteDialog: () -> Unit = {},
)

internal class DrawerAiCallbacks(
    val onInsertCode: (String) -> Unit = {},
    val onGetCurrentFile: () -> MessageContext.CurrentFile? = { null },
    val onGetSelectedCode: () -> MessageContext.SelectedCode? = { null },
    val onOpenSettings: () -> Unit = {},
)

/**
 * 侧滑栏内容组件
 *
 * 包含文件树和 Git 面板的切换
 */
@Composable
internal fun DrawerContent(
    projectName: String,
    fileTreeState: FileTreeState,
    pluginManager: PluginManager,
    fileCallbacks: DrawerFileCallbacks,
    gitStatus: GitStatus,
    gitIsLoading: Boolean,
    gitStatusMap: Map<String, FileGitStatus>,
    gitCallbacks: DrawerGitCallbacks,
    modifier: Modifier = Modifier,
    hostCommandExecutor: HostCommandExecutor? = null,
    aiChatViewModel: AiChatViewModel? = null,
    aiCallbacks: DrawerAiCallbacks = DrawerAiCallbacks()
) {
    var drawerTab by remember { mutableStateOf(DrawerTab.FILES) }

    Column(modifier = modifier.fillMaxSize()) {
        // 头部（AI Tab 使用自己的工具栏，不显示通用头部）
        if (drawerTab != DrawerTab.AI) {
            DrawerHeader(
                drawerTab = drawerTab,
                projectName = projectName,
                gitStatus = gitStatus,
                gitIsLoading = gitIsLoading,
                onAddFileClick = {
                    val targetDir = fileTreeState.selectedDirectoryPath?.let(::File)
                    fileCallbacks.onAddFileClick(targetDir)
                },
                onGitRefresh = gitCallbacks.onRefresh
            )

            HorizontalDivider()
        }

        // 内容区域
        when (drawerTab) {
            DrawerTab.FILES -> {
                FileTree(
                    state = fileTreeState,
                    pluginManager = pluginManager,
                    hostCommandExecutor = hostCommandExecutor,
                    onFileClick = fileCallbacks.onFileClick,
                    onFileLongClick = { /* 长按已由上下文菜单处理 */ },
                    onContextAction = fileCallbacks.onContextAction,
                    gitStatusMap = gitStatusMap,
                    modifier = Modifier.weight(1f)
                )
            }
            DrawerTab.GIT -> {
                DrawerGitPanelContent(
                    status = gitStatus,
                    onStageFile = gitCallbacks.onStageFile,
                    onUnstageFile = gitCallbacks.onUnstageFile,
                    onStageAll = gitCallbacks.onStageAll,
                    onUnstageAll = gitCallbacks.onUnstageAll,
                    onDiscardChanges = gitCallbacks.onDiscardChanges,
                    onFileClick = gitCallbacks.onFileClick,
                    onShowDiff = gitCallbacks.onShowDiff,
                    onCommit = gitCallbacks.onCommitWithMessage,
                    onInitRepository = gitCallbacks.onInitRepository,
                    onOpenSync = gitCallbacks.onOpenSyncDialog,
                    onOpenRemotes = gitCallbacks.onOpenRemoteDialog,
                    modifier = Modifier.weight(1f)
                )
            }
            DrawerTab.AI -> {
                if (aiChatViewModel != null) {
                    DrawerAiPanel(
                        viewModel = aiChatViewModel,
                        onInsertCode = aiCallbacks.onInsertCode,
                        onGetCurrentFile = aiCallbacks.onGetCurrentFile,
                        onGetSelectedCode = aiCallbacks.onGetSelectedCode,
                        onOpenSettings = aiCallbacks.onOpenSettings,
                        modifier = Modifier.weight(1f)
                    )
                } else {
                    // ViewModel 未注入时显示带设置入口的占位界面
                    AiPlaceholderContent(
                        onOpenSettings = aiCallbacks.onOpenSettings,
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }

        HorizontalDivider()

        // 底部 Tab 切换
        DrawerTabBar(
            selectedTab = drawerTab,
            onTabSelected = { tab ->
                drawerTab = tab
            }
        )
    }
}

/**
 * AI 未配置时的占位界面
 * 包含顶部工具栏和设置入口，保持与 DrawerAiPanel 一致的布局
 */
@Composable
private fun AiPlaceholderContent(
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier.fillMaxSize()) {
        // 顶部工具栏（与 DrawerAiPanel 的 AiPanelToolbar 保持一致）
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp)
                .padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = stringResource(Strings.ai_assistant),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f)
            )

            IconButton(onClick = onOpenSettings) {
                Icon(
                    imageVector = Icons.Outlined.Settings,
                    contentDescription = stringResource(Strings.ai_settings),
                    modifier = Modifier.size(20.dp)
                )
            }
        }

        HorizontalDivider()

        // 占位内容
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            contentAlignment = Alignment.Center
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Text(
                    text = stringResource(Strings.ai_error_no_config),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                OutlinedButton(onClick = onOpenSettings) {
                    Icon(
                        imageVector = Icons.Outlined.Settings,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(text = stringResource(Strings.ai_settings))
                }
            }
        }
    }
}

@Composable
private fun DrawerHeader(
    drawerTab: DrawerTab,
    projectName: String,
    gitStatus: GitStatus,
    gitIsLoading: Boolean,
    onAddFileClick: () -> Unit,
    onGitRefresh: () -> Unit
) {
    val actionIconButtonColors = IconButtonDefaults.iconButtonColors(
        contentColor = MaterialTheme.colorScheme.onSurface
    )

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(56.dp)
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // 项目图标（仅在文件标签页显示）
        if (drawerTab == DrawerTab.FILES) {
            ProjectIcon(
                projectName = projectName,
                size = 36.dp
            )
            Spacer(modifier = Modifier.width(12.dp))
        }

        // 标题
        val headerTitle = when (drawerTab) {
            DrawerTab.FILES -> projectName
            DrawerTab.GIT -> stringResource(Strings.drawer_title_source_control)
            DrawerTab.AI -> stringResource(Strings.drawer_title_ai)
        }
        Text(
            text = headerTitle,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f)
        )

        // 操作按钮
        when (drawerTab) {
            DrawerTab.FILES -> {
                IconButton(
                    onClick = onAddFileClick,
                    colors = actionIconButtonColors
                ) {
                    Icon(
                        imageVector = Icons.Default.Add,
                        contentDescription = stringResource(Strings.content_desc_add_file)
                    )
                }
            }
            DrawerTab.GIT -> {
                // Git 标签页只显示刷新按钮，其他操作在面板内部
                IconButton(
                    onClick = onGitRefresh,
                    enabled = !gitIsLoading,
                    colors = actionIconButtonColors
                ) {
                    if (gitIsLoading) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            strokeWidth = 2.dp
                        )
                    } else {
                        Icon(
                            imageVector = Icons.Default.Refresh,
                            contentDescription = stringResource(Strings.menu_refresh),
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            }
            DrawerTab.AI -> {
                // AI 标签页的操作按钮在 DrawerAiPanel 内部工具栏处理
            }
        }
    }
}

/**
 * Material Design 3 风格的底部导航栏
 *
 * 使用 NavigationBar 组件替代原有的 Surface + Row 实现，
 * 具有以下优势：
 * - 使用 MD3 的 pill-shaped indicator（药丸形指示器）
 * - 自动处理系统导航栏的安全区域
 * - 更现代的视觉效果，与系统小白条不冲突
 */
@Composable
private fun DrawerTabBar(
    selectedTab: DrawerTab,
    onTabSelected: (DrawerTab) -> Unit
) {
    val selectedIconBackgroundShape = RoundedCornerShape(12.dp)
    // 禁用 tab item 的 ripple / pressed state layer（点击“黑影”）
    CompositionLocalProvider(LocalRippleConfiguration provides null) {
        NavigationBar(
            modifier = Modifier.fillMaxWidth(),
            containerColor = MaterialTheme.colorScheme.surface,
            contentColor = MaterialTheme.colorScheme.onSurface,
            tonalElevation = 0.dp,
            windowInsets = WindowInsets(0) // 不自动添加 insets，由外部控制
        ) {
            DrawerTab.entries.forEach { tab ->
                val selected = selectedTab == tab
                val icon = when (tab) {
                    DrawerTab.FILES -> TinaTabIcons.Files
                    DrawerTab.GIT -> TinaTabIcons.Git
                    DrawerTab.AI -> TinaTabIcons.Ai
                }
                val tabTitle = stringResource(tab.titleRes)

                NavigationBarItem(
                    selected = selected,
                    onClick = { onTabSelected(tab) },
                    icon = {
                        Box(
                            modifier = Modifier
                                .size(44.dp)
                                .clip(selectedIconBackgroundShape)
                                .background(
                                    if (selected) {
                                        MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.75f)
                                    } else {
                                        Color.Transparent
                                    }
                                ),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = icon,
                                contentDescription = tabTitle,
                                modifier = Modifier.size(24.dp)
                            )
                        }
                    },
                    label = {
                        Text(
                            text = tabTitle,
                            style = MaterialTheme.typography.labelMedium
                        )
                    },
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = MaterialTheme.colorScheme.onSecondaryContainer,
                        selectedTextColor = MaterialTheme.colorScheme.onSurface,
                        // 隐藏默认 pill indicator，使用上面自绘的“正方形圆角”背景
                        indicatorColor = Color.Transparent,
                        unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                )
            }
        }
    }
}
