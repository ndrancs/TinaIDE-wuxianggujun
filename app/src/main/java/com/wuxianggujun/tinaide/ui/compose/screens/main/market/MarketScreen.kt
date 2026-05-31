

package com.wuxianggujun.tinaide.ui.compose.screens.main.market

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Inventory
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.wuxianggujun.tinaide.core.i18n.Strings
import com.wuxianggujun.tinaide.core.packages.model.GUIPackage
import com.wuxianggujun.tinaide.plugin.marketplace.PluginComment
import com.wuxianggujun.tinaide.plugin.marketplace.PluginDetail
import com.wuxianggujun.tinaide.plugin.marketplace.PluginMarketplaceSelectionSupport
import com.wuxianggujun.tinaide.plugin.marketplace.PluginSummary
import com.wuxianggujun.tinaide.plugin.marketplace.PluginVersion
import com.wuxianggujun.tinaide.ui.compose.components.PluginCardSkeleton
import com.wuxianggujun.tinaide.ui.compose.components.TinaAlertDialog
import com.wuxianggujun.tinaide.ui.compose.components.TinaBackHandlers
import com.wuxianggujun.tinaide.ui.compose.components.TinaCard
import com.wuxianggujun.tinaide.ui.compose.components.TinaDialogCard
import com.wuxianggujun.tinaide.ui.compose.components.TinaDialogContentColumn
import com.wuxianggujun.tinaide.ui.compose.components.TinaDialogTitleText
import com.wuxianggujun.tinaide.ui.compose.components.TinaOutlinedButton
import com.wuxianggujun.tinaide.ui.compose.components.TinaPrimaryButton
import com.wuxianggujun.tinaide.ui.compose.components.TinaPullToRefreshBox
import com.wuxianggujun.tinaide.ui.compose.components.TinaTextButton
import com.wuxianggujun.tinaide.ui.compose.components.TinaTopBar
import com.wuxianggujun.tinaide.ui.compose.components.tinaBackAction
import java.util.Locale
import org.koin.androidx.compose.koinViewModel

/**
 * 市场主屏幕
 *
 * 市场主屏幕：插件市场、包管理。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MarketScreen(
    modifier: Modifier = Modifier,
    onNavigateToMyPublish: () -> Unit = {},
    viewModel: MarketScreenViewModel = koinViewModel()
) {
    val context = LocalContext.current
    var selectedTab by remember { mutableIntStateOf(0) }
    val pluginState by viewModel.pluginState.collectAsState()
    val packageState by viewModel.packageState.collectAsState()
    val closePluginDetails = viewModel::closePluginDetails
    val selectedPlugin = PluginMarketplaceSelectionSupport.resolveSelectedPlugin(
        selectedPluginId = pluginState.selectedPluginId,
        plugins = pluginState.plugins,
    )

    // 显示错误提示
    LaunchedEffect(pluginState.error) {
        pluginState.error?.let { error ->
            Toast.makeText(context, error, Toast.LENGTH_SHORT).show()
            viewModel.clearError()
        }
    }

    // 显示安装结果提示
    LaunchedEffect(pluginState.message) {
        pluginState.message?.let { msg ->
            Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
            viewModel.clearMessage()
        }
    }

    TinaBackHandlers(
        tinaBackAction(
            enabled = pluginState.selectedPluginId != null,
            onBack = closePluginDetails
        )
    )

    LaunchedEffect(pluginState.selectedPluginId, selectedPlugin) {
        if (PluginMarketplaceSelectionSupport.shouldClosePluginDetails(pluginState.selectedPluginId, selectedPlugin)) {
            viewModel.closePluginDetails()
        }
    }

    // 如果选中了插件，显示详情页面；拦截返回手势，防止直接退出 APP
    selectedPlugin?.let { plugin ->
        val detail = pluginState.selectedPluginDetail ?: plugin.toPluginDetailFallback()
        PluginDetailScreen(
            plugin = detail,
            isPluginDetailLoading = pluginState.isPluginDetailLoading,
            isPluginRatingSubmitting = pluginState.isPluginRatingSubmitting,
            isPluginCommentSubmitting = pluginState.isPluginCommentSubmitting,
            isPluginCommentReporting = pluginState.isPluginCommentReporting,
            reportingCommentId = pluginState.reportingCommentId,
            isInstalled = plugin.pluginId in pluginState.installedPlugins,
            isUpdatable = plugin.pluginId in pluginState.updatablePlugins,
            isFavorited = plugin.pluginId in pluginState.favoritedPlugins,
            downloadProgress = pluginState.downloadingPlugins[plugin.pluginId],
            commentDraft = pluginState.pluginCommentDraft,
            onInstall = { viewModel.installPlugin(plugin) },
            onRate = viewModel::rateSelectedPlugin,
            onCommentDraftChange = viewModel::updatePluginCommentDraft,
            onSubmitComment = viewModel::submitSelectedPluginComment,
            onReportComment = viewModel::reportSelectedPluginComment,
            onToggleFavorite = { viewModel.togglePluginFavorite(plugin.pluginId) },
            onNavigateBack = closePluginDetails
        )
        return
    }

    Scaffold(
        topBar = {
            TinaTopBar(
                title = stringResource(Strings.market_title),
                actions = {
                    IconButton(onClick = onNavigateToMyPublish) {
                        Icon(
                            imageVector = Icons.Default.Person,
                            contentDescription = stringResource(Strings.my_publish_title)
                        )
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            PrimaryTabRow(
                selectedTabIndex = selectedTab,
                containerColor = MaterialTheme.colorScheme.surface,
                contentColor = MaterialTheme.colorScheme.primary
            ) {
                Tab(
                    selected = selectedTab == 0,
                    onClick = { selectedTab = 0 },
                    text = { Text(stringResource(Strings.market_tab_plugins)) },
                    icon = { Icon(Icons.Default.Extension, contentDescription = null) }
                )
                Tab(
                    selected = selectedTab == 1,
                    onClick = { selectedTab = 1 },
                    text = { Text(stringResource(Strings.market_tab_packages)) },
                    icon = { Icon(Icons.Default.Inventory, contentDescription = null) }
                )
            }

            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxSize()
            ) {
                when (selectedTab) {
                    0 -> PluginsMarketContent(
                        plugins = pluginState.plugins,
                        installedPlugins = pluginState.installedPlugins,
                        updatablePlugins = pluginState.updatablePlugins,
                        favoritedPlugins = pluginState.favoritedPlugins,
                        downloadingPlugins = pluginState.downloadingPlugins,
                        isLoading = pluginState.isLoading,
                        error = pluginState.error,
                        onInstall = viewModel::installPlugin,
                        onToggleFavorite = viewModel::togglePluginFavorite,
                        onPluginClick = viewModel::selectPlugin,
                        onRefresh = viewModel::retryLoadPlugins
                    )
                    1 -> PackagesMarketContent(
                        packages = packageState.filteredPackages,
                        installStates = packageState.installStates,
                        isLoading = packageState.isLoading,
                        error = packageState.error,
                        onInstall = viewModel::installPackage,
                        onRefresh = viewModel::retryLoadPackages
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PluginsMarketContent(
    plugins: List<PluginSummary>,
    installedPlugins: Set<String>,
    updatablePlugins: Set<String>,
    favoritedPlugins: Set<String>,
    downloadingPlugins: Map<String, Float>,
    isLoading: Boolean,
    error: String?,
    onInstall: (PluginSummary) -> Unit,
    onToggleFavorite: (String) -> Unit,
    onPluginClick: (PluginSummary) -> Unit,
    onRefresh: () -> Unit
) {
    var searchQuery by remember { mutableStateOf("") }
    var selectedCategory by remember { mutableIntStateOf(0) }
    val categories = listOf(
        stringResource(Strings.market_category_all),
        stringResource(Strings.market_category_theme),
        stringResource(Strings.market_category_tool),
        stringResource(Strings.market_category_language),
    )

    Column(modifier = Modifier.fillMaxSize()) {
        SearchTextField(
            query = searchQuery,
            onQueryChange = { searchQuery = it },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)
        )

        LazyRow(
            contentPadding = PaddingValues(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(categories.size) { index ->
                FilterChip(
                    selected = selectedCategory == index,
                    onClick = { selectedCategory = index },
                    label = { Text(categories[index]) },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                        selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                )
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        when {
            isLoading && plugins.isEmpty() -> {
                LazyColumn(
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.fillMaxSize()
                ) { items(5) { PluginCardSkeleton() } }
            }
            error != null && plugins.isEmpty() -> {
                MarketErrorState(icon = Icons.Default.Extension, errorMessage = error, onRetry = onRefresh)
            }
            plugins.isEmpty() -> {
                MarketEmptyState(
                    icon = Icons.Default.Extension,
                    title = stringResource(Strings.market_empty_plugins_title),
                    description = stringResource(Strings.market_empty_plugins_desc)
                )
            }
            else -> {
                TinaPullToRefreshBox(
                    isRefreshing = isLoading,
                    onRefresh = onRefresh,
                    enableHapticFeedback = true,
                    modifier = Modifier.fillMaxSize()
                ) {
                    LazyColumn(
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        items(plugins) { plugin ->
                            PluginCard(
                                plugin = plugin,
                                isInstalled = plugin.pluginId in installedPlugins,
                                isUpdatable = plugin.pluginId in updatablePlugins,
                                isFavorited = plugin.pluginId in favoritedPlugins,
                                downloadProgress = downloadingPlugins[plugin.pluginId],
                                onInstall = { onInstall(plugin) },
                                onToggleFavorite = { onToggleFavorite(plugin.pluginId) },
                                onClick = { onPluginClick(plugin) }
                            )
                        }
                    }
                }
            }
        }
    }
}

// ── PLACEHOLDER_PLUGIN_CARD ──

@Composable
private fun PluginCard(
    plugin: PluginSummary,
    isInstalled: Boolean,
    isUpdatable: Boolean,
    isFavorited: Boolean,
    downloadProgress: Float?,
    onInstall: () -> Unit,
    onToggleFavorite: () -> Unit,
    onClick: () -> Unit
) {
    TinaCard(
        modifier = Modifier.fillMaxWidth(),
        onClick = onClick
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Extension, null, Modifier.size(48.dp), tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(16.dp))
                Column(Modifier.weight(1f)) {
                    Text(plugin.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(plugin.description ?: "", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Spacer(Modifier.height(4.dp))
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(stringResource(Strings.market_download_count, plugin.downloadCount), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(stringResource(Strings.market_rating, plugin.ratingAvg), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                Spacer(Modifier.width(8.dp))
                IconButton(onClick = onToggleFavorite) {
                    Icon(
                        if (isFavorited) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                        null,
                        tint = if (isFavorited) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
            when {
                downloadProgress != null -> Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(progress = { downloadProgress }, modifier = Modifier.size(40.dp))
                }
                isUpdatable -> TinaPrimaryButton(
                    text = stringResource(Strings.plugin_marketplace_update),
                    onClick = onInstall,
                    modifier = Modifier.fillMaxWidth()
                )
                isInstalled -> TinaOutlinedButton(
                    text = stringResource(Strings.market_installed),
                    onClick = {},
                    modifier = Modifier.fillMaxWidth(),
                    enabled = false
                )
                else -> TinaPrimaryButton(text = stringResource(Strings.market_install), onClick = onInstall, modifier = Modifier.fillMaxWidth())
            }
        }
    }
}

// ── PLACEHOLDER_PACKAGES ──

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PackagesMarketContent(
    packages: List<GUIPackage>,
    installStates: Map<String, com.wuxianggujun.tinaide.core.packages.model.PackageInstallState>,
    isLoading: Boolean,
    error: String?,
    onInstall: (String) -> Unit,
    onRefresh: () -> Unit
) {
    var searchQuery by remember { mutableStateOf("") }

    Column(modifier = Modifier.fillMaxSize()) {
        SearchTextField(query = searchQuery, onQueryChange = { searchQuery = it }, modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp))

        when {
            isLoading && packages.isEmpty() -> {
                LazyColumn(contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxSize()) { items(5) { PluginCardSkeleton() } }
            }
            error != null && packages.isEmpty() -> MarketErrorState(icon = Icons.Default.Inventory, errorMessage = error, onRetry = onRefresh)
            packages.isEmpty() -> MarketEmptyState(icon = Icons.Default.Inventory, title = stringResource(Strings.market_empty_packages_title), description = stringResource(Strings.market_empty_packages_desc))
            else -> {
                TinaPullToRefreshBox(isRefreshing = isLoading, onRefresh = onRefresh, enableHapticFeedback = true, modifier = Modifier.fillMaxSize()) {
                    LazyColumn(contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        items(packages) { pkg ->
                            val installState = installStates[pkg.id]
                            val isInstalled = installState?.linux?.isInstalled == true || installState?.android?.isInstalled == true
                            PackageCard(packageItem = pkg, isInstalled = isInstalled, onInstall = { onInstall(pkg.id) })
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PackageCard(packageItem: GUIPackage, isInstalled: Boolean, onInstall: () -> Unit) {
    TinaCard(modifier = Modifier.fillMaxWidth()) {
        Row(modifier = Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Inventory, null, Modifier.size(48.dp), tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(packageItem.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(packageItem.description ?: "", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
                val lv = packageItem.linux?.version
                val av = packageItem.android?.version
                val dv = when {
                    lv != null && av != null -> "$lv (Linux) / $av (Android)"
                    lv != null -> lv
                    av != null -> av
                    else -> null
                }
                if (dv != null) {
                    Spacer(Modifier.height(4.dp))
                    Text(stringResource(Strings.market_version, dv), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Spacer(Modifier.width(8.dp))
            if (isInstalled) {
                TinaOutlinedButton(text = stringResource(Strings.market_installed), onClick = onInstall)
            } else {
                TinaPrimaryButton(text = stringResource(Strings.market_install), onClick = onInstall)
            }
        }
    }
}

// ── PLACEHOLDER_HELPERS ──

@Composable
private fun SearchTextField(query: String, onQueryChange: (String) -> Unit, modifier: Modifier = Modifier) {
    OutlinedTextField(
        value = query,
        onValueChange = onQueryChange,
        modifier = modifier,
        placeholder = { Text(stringResource(Strings.market_search_hint)) },
        leadingIcon = { Icon(Icons.Default.Download, null, tint = MaterialTheme.colorScheme.onSurfaceVariant) },
        singleLine = true,
        shape = RoundedCornerShape(12.dp),
        colors = OutlinedTextFieldDefaults.colors(focusedContainerColor = MaterialTheme.colorScheme.surface, unfocusedContainerColor = MaterialTheme.colorScheme.surface)
    )
}

@Composable
private fun MarketEmptyState(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, description: String, modifier: Modifier = Modifier) {
    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.wrapContentSize().padding(32.dp)) {
            Box(Modifier.size(96.dp).clip(RoundedCornerShape(48.dp)).then(Modifier.padding(0.dp)), contentAlignment = Alignment.Center) {
                Icon(icon, null, Modifier.size(48.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f))
            }
            Spacer(Modifier.height(24.dp))
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
            Spacer(Modifier.height(8.dp))
            Text(description, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun MarketErrorState(icon: androidx.compose.ui.graphics.vector.ImageVector, errorMessage: String, onRetry: () -> Unit, modifier: Modifier = Modifier) {
    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.wrapContentSize().padding(32.dp)) {
            Box(Modifier.size(96.dp).clip(RoundedCornerShape(48.dp)), contentAlignment = Alignment.Center) {
                Icon(Icons.Default.Warning, null, Modifier.size(48.dp), tint = MaterialTheme.colorScheme.onErrorContainer)
            }
            Spacer(Modifier.height(24.dp))
            Text(stringResource(Strings.market_error_title), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
            Spacer(Modifier.height(8.dp))
            Text(errorMessage, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 16.dp))
            Spacer(Modifier.height(24.dp))
            OutlinedButton(onClick = onRetry, colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.primary)) {
                Icon(Icons.Default.Refresh, null, Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(stringResource(Strings.market_error_retry))
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PluginDetailScreen(
    plugin: PluginDetail,
    isPluginDetailLoading: Boolean,
    isPluginRatingSubmitting: Boolean,
    isPluginCommentSubmitting: Boolean,
    isPluginCommentReporting: Boolean,
    reportingCommentId: String?,
    isInstalled: Boolean,
    isUpdatable: Boolean,
    isFavorited: Boolean,
    downloadProgress: Float?,
    commentDraft: String,
    onInstall: () -> Unit,
    onRate: (Int) -> Unit,
    onCommentDraftChange: (String) -> Unit,
    onSubmitComment: () -> Unit,
    onReportComment: (String, String, String?) -> Unit,
    onToggleFavorite: () -> Unit,
    onNavigateBack: () -> Unit
) {
    var reportingComment by remember { mutableStateOf<PluginComment?>(null) }
    var reportReason by remember { mutableStateOf("sexual") }
    var reportDetails by remember { mutableStateOf("") }

    Scaffold(
        topBar = {
            TinaTopBar(
                title = plugin.name,
                onNavigateBack = onNavigateBack,
                actions = {
                    IconButton(onClick = onToggleFavorite) {
                        Icon(
                            if (isFavorited) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                            contentDescription = null,
                            tint = if (isFavorited) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // ── 顶部：插件图标 + 名称 + 发布者 + 安装按钮 ──
            item {
                TinaCard {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                Icons.Default.Extension,
                                null,
                                Modifier.size(64.dp),
                                tint = MaterialTheme.colorScheme.primary
                            )
                            Spacer(Modifier.width(16.dp))
                            Column(Modifier.weight(1f)) {
                                Text(
                                    plugin.name,
                                    style = MaterialTheme.typography.titleLarge,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    plugin.publisher.displayName,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                plugin.category?.let { cat ->
                                    Spacer(Modifier.height(4.dp))
                                    Text(
                                        stringResource(Strings.plugin_marketplace_category) + ": $cat",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }

                        if (isPluginDetailLoading) {
                            Spacer(Modifier.height(12.dp))
                            Text(
                                stringResource(Strings.plugin_marketplace_detail_loading),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(Modifier.height(8.dp))
                            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                        }

                        Spacer(Modifier.height(16.dp))
                        HorizontalDivider()
                        Spacer(Modifier.height(16.dp))

                        // 统计信息行：下载量 / 评分 / 版本号
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceEvenly
                        ) {
                            // 下载量
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Icon(
                                    Icons.Default.Download,
                                    null,
                                    Modifier.size(20.dp),
                                    tint = MaterialTheme.colorScheme.primary
                                )
                                Spacer(Modifier.height(4.dp))
                                Text(
                                    plugin.downloadCount.toString(),
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.SemiBold
                                )
                                Text(
                                    stringResource(Strings.market_downloads),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            // 评分
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Icon(
                                    Icons.Default.Star,
                                    null,
                                    Modifier.size(20.dp),
                                    tint = MaterialTheme.colorScheme.primary
                                )
                                Spacer(Modifier.height(4.dp))
                                Text(
                                    String.format(Locale.ROOT, "%.1f", plugin.ratingAvg),
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.SemiBold
                                )
                                Text(
                                    "(${plugin.ratingCount})",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            // 版本
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Icon(
                                    Icons.Default.Refresh,
                                    null,
                                    Modifier.size(20.dp),
                                    tint = MaterialTheme.colorScheme.primary
                                )
                                Spacer(Modifier.height(4.dp))
                                Text(
                                    plugin.latestVersionLabel(),
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.SemiBold
                                )
                                Text(
                                    stringResource(Strings.plugin_marketplace_version),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }

                        Spacer(Modifier.height(16.dp))

                        // 安装 / 下载进度按钮
                        when {
                            downloadProgress != null -> Column {
                                Text(
                                    stringResource(Strings.market_downloading, (downloadProgress * 100).toInt()),
                                    style = MaterialTheme.typography.bodyMedium
                                )
                                Spacer(Modifier.height(8.dp))
                                LinearProgressIndicator(
                                    progress = { downloadProgress },
                                    modifier = Modifier.fillMaxWidth()
                                )
                            }
                            isUpdatable -> TinaPrimaryButton(
                                text = stringResource(Strings.plugin_marketplace_update),
                                onClick = onInstall,
                                modifier = Modifier.fillMaxWidth()
                            )
                            isInstalled -> TinaOutlinedButton(
                                text = stringResource(Strings.market_installed),
                                onClick = {},
                                modifier = Modifier.fillMaxWidth(),
                                enabled = false
                            )
                            else -> TinaPrimaryButton(
                                text = stringResource(Strings.market_install),
                                onClick = onInstall,
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    }
                }
            }

            // ── 插件信息卡片（更新时间等）──
            item {
                TinaCard {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            stringResource(Strings.plugin_marketplace_info),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                        Spacer(Modifier.height(12.dp))

                        // 发布者
                        PluginInfoRow(
                            label = stringResource(Strings.plugin_marketplace_publisher),
                            value = plugin.publisher.displayName
                        )

                        // 最新版本
                        Spacer(Modifier.height(8.dp))
                        PluginInfoRow(
                            label = stringResource(Strings.plugin_marketplace_version),
                            value = plugin.latestVersionLabel()
                        )

                        // 分类
                        plugin.category?.let { cat ->
                            Spacer(Modifier.height(8.dp))
                            PluginInfoRow(
                                label = stringResource(Strings.plugin_marketplace_category),
                                value = cat
                            )
                        }

                        // 更新时间
                        Spacer(Modifier.height(8.dp))
                        PluginInfoRow(
                            label = stringResource(Strings.plugin_marketplace_sort_updated),
                            value = plugin.updatedAt.take(10) // 只取日期部分 yyyy-MM-dd
                        )

                        plugin.repositoryUrl?.takeIf { it.isNotBlank() }?.let { repositoryUrl ->
                            Spacer(Modifier.height(8.dp))
                            PluginInfoRow(
                                label = stringResource(Strings.plugin_marketplace_repository),
                                value = repositoryUrl
                            )
                        }

                        plugin.homepageUrl?.takeIf { it.isNotBlank() }?.let { homepageUrl ->
                            Spacer(Modifier.height(8.dp))
                            PluginInfoRow(
                                label = stringResource(Strings.plugin_marketplace_homepage),
                                value = homepageUrl
                            )
                        }

                        plugin.license?.takeIf { it.isNotBlank() }?.let { license ->
                            Spacer(Modifier.height(8.dp))
                            PluginInfoRow(
                                label = stringResource(Strings.plugin_marketplace_license),
                                value = license
                            )
                        }
                    }
                }
            }

            item {
                TinaCard {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            stringResource(Strings.plugin_marketplace_rate_title),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = plugin.myRating?.let { currentRating ->
                                stringResource(Strings.plugin_marketplace_my_rating, currentRating)
                            } ?: stringResource(Strings.plugin_marketplace_rate_hint),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.height(12.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceEvenly
                        ) {
                            (1..5).forEach { rating ->
                                IconButton(
                                    onClick = { onRate(rating) },
                                    enabled = !isPluginRatingSubmitting
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Star,
                                        contentDescription = null,
                                        tint = if ((plugin.myRating ?: 0) >= rating) {
                                            MaterialTheme.colorScheme.primary
                                        } else {
                                            MaterialTheme.colorScheme.outline
                                        }
                                    )
                                }
                            }
                        }
                        if (isPluginRatingSubmitting) {
                            Spacer(Modifier.height(8.dp))
                            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                        }
                    }
                }
            }

            item {
                PluginCommentsCard(
                    comments = plugin.comments,
                    canComment = plugin.canComment,
                    commentDisabledReason = plugin.commentDisabledReason,
                    commentDraft = commentDraft,
                    isPluginCommentSubmitting = isPluginCommentSubmitting,
                    isPluginCommentReporting = isPluginCommentReporting,
                    reportingCommentId = reportingCommentId,
                    onCommentDraftChange = onCommentDraftChange,
                    onSubmitComment = onSubmitComment,
                    onReportCommentClick = { comment ->
                        reportingComment = comment
                        reportReason = "sexual"
                        reportDetails = ""
                    }
                )
            }

            // ── 描述卡片 ──
            plugin.description?.let { description ->
                item {
                    TinaCard {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text(
                                stringResource(Strings.plugin_marketplace_description),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold
                            )
                            Spacer(Modifier.height(8.dp))
                            Text(
                                description,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }

            if (plugin.versions.isNotEmpty()) {
                item {
                    TinaCard {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text(
                                stringResource(Strings.plugin_marketplace_versions),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold
                            )
                            Spacer(Modifier.height(12.dp))
                            plugin.versions.forEachIndexed { index, version ->
                                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                    PluginInfoRow(
                                        label = stringResource(Strings.plugin_marketplace_version),
                                        value = version.version
                                    )
                                    version.changelog?.takeIf { it.isNotBlank() }?.let { changelog ->
                                        Text(
                                            text = changelog,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                    Text(
                                        text = version.createdAt.take(10),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                if (index != plugin.versions.lastIndex) {
                                    Spacer(Modifier.height(12.dp))
                                    HorizontalDivider()
                                    Spacer(Modifier.height(12.dp))
                                }
                            }
                        }
                    }
                }
            }

            // ── 标签卡片 ──
            if (plugin.tags.isNotEmpty()) {
                item {
                    TinaCard {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text(
                                stringResource(Strings.plugin_marketplace_tags),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold
                            )
                            Spacer(Modifier.height(8.dp))
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                plugin.tags.forEach { tag ->
                                    SuggestionChip(
                                        onClick = {},
                                        label = { Text(tag, style = MaterialTheme.typography.bodySmall) }
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    reportingComment?.let { comment ->
        ReportPluginCommentDialog(
            reason = reportReason,
            details = reportDetails,
            onReasonChange = { reportReason = it },
            onDetailsChange = { reportDetails = it },
            onDismiss = { reportingComment = null },
            onConfirm = {
                onReportComment(
                    comment.id,
                    reportReason,
                    reportDetails.trim().ifBlank { null }
                )
                reportingComment = null
            }
        )
    }
}

private fun PluginDetail.latestVersionLabel(): String = versions.firstOrNull()?.version ?: "-"

private fun PluginSummary.toPluginDetailFallback(): PluginDetail {
    val fallbackVersions = latestVersion?.let { version ->
        listOf(
            PluginVersion(
                version = version,
                versionCode = 0,
                fileSize = 0,
                fileHash = "",
                minAppVersion = null,
                changelog = null,
                createdAt = updatedAt
            )
        )
    } ?: emptyList()

    return PluginDetail(
        id = id,
        pluginId = pluginId,
        name = name,
        description = description,
        category = category,
        tags = tags,
        iconUrl = iconUrl,
        repositoryUrl = null,
        homepageUrl = null,
        license = null,
        publisher = publisher,
        versions = fallbackVersions,
        comments = emptyList(),
        canComment = true,
        commentDisabledReason = null,
        downloadCount = downloadCount,
        ratingAvg = ratingAvg,
        ratingCount = ratingCount,
        myRating = null,
        createdAt = updatedAt,
        updatedAt = updatedAt
    )
}

@Composable
private fun PluginCommentsCard(
    comments: List<PluginComment>,
    canComment: Boolean,
    commentDisabledReason: String?,
    commentDraft: String,
    isPluginCommentSubmitting: Boolean,
    isPluginCommentReporting: Boolean,
    reportingCommentId: String?,
    onCommentDraftChange: (String) -> Unit,
    onSubmitComment: () -> Unit,
    onReportCommentClick: (PluginComment) -> Unit
) {
    TinaCard {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                stringResource(Strings.plugin_marketplace_comments, comments.size),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(Modifier.height(8.dp))
            Text(
                stringResource(Strings.plugin_marketplace_comment_hint),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = commentDraft,
                onValueChange = onCommentDraftChange,
                modifier = Modifier.fillMaxWidth(),
                enabled = canComment && !isPluginCommentSubmitting,
                minLines = 3,
                maxLines = 5,
                placeholder = {
                    Text(stringResource(Strings.plugin_marketplace_comment_hint))
                }
            )
            Spacer(Modifier.height(12.dp))
            TinaPrimaryButton(
                text = stringResource(Strings.plugin_marketplace_comment_submit),
                onClick = onSubmitComment,
                modifier = Modifier.fillMaxWidth(),
                enabled = canComment && commentDraft.isNotBlank() && !isPluginCommentSubmitting
            )
            if (isPluginCommentSubmitting) {
                Spacer(Modifier.height(8.dp))
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
            if (!canComment && !commentDisabledReason.isNullOrBlank()) {
                Spacer(Modifier.height(8.dp))
                Text(
                    text = commentDisabledReason,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }
            Spacer(Modifier.height(16.dp))
            if (comments.isEmpty()) {
                Text(
                    stringResource(Strings.plugin_marketplace_comments_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                comments.forEachIndexed { index, comment ->
                    PluginCommentItem(
                        comment = comment,
                        isReporting = isPluginCommentReporting && reportingCommentId == comment.id,
                        onReportClick = if (comment.isMine) {
                            null
                        } else {
                            { onReportCommentClick(comment) }
                        }
                    )
                    if (index != comments.lastIndex) {
                        Spacer(Modifier.height(12.dp))
                        HorizontalDivider()
                        Spacer(Modifier.height(12.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun PluginCommentItem(
    comment: PluginComment,
    isReporting: Boolean,
    onReportClick: (() -> Unit)?
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Default.Person,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )
                Text(
                    text = comment.author.displayName,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold
                )
            }
            Text(
                text = comment.createdAt.take(10),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Text(
            text = comment.content,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        if (onReportClick != null) {
            TextButton(
                onClick = onReportClick,
                enabled = !isReporting
            ) {
                Text(stringResource(Strings.plugin_marketplace_comment_report))
            }
        }
    }
}

@Composable
private fun ReportPluginCommentDialog(
    reason: String,
    details: String,
    onReasonChange: (String) -> Unit,
    onDetailsChange: (String) -> Unit,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit
) {
    val reasons = listOf(
        "sexual" to Strings.plugin_marketplace_report_reason_sexual,
        "violence" to Strings.plugin_marketplace_report_reason_violence,
        "vulgar" to Strings.plugin_marketplace_report_reason_vulgar,
        "politics" to Strings.plugin_marketplace_report_reason_politics,
        "spam" to Strings.plugin_marketplace_report_reason_spam,
        "other" to Strings.plugin_marketplace_report_reason_other
    )

    TinaAlertDialog(
        onDismissRequest = onDismiss,
        title = {
            TinaDialogTitleText(stringResource(Strings.plugin_marketplace_comment_report_title))
        },
        text = {
            TinaDialogContentColumn {
                TinaDialogCard(
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.12f)
                ) {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(reasons) { item ->
                            FilterChip(
                                selected = reason == item.first,
                                onClick = { onReasonChange(item.first) },
                                label = { Text(stringResource(item.second)) }
                            )
                        }
                    }
                    OutlinedTextField(
                        value = details,
                        onValueChange = onDetailsChange,
                        modifier = Modifier.fillMaxWidth(),
                        minLines = 2,
                        maxLines = 4,
                        placeholder = {
                            Text(stringResource(Strings.plugin_marketplace_comment_report_details))
                        }
                    )
                }
            }
        },
        confirmButton = {
            TinaPrimaryButton(
                text = stringResource(Strings.plugin_marketplace_comment_report_submit),
                onClick = onConfirm,
                enabled = reason.isNotBlank()
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
private fun PluginInfoRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(0.4f)
        )
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.weight(0.6f),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
    }
}
