package com.wuxianggujun.tinaide.ui.compose.screens.packages
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.wuxianggujun.tinaide.core.i18n.Strings
import com.wuxianggujun.tinaide.core.packages.InstalledPackageMetadata
import com.wuxianggujun.tinaide.core.packages.PackageInstallPlan
import com.wuxianggujun.tinaide.core.packages.PackageInstallPlanItem
import com.wuxianggujun.tinaide.core.packages.model.*
import com.wuxianggujun.tinaide.ui.compose.components.PluginCardSkeleton
import com.wuxianggujun.tinaide.ui.compose.components.TinaAlertDialog
import com.wuxianggujun.tinaide.ui.compose.components.TinaBackHandlers
import com.wuxianggujun.tinaide.ui.compose.components.TinaDangerButton
import com.wuxianggujun.tinaide.ui.compose.components.TinaDialogCard
import com.wuxianggujun.tinaide.ui.compose.components.TinaDialogContentColumn
import com.wuxianggujun.tinaide.ui.compose.components.TinaDialogMessageCard
import com.wuxianggujun.tinaide.ui.compose.components.TinaDialogTitleText
import com.wuxianggujun.tinaide.ui.compose.components.TinaInfoDialog
import com.wuxianggujun.tinaide.ui.compose.components.TinaOutlinedButton
import com.wuxianggujun.tinaide.ui.compose.components.TinaPrimaryButton
import com.wuxianggujun.tinaide.ui.compose.components.TinaSearchField
import com.wuxianggujun.tinaide.ui.compose.components.TinaShapes
import com.wuxianggujun.tinaide.ui.compose.components.TinaSpacing
import com.wuxianggujun.tinaide.ui.compose.components.TinaTextButton
import com.wuxianggujun.tinaide.ui.compose.components.TinaTopBar
import com.wuxianggujun.tinaide.ui.compose.components.tinaBackAction
import org.koin.androidx.compose.koinViewModel

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun PackageManagerScreen(
    onNavigateBack: () -> Unit,
    initialSearchQuery: String? = null
) {
    val viewModel: PackageManagerViewModel = koinViewModel()
    val uiState by viewModel.uiState.collectAsState()
    val filterState by viewModel.filterState.collectAsState()
    val dialogState by viewModel.dialogState.collectAsState()

    LaunchedEffect(initialSearchQuery) {
        initialSearchQuery
            ?.takeUnless { it.isBlank() }
            ?.let(viewModel::updateSearchQuery)
    }

    // 处理系统返回键：优先处理内部子界面的返回
    TinaBackHandlers(
        tinaBackAction(enabled = uiState.currentDetailPackage != null) {
            viewModel.closePackageDetails()
        }
    )

    if (uiState.currentDetailPackage != null) {
        val pkg = uiState.currentDetailPackage!!
        PackageDetailScreen(
            pkg = pkg,
            installedMetadata = uiState.installedMetadata[pkg.id],
            installState = uiState.installStates[pkg.id] ?: PackageInstallState(),
            installEvent = uiState.currentInstallEvent,
            onInstall = { platform -> viewModel.installPackage(pkg.id, platform) },
            onUninstall = { platform -> viewModel.requestUninstall(pkg.id, platform) },
            onNavigateBack = viewModel::closePackageDetails,
            onDependencyClick = viewModel::navigateToDependency
        )
    } else {
        Scaffold(
            topBar = {
                if (uiState.isSelectionMode) {
                    TinaTopBar(
                        title = stringResource(Strings.pkg_manager_selected, uiState.selectedPackageIds.size),
                        navigationIcon = {
                            IconButton(onClick = viewModel::toggleSelectionMode) {
                                Icon(Icons.Default.Close, contentDescription = stringResource(Strings.btn_cancel))
                            }
                        },
                        actions = {
                            IconButton(onClick = viewModel::selectAll) {
                                Icon(Icons.Default.SelectAll, contentDescription = stringResource(Strings.pkg_manager_select))
                            }
                            IconButton(onClick = viewModel::clearSelection) {
                                Icon(Icons.Default.Deselect, contentDescription = stringResource(Strings.btn_deselect_all))
                            }
                        }
                    )
                } else {
                    TinaTopBar(
                        title = stringResource(Strings.pkg_manager_title),
                        onNavigateBack = onNavigateBack,
                        actions = {
                            if (uiState.availableUpdates.isNotEmpty()) {
                                Badge(
                                    containerColor = MaterialTheme.colorScheme.error,
                                    contentColor = MaterialTheme.colorScheme.onError
                                ) {
                                    Text("${uiState.availableUpdates.size}")
                                }
                                TextButton(onClick = viewModel::updateAllPackages) {
                                    Text(stringResource(Strings.pkg_manager_update_all))
                                }
                            }
                            IconButton(onClick = viewModel::checkForUpdates) {
                                Icon(Icons.Default.Update, contentDescription = stringResource(Strings.pkg_manager_check_updates))
                            }
                            IconButton(onClick = viewModel::refreshPackages) {
                                Icon(Icons.Default.Refresh, contentDescription = stringResource(Strings.pkg_manager_refresh))
                            }
                            IconButton(onClick = viewModel::toggleSelectionMode) {
                                Icon(Icons.Default.Checklist, contentDescription = stringResource(Strings.pkg_manager_select))
                            }
                        }
                    )
                }
            },
            bottomBar = {
                AnimatedVisibility(visible = uiState.isSelectionMode && uiState.selectedPackageIds.isNotEmpty()) {
                    BottomAppBar {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = TinaSpacing.xl),
                            verticalArrangement = Arrangement.spacedBy(TinaSpacing.sm)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(TinaSpacing.md)
                            ) {
                                TinaPrimaryButton(
                                    text = stringResource(Strings.pkg_manager_install_linux),
                                    onClick = { viewModel.batchInstall(Platform.LINUX) },
                                    modifier = Modifier.weight(1f)
                                )
                                TinaPrimaryButton(
                                    text = stringResource(Strings.pkg_manager_install_android),
                                    onClick = { viewModel.batchInstall(Platform.ANDROID) },
                                    modifier = Modifier.weight(1f)
                                )
                            }
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(TinaSpacing.md)
                            ) {
                                TinaDangerButton(
                                    text = stringResource(Strings.pkg_manager_uninstall_linux),
                                    onClick = { viewModel.batchUninstall(Platform.LINUX) },
                                    modifier = Modifier.weight(1f)
                                )
                                TinaDangerButton(
                                    text = stringResource(Strings.pkg_manager_uninstall_android),
                                    onClick = { viewModel.batchUninstall(Platform.ANDROID) },
                                    modifier = Modifier.weight(1f)
                                )
                            }
                        }
                    }
                }
            }
        ) { padding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
            ) {
                SearchBar(
                    query = filterState.searchQuery,
                    onQueryChange = viewModel::updateSearchQuery,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = TinaSpacing.xl, vertical = TinaSpacing.md)
                )

                if (uiState.isLoading) {
                    // 显示骨架屏
                    LazyColumn(
                        contentPadding = PaddingValues(TinaSpacing.xl),
                        verticalArrangement = Arrangement.spacedBy(TinaSpacing.lg),
                        modifier = Modifier.fillMaxSize()
                    ) {
                        items(5) {
                            PluginCardSkeleton()
                        }
                    }
                } else if (uiState.error != null) {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                text = uiState.error ?: stringResource(Strings.pkg_manager_error_unknown),
                                color = MaterialTheme.colorScheme.error
                            )
                            Spacer(modifier = Modifier.height(TinaSpacing.xl))
                            TinaPrimaryButton(
                                text = stringResource(Strings.pkg_manager_retry),
                                onClick = viewModel::loadPackages
                            )
                        }
                    }
                } else if (uiState.filteredPackages.isEmpty()) {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = stringResource(Strings.pkg_manager_no_available_packages),
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(TinaSpacing.xl),
                        verticalArrangement = Arrangement.spacedBy(TinaSpacing.lg)
                    ) {
                        items(uiState.filteredPackages, key = { it.id }) { pkg ->
                            PackageCard(
                                pkg = pkg,
                                installedMetadata = uiState.installedMetadata[pkg.id],
                                installState = uiState.installStates[pkg.id] ?: PackageInstallState(),
                                isSelectionMode = uiState.isSelectionMode,
                                isSelected = pkg.id in uiState.selectedPackageIds,
                                onInstallLinux = { viewModel.installPackage(pkg.id, Platform.LINUX) },
                                onInstallAndroid = { viewModel.installPackage(pkg.id, Platform.ANDROID) },
                                onUninstallLinux = { viewModel.requestUninstall(pkg.id, Platform.LINUX) },
                                onUninstallAndroid = { viewModel.requestUninstall(pkg.id, Platform.ANDROID) },
                                onClick = {
                                    if (uiState.isSelectionMode) {
                                        viewModel.togglePackageSelection(pkg.id)
                                    } else {
                                        viewModel.showPackageDetails(pkg.id)
                                    }
                                },
                                onLongClick = {
                                    if (!uiState.isSelectionMode) {
                                        viewModel.toggleSelectionMode()
                                        viewModel.togglePackageSelection(pkg.id)
                                    }
                                }
                            )
                        }
                    }
                }
            }
        }
    } // end else

    dialogState?.let { state ->
        when (state) {
            is PackageDialogState.InstallConfirm -> {
                InstallConfirmDialog(
                    packageInfo = state.packageInfo,
                    platform = state.platform,
                    plan = state.plan,
                    onConfirm = { viewModel.confirmInstall(state.packageId, state.platform) },
                    onDismiss = viewModel::dismissDialog
                )
            }
            is PackageDialogState.Installing -> {
                InstallProgressDialog(
                    packageName = state.packageName,
                    platform = state.platform,
                    event = state.event,
                    onCancel = viewModel::cancelInstall,
                    onDismiss = {}
                )
            }
            is PackageDialogState.InstallComplete -> {
                InstallCompleteDialog(
                    result = state.result,
                    onDismiss = viewModel::dismissDialog
                )
            }
            is PackageDialogState.UninstallConfirm -> {
                UninstallConfirmDialog(
                    packageInfo = state.packageInfo,
                    platform = state.platform,
                    dependentPackages = state.dependentPackages,
                    onConfirm = { viewModel.confirmUninstall(state.packageId, state.platform) },
                    onDismiss = viewModel::dismissDialog
                )
            }
            is PackageDialogState.UninstallComplete -> {
                UninstallCompleteDialog(
                    result = state.result,
                    onDismiss = viewModel::dismissDialog
                )
            }
            is PackageDialogState.PackageDetails -> {
                PackageDetailsDialog(
                    pkg = state.packageInfo,
                    installedMetadata = uiState.installedMetadata[state.packageInfo.id],
                    onDismiss = viewModel::dismissDialog
                )
            }
            is PackageDialogState.BatchInstallConfirm -> {
                BatchPlanConfirmDialog(
                    title = stringResource(Strings.pkg_manager_batch_install_confirm_title),
                    message = stringResource(
                        Strings.pkg_manager_batch_install_confirm_message,
                        state.packageIds.size,
                        platformDisplayName(state.platform)
                    ),
                    plans = state.plans,
                    confirmText = stringResource(Strings.pkg_manager_install_confirm_button),
                    onConfirm = { viewModel.confirmBatchInstall() },
                    onDismiss = viewModel::dismissDialog
                )
            }
            is PackageDialogState.BatchInstalling -> {
                BatchInstallProgressDialog(
                    currentIndex = state.currentIndex,
                    totalCount = state.totalCount,
                    currentPackageName = state.currentPackageName,
                    platform = state.platform,
                    event = state.event
                )
            }
            is PackageDialogState.BatchInstallComplete -> {
                TinaInfoDialog(
                    title = stringResource(Strings.pkg_manager_batch_complete_title),
                    message = stringResource(
                        Strings.pkg_manager_batch_complete_msg,
                        state.totalCount,
                        platformDisplayName(state.platform)
                    ),
                    confirmText = stringResource(Strings.btn_confirm),
                    onDismiss = viewModel::dismissDialog
                )
            }
            is PackageDialogState.BatchPlatformSelect -> {}
            is PackageDialogState.BatchUpdating -> {
                BatchUpdateProgressDialog(
                    currentIndex = state.currentIndex,
                    totalCount = state.totalCount,
                    currentPackageName = state.currentPackageName,
                    event = state.event
                )
            }
            is PackageDialogState.BatchUpdateConfirm -> {
                BatchPlanConfirmDialog(
                    title = stringResource(Strings.pkg_manager_batch_update_confirm_title),
                    message = stringResource(
                        Strings.pkg_manager_batch_update_confirm_message,
                        state.updates.size
                    ),
                    plans = state.plans,
                    confirmText = stringResource(Strings.pkg_manager_update_all),
                    onConfirm = { viewModel.confirmBatchUpdate() },
                    onDismiss = viewModel::dismissDialog
                )
            }
            is PackageDialogState.BatchUpdateComplete -> {
                TinaInfoDialog(
                    title = stringResource(Strings.pkg_manager_update_complete_title),
                    message = stringResource(Strings.pkg_manager_update_complete_msg, state.totalCount),
                    confirmText = stringResource(Strings.btn_confirm),
                    onDismiss = viewModel::dismissDialog
                )
            }
        }
    }
}

@Composable
private fun SearchBar(
    query: String,
    onQueryChange: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    TinaSearchField(
        value = query,
        onValueChange = onQueryChange,
        placeholder = stringResource(Strings.pkg_manager_search_hint),
        modifier = modifier
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun PackageCard(
    pkg: GUIPackage,
    installedMetadata: InstalledPackageMetadata? = null,
    installState: PackageInstallState,
    isSelectionMode: Boolean = false,
    isSelected: Boolean = false,
    onInstallLinux: () -> Unit,
    onInstallAndroid: () -> Unit,
    onUninstallLinux: () -> Unit,
    onUninstallAndroid: () -> Unit,
    onClick: () -> Unit,
    onLongClick: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val packageVersion = installedMetadata?.version ?: pkg.android?.version ?: pkg.linux?.version
    val upstreamVersion = installedMetadata?.upstreamVersion

    Card(
        modifier = modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick
            ),
        colors = if (isSelected) {
            CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
        } else {
            CardDefaults.cardColors()
        }
    ) {
        Row(
            modifier = Modifier.padding(TinaSpacing.xl),
            verticalAlignment = Alignment.Top
        ) {
            if (isSelectionMode) {
                Checkbox(
                    checked = isSelected,
                    onCheckedChange = { onClick() },
                    modifier = Modifier.padding(end = TinaSpacing.lg)
                )
            }

            Column(modifier = Modifier.weight(1f)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(TinaSpacing.md)
                    ) {
                        Text(
                            text = pkg.name,
                            style = MaterialTheme.typography.titleMedium
                        )
                        if (pkg.isBundled) {
                            Text(
                                text = stringResource(Strings.pkg_manager_bundled),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.tertiary,
                                modifier = Modifier
                                    .background(
                                        MaterialTheme.colorScheme.tertiaryContainer,
                                        RoundedCornerShape(TinaShapes.ExtraSmallCorner)
                                    )
                                    .padding(horizontal = TinaSpacing.sm, vertical = TinaSpacing.xxs)
                            )
                        }
                    }
                    pkg.linux?.version?.let { version ->
                        Text(
                            text = "v${packageVersion ?: version}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    if (pkg.linux?.version == null) {
                        packageVersion?.let { version ->
                            Text(
                                text = "v$version",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }

                pkg.description?.let { desc ->
                    Spacer(modifier = Modifier.height(TinaSpacing.xs))
                    Text(
                        text = desc,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                installedMetadata?.let { metadata ->
                    Spacer(modifier = Modifier.height(TinaSpacing.xs))
                    val versionSummary = buildList {
                        add(stringResource(Strings.pkg_manager_card_package_version, metadata.version))
                        metadata.upstreamVersion?.let { add(stringResource(Strings.pkg_manager_card_library_version, it)) }
                    }.joinToString("  ·  ")
                    Text(
                        text = versionSummary,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                if (!isSelectionMode) {
                    Spacer(modifier = Modifier.height(TinaSpacing.lg))

                    if (pkg.linux != null) {
                        PlatformRow(
                            platformLabel = platformDisplayName(Platform.LINUX),
                            platformIcon = "\uD83D\uDC27",
                            state = installState.linux,
                            onInstall = onInstallLinux,
                            onUninstall = onUninstallLinux
                        )
                    }

                    if (pkg.android != null) {
                        Spacer(modifier = Modifier.height(TinaSpacing.md))
                        PlatformRow(
                            platformLabel = platformDisplayName(Platform.ANDROID),
                            platformIcon = "\uD83E\uDD16",
                            state = installState.android,
                            onInstall = onInstallAndroid,
                            onUninstall = onUninstallAndroid
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun PlatformRow(
    platformLabel: String,
    platformIcon: String,
    state: PlatformInstallState,
    onInstall: () -> Unit,
    onUninstall: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(text = platformIcon)
            Spacer(modifier = Modifier.width(TinaSpacing.md))
            Text(text = platformLabel, style = MaterialTheme.typography.bodyMedium)
        }

        when (state) {
            is PlatformInstallState.NotInstalled -> {
                TinaPrimaryButton(
                    text = stringResource(Strings.pkg_manager_btn_install),
                    onClick = onInstall,
                    contentPadding = PaddingValues(horizontal = TinaSpacing.xl, vertical = TinaSpacing.xs)
                )
            }
            is PlatformInstallState.Installed -> {
                TinaOutlinedButton(
                    text = stringResource(Strings.pkg_manager_btn_installed),
                    onClick = onUninstall,
                    leadingIcon = Icons.Default.Check,
                    contentPadding = PaddingValues(horizontal = TinaSpacing.xl, vertical = TinaSpacing.xs)
                )
            }
            is PlatformInstallState.Installing -> {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                    Spacer(modifier = Modifier.width(TinaSpacing.md))
                    Text("${(state.progress * 100).toInt()}%", style = MaterialTheme.typography.labelMedium)
                }
            }
            is PlatformInstallState.UpdateAvailable -> {
                TinaPrimaryButton(
                    text = stringResource(Strings.pkg_manager_btn_update),
                    onClick = onInstall,
                    contentPadding = PaddingValues(horizontal = TinaSpacing.xl, vertical = TinaSpacing.xs)
                )
            }
        }
    }
}

@Composable
private fun InstallConfirmDialog(
    packageInfo: GUIPackage,
    platform: Platform,
    plan: PackageInstallPlan,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    val dependenciesToInstall = plan.packages.filterNot { it.isRoot }
    TinaAlertDialog(
        onDismissRequest = onDismiss,
        title = { TinaDialogTitleText(stringResource(Strings.pkg_manager_install_confirm_title, packageInfo.name)) },
        text = {
            TinaDialogContentColumn {
                TinaDialogMessageCard(
                    message = stringResource(
                        Strings.pkg_manager_install_confirm_message,
                        platformDisplayName(platform),
                        packageInfo.name
                    )
                )
                if (dependenciesToInstall.isNotEmpty()) {
                    TinaDialogCard(
                        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.42f),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = stringResource(Strings.pkg_manager_install_confirm_dependencies_title),
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                            style = MaterialTheme.typography.bodyMedium
                        )
                        dependenciesToInstall.forEach { dependency ->
                            Text(
                                text = "\u2022 " + stringResource(
                                    Strings.pkg_manager_install_confirm_dependency_item,
                                    dependency.packageName,
                                    dependency.version
                                ),
                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TinaPrimaryButton(
                text = stringResource(Strings.pkg_manager_install_confirm_button),
                onClick = onConfirm
            )
        },
        dismissButton = {
            TinaTextButton(
                text = stringResource(Strings.btn_cancel),
                onClick = onDismiss
            )
        }
    )
}

@Composable
private fun BatchPlanConfirmDialog(
    title: String,
    message: String,
    plans: List<PackageInstallPlan>,
    confirmText: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    val dependenciesToInstall = remember(plans) {
        collectBatchPlanDependencies(plans)
    }
    TinaAlertDialog(
        onDismissRequest = onDismiss,
        title = { TinaDialogTitleText(title) },
        text = {
            TinaDialogContentColumn {
                TinaDialogMessageCard(message = message)
                if (dependenciesToInstall.isNotEmpty()) {
                    TinaDialogCard(
                        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.42f),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = stringResource(Strings.pkg_manager_install_confirm_dependencies_title),
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                            style = MaterialTheme.typography.bodyMedium
                        )
                        dependenciesToInstall.forEach { dependency ->
                            Text(
                                text = "\u2022 " + stringResource(
                                    Strings.pkg_manager_install_confirm_dependency_item,
                                    dependency.packageName,
                                    dependency.version
                                ),
                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TinaPrimaryButton(
                text = confirmText,
                onClick = onConfirm
            )
        },
        dismissButton = {
            TinaTextButton(
                text = stringResource(Strings.btn_cancel),
                onClick = onDismiss
            )
        }
    )
}

private fun collectBatchPlanDependencies(plans: List<PackageInstallPlan>): List<PackageInstallPlanItem> = plans.asSequence()
    .flatMap { it.packages.asSequence() }
    .filterNot { it.isRoot }
    .filterNot { it.isAlreadyInstalled }
    .distinctBy { it.packageId }
    .sortedBy { it.packageName.lowercase() }
    .toList()

@Composable
private fun platformDisplayName(platform: Platform): String = when (platform) {
    Platform.LINUX -> stringResource(Strings.pkg_manager_platform_linux)
    Platform.ANDROID -> stringResource(Strings.pkg_manager_platform_android)
}

@Composable
private fun InstallProgressDialog(
    packageName: String,
    platform: Platform,
    event: InstallProgressEvent,
    onCancel: () -> Unit,
    onDismiss: () -> Unit
) {
    TinaAlertDialog(
        onDismissRequest = {},
        title = { TinaDialogTitleText(stringResource(Strings.pkg_manager_installing_title, packageName)) },
        text = {
            TinaDialogContentColumn {
                TinaDialogCard(verticalArrangement = Arrangement.spacedBy(TinaSpacing.md)) {
                    when (event) {
                        is InstallProgressEvent.Preparing -> {
                            Text(stringResource(Strings.pkg_manager_progress_preparing, event.message))
                        }
                        is InstallProgressEvent.Downloading -> {
                            LinearProgressIndicator(
                                progress = { event.progress },
                                modifier = Modifier.fillMaxWidth()
                            )
                            Text(
                                stringResource(
                                    Strings.pkg_manager_progress_downloading,
                                    (event.progress * 100).toInt()
                                )
                            )
                        }
                        is InstallProgressEvent.Verifying -> {
                            Text(stringResource(Strings.pkg_manager_progress_verifying, event.message))
                        }
                        is InstallProgressEvent.Extracting -> {
                            LinearProgressIndicator(
                                progress = { event.progress },
                                modifier = Modifier.fillMaxWidth()
                            )
                            Text(stringResource(Strings.pkg_manager_progress_extracting))
                        }
                        is InstallProgressEvent.Installing -> {
                            Text(stringResource(Strings.pkg_manager_progress_installing, event.message))
                        }
                        is InstallProgressEvent.Completed -> {
                            Text(stringResource(Strings.pkg_manager_progress_completed))
                        }
                        is InstallProgressEvent.Failed -> {
                            Text(
                                stringResource(
                                    Strings.pkg_manager_progress_failed,
                                    event.error.toDisplayMessage()
                                ),
                                color = MaterialTheme.colorScheme.error
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            if (event is InstallProgressEvent.Completed || event is InstallProgressEvent.Failed) {
                TinaTextButton(
                    text = stringResource(Strings.btn_close),
                    onClick = onDismiss
                )
            } else {
                // 下载/安装进行中：允许取消（取消协程会触发 OkHttp Call.cancel 立即断开连接）。
                TinaTextButton(
                    text = stringResource(Strings.btn_cancel_install),
                    onClick = onCancel
                )
            }
        }
    )
}

@Composable
private fun InstallCompleteDialog(
    result: InstallResult,
    onDismiss: () -> Unit
) {
    when (result) {
        is InstallResult.Success -> {
            // 安装成功：提示用户重新打开文件
            TinaAlertDialog(
                onDismissRequest = onDismiss,
                title = { TinaDialogTitleText(stringResource(Strings.pkg_manager_install_complete)) },
                text = {
                    TinaDialogContentColumn {
                        TinaDialogMessageCard(
                            message = stringResource(
                                Strings.pkg_manager_install_success_msg,
                                result.packageId,
                                result.version
                            )
                        )
                        TinaDialogMessageCard(
                            message = stringResource(Strings.pkg_manager_reopen_file_hint),
                            color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.7f),
                            textColor = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    }
                },
                confirmButton = {
                    TinaPrimaryButton(
                        text = stringResource(Strings.btn_confirm),
                        onClick = onDismiss
                    )
                }
            )
        }
        is InstallResult.Failure -> {
            // 安装失败：只显示错误信息
            TinaInfoDialog(
                title = stringResource(Strings.pkg_manager_install_failed),
                message = result.error.toDisplayMessage(),
                confirmText = stringResource(Strings.btn_confirm),
                onDismiss = onDismiss
            )
        }
    }
}

@Composable
private fun UninstallConfirmDialog(
    packageInfo: GUIPackage,
    platform: Platform,
    dependentPackages: List<String>,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    TinaAlertDialog(
        onDismissRequest = onDismiss,
        title = { TinaDialogTitleText(stringResource(Strings.pkg_manager_uninstall_title, packageInfo.name)) },
        text = {
            TinaDialogContentColumn {
                TinaDialogMessageCard(
                    message = stringResource(
                        Strings.pkg_manager_uninstall_message,
                        platformDisplayName(platform),
                        packageInfo.name
                    )
                )
                if (dependentPackages.isNotEmpty()) {
                    TinaDialogCard(
                        color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.72f),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = stringResource(Strings.pkg_manager_uninstall_warning),
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            style = MaterialTheme.typography.bodyMedium
                        )
                        dependentPackages.forEach { dependentPackage ->
                            Text(
                                text = "\u2022 $dependentPackage",
                                color = MaterialTheme.colorScheme.onErrorContainer,
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TinaDangerButton(
                text = stringResource(Strings.pkg_manager_btn_uninstall),
                onClick = onConfirm
            )
        },
        dismissButton = {
            TinaTextButton(
                text = stringResource(Strings.btn_cancel),
                onClick = onDismiss
            )
        }
    )
}

@Composable
private fun UninstallCompleteDialog(
    result: UninstallResult,
    onDismiss: () -> Unit
) {
    when (result) {
        is UninstallResult.Success -> {
            // 卸载成功：提示用户重新打开文件
            TinaAlertDialog(
                onDismissRequest = onDismiss,
                title = { TinaDialogTitleText(stringResource(Strings.pkg_manager_uninstall_complete)) },
                text = {
                    TinaDialogContentColumn {
                        TinaDialogMessageCard(
                            message = stringResource(
                                Strings.pkg_manager_uninstall_success_msg,
                                result.packageId
                            )
                        )
                        TinaDialogMessageCard(
                            message = stringResource(Strings.pkg_manager_reopen_file_hint),
                            color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.7f),
                            textColor = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    }
                },
                confirmButton = {
                    TinaPrimaryButton(
                        text = stringResource(Strings.btn_confirm),
                        onClick = onDismiss
                    )
                }
            )
        }
        is UninstallResult.Failure -> {
            // 卸载失败：只显示错误信息
            TinaInfoDialog(
                title = stringResource(Strings.pkg_manager_uninstall_failed),
                message = result.error.toDisplayMessage(),
                confirmText = stringResource(Strings.btn_confirm),
                onDismiss = onDismiss
            )
        }
    }
}

@Composable
private fun PackageDetailsDialog(
    pkg: GUIPackage,
    installedMetadata: InstalledPackageMetadata?,
    onDismiss: () -> Unit
) {
    TinaAlertDialog(
        onDismissRequest = onDismiss,
        title = { TinaDialogTitleText(pkg.name) },
        text = {
            TinaDialogContentColumn {
                pkg.description?.let { description ->
                    TinaDialogMessageCard(message = description)
                }

                if (pkg.homepage != null || pkg.category != null) {
                    TinaDialogCard(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        pkg.homepage?.let {
                            Text(
                                text = stringResource(Strings.pkg_manager_detail_homepage, it),
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                        pkg.category?.let {
                            Text(
                                text = stringResource(Strings.pkg_manager_detail_category, it),
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }
                }

                if (pkg.linux != null || pkg.android != null) {
                    TinaDialogCard(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        pkg.linux?.let { linuxPkg ->
                            val linuxArtifactTypeLabel = stringResource(linuxPkg.artifactType.labelResId())
                            Text(
                                text = stringResource(
                                    Strings.pkg_manager_detail_linux,
                                    linuxPkg.version,
                                    linuxPkg.installType.name.lowercase()
                                ),
                                style = MaterialTheme.typography.bodySmall
                            )
                            Text(
                                text = stringResource(
                                    Strings.pkg_manager_detail_artifact_type,
                                    linuxArtifactTypeLabel
                                ),
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                        pkg.android?.let { androidPkg ->
                            val androidArtifactTypeLabel = stringResource(androidPkg.artifactType.labelResId())
                            Text(
                                text = stringResource(
                                    Strings.pkg_manager_detail_android,
                                    androidPkg.version,
                                    androidPkg.installType.name.lowercase()
                                ),
                                style = MaterialTheme.typography.bodySmall
                            )
                            Text(
                                text = stringResource(
                                    Strings.pkg_manager_detail_artifact_type,
                                    androidArtifactTypeLabel
                                ),
                                style = MaterialTheme.typography.bodySmall
                            )
                            androidPkg.abi?.let { abi ->
                                Text(
                                    text = stringResource(Strings.pkg_manager_detail_abi, abi.joinToString()),
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                        }
                    }
                }

                installedMetadata?.let { metadata ->
                    TinaDialogCard(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(
                            text = stringResource(Strings.pkg_detail_package_version, metadata.version),
                            style = MaterialTheme.typography.bodySmall
                        )
                        metadata.packageRevision?.let {
                            Text(
                                text = stringResource(Strings.pkg_detail_package_revision, it),
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                        metadata.upstreamName?.let {
                            Text(
                                text = stringResource(Strings.pkg_detail_upstream_name, it),
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                        metadata.upstreamVersion?.let {
                            Text(
                                text = stringResource(Strings.pkg_detail_library_version, it),
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                        metadata.upstreamTag?.let {
                            Text(
                                text = stringResource(Strings.pkg_detail_upstream_tag, it),
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TinaTextButton(
                text = stringResource(Strings.btn_close),
                onClick = onDismiss
            )
        }
    )
}

@Composable
private fun BatchInstallProgressDialog(
    currentIndex: Int,
    totalCount: Int,
    currentPackageName: String,
    platform: Platform,
    event: InstallProgressEvent
) {
    TinaAlertDialog(
        onDismissRequest = {},
        title = {
            TinaDialogTitleText(
                stringResource(Strings.pkg_manager_batch_install_title, currentIndex + 1, totalCount)
            )
        },
        text = {
            TinaDialogContentColumn {
                TinaDialogMessageCard(
                    message = stringResource(
                        Strings.pkg_manager_batch_install_msg,
                        currentPackageName,
                        platformDisplayName(platform)
                    )
                )
                TinaDialogCard(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    LinearProgressIndicator(
                        progress = { (currentIndex.toFloat() + 0.5f) / totalCount },
                        modifier = Modifier.fillMaxWidth()
                    )
                    ProgressEventContent(event)
                }
            }
        },
        confirmButton = {}
    )
}

@Composable
private fun BatchUpdateProgressDialog(
    currentIndex: Int,
    totalCount: Int,
    currentPackageName: String,
    event: InstallProgressEvent
) {
    TinaAlertDialog(
        onDismissRequest = {},
        title = {
            TinaDialogTitleText(
                stringResource(Strings.pkg_manager_updating_title, currentIndex + 1, totalCount)
            )
        },
        text = {
            TinaDialogContentColumn {
                TinaDialogMessageCard(
                    message = stringResource(Strings.pkg_manager_updating_msg, currentPackageName)
                )
                TinaDialogCard(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    LinearProgressIndicator(
                        progress = { (currentIndex.toFloat() + 0.5f) / totalCount },
                        modifier = Modifier.fillMaxWidth()
                    )
                    ProgressEventContent(event)
                }
            }
        },
        confirmButton = {}
    )
}

@Composable
private fun ProgressEventContent(event: InstallProgressEvent) {
    when (event) {
        is InstallProgressEvent.Preparing -> Text(event.message, style = MaterialTheme.typography.bodySmall)
        is InstallProgressEvent.Downloading -> {
            LinearProgressIndicator(
                progress = { event.progress },
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(stringResource(Strings.pkg_manager_progress_downloading_simple, (event.progress * 100).toInt()), style = MaterialTheme.typography.bodySmall)
        }
        is InstallProgressEvent.Verifying -> Text(event.message, style = MaterialTheme.typography.bodySmall)
        is InstallProgressEvent.Extracting -> {
            LinearProgressIndicator(
                progress = { event.progress },
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(stringResource(Strings.pkg_manager_progress_extracting_simple), style = MaterialTheme.typography.bodySmall)
        }
        is InstallProgressEvent.Installing -> Text(event.message, style = MaterialTheme.typography.bodySmall)
        is InstallProgressEvent.Completed -> Text(stringResource(Strings.pkg_manager_progress_completed_simple), style = MaterialTheme.typography.bodySmall)
        is InstallProgressEvent.Failed -> Text(
            stringResource(Strings.pkg_manager_progress_failed_simple, event.error.toDisplayMessage()),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error
        )
    }
}
