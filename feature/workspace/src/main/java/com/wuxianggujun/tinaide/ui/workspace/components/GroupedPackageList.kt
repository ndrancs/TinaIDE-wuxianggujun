package com.wuxianggujun.tinaide.ui.workspace.components

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.wuxianggujun.tinaide.core.i18n.Drawables
import com.wuxianggujun.tinaide.core.i18n.Strings
import com.wuxianggujun.tinaide.core.proot.PRootBootstrap
import com.wuxianggujun.tinaide.ui.compose.components.TinaOverlayPanelSurface
import com.wuxianggujun.tinaide.ui.compose.components.TinaShapes

/**
 * 分组显示的包安装列表
 *
 * 按状态分组：正在安装 > 等待中 > 已完成（可折叠）
 * 这样用户能清楚看到当前进度，已完成的包也不会"消失"
 */
@Composable
fun GroupedPackageInstallList(
    packages: List<PRootBootstrap.PackageInfo>,
    currentPackage: String?,
    installStage: PRootBootstrap.InstallStage,
    modifier: Modifier = Modifier
) {
    // 按状态分组
    val installingPackages = packages.filter {
        it.status == PRootBootstrap.PackageStatus.DOWNLOADING ||
            it.status == PRootBootstrap.PackageStatus.INSTALLING
    }
    val pendingPackages = packages.filter { it.status == PRootBootstrap.PackageStatus.PENDING }
    val completedPackages = packages.filter { it.status == PRootBootstrap.PackageStatus.COMPLETED }
    val failedPackages = packages.filter { it.status == PRootBootstrap.PackageStatus.FAILED }

    // 已完成包的折叠状态
    var completedExpanded by remember { mutableStateOf(false) }

    TinaOverlayPanelSurface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(TinaShapes.ButtonCorner),
        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        tonalElevation = 0.dp,
        shadowElevation = 0.dp
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            // 标题和当前阶段
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = stringResource(Strings.package_list_title),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Medium
                )

                // 当前阶段标签
                val stageText = when (installStage) {
                    PRootBootstrap.InstallStage.PREPARING_RUNTIME -> stringResource(Strings.install_stage_configuring)
                    PRootBootstrap.InstallStage.INSTALLING_DISTRO -> stringResource(Strings.install_stage_downloading)
                    PRootBootstrap.InstallStage.REGISTERING_PROFILE -> stringResource(Strings.install_stage_verifying)
                    PRootBootstrap.InstallStage.COMPLETED -> stringResource(Strings.install_stage_completed)
                }

                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.primaryContainer
                ) {
                    Text(
                        text = stageText,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // 包列表（按状态分组）
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                // 正在安装的包（高优先级，置顶显示）
                if (installingPackages.isNotEmpty()) {
                    item {
                        PackageGroupHeader(
                            icon = Drawables.ic_download_circle,
                            title = stringResource(Strings.package_group_installing),
                            count = installingPackages.size,
                            color = MaterialTheme.colorScheme.primary,
                            expanded = true,
                            onToggle = null // 不可折叠
                        )
                    }
                    items(
                        items = installingPackages,
                        key = { it.name }
                    ) { pkg ->
                        PackageInstallItem(
                            packageInfo = pkg,
                            isCurrentPackage = pkg.matchesPackageName(currentPackage),
                            modifier = Modifier.animateItem()
                        )
                    }
                    item { Spacer(modifier = Modifier.height(4.dp)) }
                }

                // 等待中的包
                if (pendingPackages.isNotEmpty()) {
                    item {
                        PackageGroupHeader(
                            icon = Drawables.ic_clock,
                            title = stringResource(Strings.package_group_pending),
                            count = pendingPackages.size,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            expanded = true,
                            onToggle = null // 不可折叠
                        )
                    }
                    items(
                        items = pendingPackages.take(5), // 只显示前5个
                        key = { it.name }
                    ) { pkg ->
                        PackageInstallItem(
                            packageInfo = pkg,
                            isCurrentPackage = false,
                            modifier = Modifier.animateItem()
                        )
                    }
                    if (pendingPackages.size > 5) {
                        item {
                            Text(
                                text = stringResource(Strings.package_pending_more, pendingPackages.size - 5),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(start = 40.dp, top = 4.dp)
                            )
                        }
                    }
                    item { Spacer(modifier = Modifier.height(4.dp)) }
                }

                // 已完成的包（可折叠）
                if (completedPackages.isNotEmpty()) {
                    item {
                        PackageGroupHeader(
                            icon = Drawables.ic_check,
                            title = stringResource(Strings.package_group_completed),
                            count = completedPackages.size,
                            color = MaterialTheme.colorScheme.primary,
                            expanded = completedExpanded,
                            onToggle = { completedExpanded = !completedExpanded }
                        )
                    }
                    if (completedExpanded) {
                        items(
                            items = completedPackages,
                            key = { it.name }
                        ) { pkg ->
                            PackageInstallItem(
                                packageInfo = pkg,
                                isCurrentPackage = false,
                                modifier = Modifier.animateItem()
                            )
                        }
                    }
                    item { Spacer(modifier = Modifier.height(4.dp)) }
                }

                // 失败的包
                if (failedPackages.isNotEmpty()) {
                    item {
                        PackageGroupHeader(
                            icon = Drawables.ic_close,
                            title = stringResource(Strings.package_group_failed),
                            count = failedPackages.size,
                            color = MaterialTheme.colorScheme.error,
                            expanded = true,
                            onToggle = null // 不可折叠
                        )
                    }
                    items(
                        items = failedPackages,
                        key = { it.name }
                    ) { pkg ->
                        PackageInstallItem(
                            packageInfo = pkg,
                            isCurrentPackage = false,
                            modifier = Modifier.animateItem()
                        )
                    }
                }
            }
        }
    }
}

/**
 * 包分组标题
 */
@Composable
private fun PackageGroupHeader(
    icon: Int,
    title: String,
    count: Int,
    color: Color,
    expanded: Boolean,
    onToggle: (() -> Unit)?,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .then(
                if (onToggle != null) {
                    Modifier.clickable(onClick = onToggle)
                } else {
                    Modifier
                }
            )
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // 图标
        Box(
            modifier = Modifier
                .size(24.dp)
                .clip(CircleShape)
                .background(color.copy(alpha = 0.15f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                painter = rememberWorkspacePainter(icon),
                contentDescription = null,
                modifier = Modifier.size(14.dp),
                tint = color
            )
        }

        // 标题和数量
        Text(
            text = "$title ($count)",
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f)
        )

        // 展开/折叠图标
        if (onToggle != null) {
            Icon(
                painter = rememberWorkspacePainter(Drawables.ic_arrow_down),
                contentDescription = stringResource(
                    if (expanded) {
                        Strings.content_desc_collapse
                    } else {
                        Strings.content_desc_expand
                    }
                ),
                modifier = Modifier
                    .size(20.dp)
                    .rotate(if (expanded) 180f else 0f),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
