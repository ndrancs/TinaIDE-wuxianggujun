package com.wuxianggujun.tinaide.ui.projectlist

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.wuxianggujun.tinaide.core.common.simplifyPath
import com.wuxianggujun.tinaide.core.i18n.Strings
import com.wuxianggujun.tinaide.project.ProjectBuildSystem
import com.wuxianggujun.tinaide.project.ProjectListItem
import com.wuxianggujun.tinaide.ui.compose.components.ProjectIcon
import com.wuxianggujun.tinaide.ui.compose.components.TinaDropdownMenu
import com.wuxianggujun.tinaide.ui.compose.components.TinaDropdownMenuDangerItem
import com.wuxianggujun.tinaide.ui.compose.components.TinaDropdownMenuDivider
import com.wuxianggujun.tinaide.ui.compose.components.TinaDropdownMenuItem
import com.wuxianggujun.tinaide.ui.compose.components.TinaDropdownMenuSectionHeader
import com.wuxianggujun.tinaide.ui.compose.components.TinaDropdownMenuSectionTitle
import com.wuxianggujun.tinaide.ui.compose.components.TinaSemanticColors
import java.io.File

/**
 * 项目列表卡片 - 符合设计图样式
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ProjectListCard(
    project: ProjectListItem,
    onClick: () -> Unit,
    onAction: (ProjectAction) -> Unit
) {
    val context = LocalContext.current
    var showMenu by remember { mutableStateOf(false) }

    // 判断是否是 Git 项目
    val isGitProject = remember(project.dir) {
        File(project.dir, ".git").exists()
    }

    // 根据构建系统和语言计算要显示的标签
    val projectTags = remember(project.sourceLocation, project.buildSystem, project.primaryLanguage, isGitProject) {
        buildList {
            // 1. 显示项目来源（公有 / 私有）
            ProjectTag.fromSourceLocation(project.sourceLocation)?.let(::add)
            // 2. 如果是 Git 项目，添加 Git 标签
            if (isGitProject) {
                add(ProjectTag.GIT)
            }
            // 3. 根据构建系统添加对应标签（CMAKE / MAKEFILE）
            when (project.buildSystem) {
                ProjectBuildSystem.CMAKE -> add(ProjectTag.CMAKE)
                ProjectBuildSystem.MAKE -> add(ProjectTag.MAKEFILE)
                ProjectBuildSystem.PLUGIN -> add(ProjectTag.PLUGIN)
                else -> { /* 单文件或未知构建系统不显示构建系统标签 */ }
            }
            // 4. 根据主要语言添加语言标签
            val languageTag = project.primaryLanguage?.let { ProjectTag.fromLanguage(it) }
            if (languageTag != null) {
                add(languageTag)
            } else if (project.buildSystem != ProjectBuildSystem.PLUGIN) {
                // 默认显示 C/C++ 标签（兼容旧项目）
                add(ProjectTag.C_CPP)
            }
        }
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = onClick,
                onLongClick = { showMenu = true }
            ),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        border = androidx.compose.foundation.BorderStroke(
            1.dp,
            MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f)
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 项目图标
            ProjectIcon(
                projectName = project.displayName,
                size = 48.dp
            )

            Spacer(Modifier.width(16.dp))

            // 项目信息
            Column(modifier = Modifier.weight(1f)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = project.displayName,
                        style = MaterialTheme.typography.titleMedium.copy(
                            fontWeight = FontWeight.SemiBold
                        ),
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    // 项目标签（可能有多个：Git + 构建系统）
                    projectTags.forEach { tag ->
                        ProjectTagChip(tag = tag)
                    }
                }

                Spacer(Modifier.height(4.dp))

                Text(
                    text = simplifyPath(project.dir.absolutePath, context),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )

                // 如果是 Git 项目，显示分支信息
                if (isGitProject) {
                    Spacer(Modifier.height(4.dp))
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text(
                            text = "<>",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = "_BRANCH",
                            style = MaterialTheme.typography.bodySmall.copy(
                                fontWeight = FontWeight.Medium
                            ),
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = "main",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                } else {
                    // 显示更新时间
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = stringResource(Strings.label_recently_updated),
                        style = MaterialTheme.typography.bodySmall,
                        color = TinaSemanticColors.Project.recentUpdate
                    )
                }
            }

            // 更多操作按钮 + 下拉菜单
            Box {
                IconButton(onClick = { showMenu = true }) {
                    Icon(
                        imageVector = Icons.Default.MoreVert,
                        contentDescription = stringResource(Strings.content_desc_more_actions),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                // 下拉菜单
                ProjectActionMenu(
                    expanded = showMenu,
                    onDismiss = { showMenu = false },
                    onAction = { action ->
                        showMenu = false
                        onAction(action)
                    }
                )
            }
        }
    }
}

/**
 * 项目操作下拉菜单
 */
@Composable
fun ProjectActionMenu(
    expanded: Boolean,
    onDismiss: () -> Unit,
    onAction: (ProjectAction) -> Unit
) {
    TinaDropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismiss
    ) {
        // 打开项目
        TinaDropdownMenuItem(
            text = { Text(stringResource(Strings.action_open_project)) },
            onClick = { onAction(ProjectAction.OPEN) },
            leadingIcon = {
                Icon(
                    Icons.Outlined.FolderOpen,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )
            }
        )

        // 重命名
        TinaDropdownMenuItem(
            text = { Text(stringResource(Strings.action_rename)) },
            onClick = { onAction(ProjectAction.RENAME) },
            leadingIcon = {
                Icon(
                    Icons.Default.Edit,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        )

        // 导出项目
        TinaDropdownMenuItem(
            text = { Text(stringResource(Strings.action_export_project)) },
            onClick = { onAction(ProjectAction.EXPORT) },
            leadingIcon = {
                Icon(
                    Icons.Default.Share,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        )

        // 项目设置（编辑该项目专属配置，不打开项目）
        TinaDropdownMenuItem(
            text = { Text(stringResource(Strings.action_project_settings)) },
            onClick = { onAction(ProjectAction.SETTINGS) },
            leadingIcon = {
                Icon(
                    Icons.Default.Tune,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        )

        // 项目信息
        TinaDropdownMenuItem(
            text = { Text(stringResource(Strings.action_project_info)) },
            onClick = { onAction(ProjectAction.INFO) },
            leadingIcon = {
                Icon(
                    Icons.Default.Info,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        )

        TinaDropdownMenuDivider()
        TinaDropdownMenuSectionHeader {
            TinaDropdownMenuSectionTitle(
                text = stringResource(Strings.action_delete),
                color = MaterialTheme.colorScheme.error
            )
        }

        // 删除项目
        TinaDropdownMenuDangerItem(
            text = { Text(stringResource(Strings.action_delete_project)) },
            onClick = { onAction(ProjectAction.DELETE) },
            leadingIcon = {
                Icon(
                    Icons.Default.Delete,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error
                )
            }
        )
    }
}
