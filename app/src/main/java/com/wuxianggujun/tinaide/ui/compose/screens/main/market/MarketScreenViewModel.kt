package com.wuxianggujun.tinaide.ui.compose.screens.main.market

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.wuxianggujun.tinaide.core.i18n.Strings
import com.wuxianggujun.tinaide.core.i18n.str
import com.wuxianggujun.tinaide.core.network.ApiResult
import com.wuxianggujun.tinaide.core.network.server.TinaServerConfig
import com.wuxianggujun.tinaide.core.packages.PackageManager
import com.wuxianggujun.tinaide.core.packages.PackageManagerImpl
import com.wuxianggujun.tinaide.core.packages.api.PackageApiClient
import com.wuxianggujun.tinaide.core.packages.model.GUIPackage
import com.wuxianggujun.tinaide.core.packages.model.PackageInstallState
import com.wuxianggujun.tinaide.core.packages.model.Platform
import com.wuxianggujun.tinaide.core.packages.store.LocalInstallStateStore
import com.wuxianggujun.tinaide.core.proot.PRootEnvironment
import com.wuxianggujun.tinaide.core.user.DownloadHistoryItem
import com.wuxianggujun.tinaide.core.user.FavoritePlugin
import com.wuxianggujun.tinaide.core.user.UserContentRepository
import com.wuxianggujun.tinaide.plugin.marketplace.PluginDetail
import com.wuxianggujun.tinaide.plugin.marketplace.PluginMarketplaceRepository
import com.wuxianggujun.tinaide.plugin.marketplace.PluginSummary
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * 市场屏幕 ViewModel
 *
 * 聚合插件市场和包管理的数据。
 */
class MarketScreenViewModel(
    application: Application,
    private val userContentRepository: UserContentRepository
) : AndroidViewModel(application) {

    private val pluginRepository = PluginMarketplaceRepository(application.applicationContext)
    private val packageManager: PackageManager

    init {
        val baseUrl = TinaServerConfig.getInstance(application).getDefaultServerUrl()
        val apiClient = PackageApiClient.getInstance(baseUrl)
        val installStateStore = LocalInstallStateStore(application)
        val prootEnv = PRootEnvironment(application)
        packageManager = PackageManagerImpl(application, apiClient, installStateStore, prootEnv = prootEnv)
    }

    private val _pluginState = MutableStateFlow(PluginState())
    val pluginState: StateFlow<PluginState> = _pluginState.asStateFlow()

    private val _packageState = MutableStateFlow(PackageState())
    val packageState: StateFlow<PackageState> = _packageState.asStateFlow()

    init {
        observeInstalledPlugins()
        loadPlugins()
        loadPackages()
        loadFavoritedPlugins()
    }

    private fun observeInstalledPlugins() {
        viewModelScope.launch {
            pluginRepository.pluginStateFlow.collect {
                syncPluginInstallState()
            }
        }
    }

    private fun loadFavoritedPlugins() {
        viewModelScope.launch {
            userContentRepository.getFavoritesFlow().collect { favorites ->
                val favoritedIds = favorites.map { it.pluginId }.toSet()
                _pluginState.update { it.copy(favoritedPlugins = favoritedIds) }
            }
        }
    }

    private fun loadPlugins() {
        viewModelScope.launch {
            _pluginState.update { it.copy(isLoading = true, error = null) }

            when (val result = pluginRepository.listPlugins(page = 1)) {
                is ApiResult.Success -> {
                    val plugins = result.data.plugins
                    val installState = pluginRepository.resolveInstallState(plugins)

                    _pluginState.update { state ->
                        MarketScreenPluginStateSupport.applyInstallState(
                            state = state.copy(
                                isLoading = false,
                                plugins = plugins,
                                error = null
                            ),
                            installedPlugins = installState.installedPlugins,
                            updatablePlugins = installState.updatablePlugins,
                        )
                    }
                }
                is ApiResult.Error -> {
                    _pluginState.update { it.copy(isLoading = false, error = result.message) }
                }
                is ApiResult.NetworkError -> {
                    _pluginState.update { it.copy(isLoading = false, error = result.message) }
                }
            }
        }
    }

    private fun loadPackages() {
        viewModelScope.launch {
            _packageState.update { it.copy(isLoading = true, error = null) }

            packageManager.getAvailablePackages().onSuccess { packages ->
                val installStates = packages.associate { pkg ->
                    pkg.id to packageManager.getInstallState(pkg.id)
                }

                _packageState.update {
                    it.copy(
                        isLoading = false,
                        packages = packages,
                        filteredPackages = packages,
                        installStates = installStates,
                        error = null
                    )
                }
            }.onFailure { e ->
                _packageState.update { it.copy(isLoading = false, error = e.message) }
            }
        }
    }

    fun retryLoadPlugins() {
        loadPlugins()
    }
    fun retryLoadPackages() {
        loadPackages()
    }

    fun installPlugin(plugin: PluginSummary) {
        val pluginId = plugin.pluginId
        val installedVersion = pluginRepository.getInstalledVersion(pluginId)
        if (_pluginState.value.downloadingPlugins.containsKey(pluginId)) return
        if (installedVersion != null && installedVersion == plugin.latestVersion) return

        viewModelScope.launch {
            _pluginState.update {
                it.copy(downloadingPlugins = it.downloadingPlugins + (pluginId to 0f))
            }

            val result = pluginRepository.downloadAndInstallPlugin(
                pluginId = pluginId,
                version = plugin.latestVersion,
                onProgress = { downloaded, total ->
                    val progress = if (total > 0) downloaded.toFloat() / total else 0f
                    _pluginState.update {
                        it.copy(downloadingPlugins = it.downloadingPlugins + (pluginId to progress))
                    }
                }
            )

            _pluginState.update {
                val newDownloading = it.downloadingPlugins - pluginId
                val newInstalled = if (result.isSuccess) {
                    it.installedPlugins + pluginId
                } else {
                    it.installedPlugins
                }
                val newUpdatable = if (result.isSuccess) {
                    it.updatablePlugins - pluginId
                } else {
                    it.updatablePlugins
                }
                if (result.isSuccess) {
                    it.copy(
                        downloadingPlugins = newDownloading,
                        installedPlugins = newInstalled,
                        updatablePlugins = newUpdatable,
                        message = Strings.plugin_marketplace_install_success.str()
                    )
                } else {
                    val throwable = result.exceptionOrNull()
                    val reason = throwable?.message?.trim()?.takeIf { msg -> msg.isNotBlank() }
                        ?: throwable?.cause?.message?.trim()?.takeIf { msg -> msg.isNotBlank() }
                        ?: throwable?.toString()
                    it.copy(
                        downloadingPlugins = newDownloading,
                        installedPlugins = newInstalled,
                        updatablePlugins = newUpdatable,
                        error = if (reason != null) {
                            Strings.toast_plugins_install_failed.str(reason)
                        } else {
                            Strings.plugin_marketplace_install_failed.str()
                        }
                    )
                }
            }

            if (result.isSuccess) {
                incrementPluginDownloadCount(pluginId)
                recordPluginDownload(plugin)
                loadPlugins()
            }
        }
    }

    private fun incrementPluginDownloadCount(pluginId: String) {
        _pluginState.update { state ->
            state.copy(
                plugins = state.plugins.map { plugin ->
                    if (plugin.pluginId == pluginId) {
                        plugin.copy(downloadCount = plugin.downloadCount + 1)
                    } else {
                        plugin
                    }
                },
                selectedPluginDetail = state.selectedPluginDetail?.let { detail ->
                    if (detail.pluginId == pluginId) {
                        detail.copy(downloadCount = detail.downloadCount + 1)
                    } else {
                        detail
                    }
                }
            )
        }
    }

    private fun loadPluginDetail(pluginId: String) {
        viewModelScope.launch {
            when (val result = pluginRepository.getPluginDetail(pluginId)) {
                is ApiResult.Success -> {
                    applyPluginDetail(result.data)
                }
                is ApiResult.Error -> {
                    _pluginState.update { state ->
                        if (state.selectedPluginId != pluginId) return@update state
                        state.copy(
                            isPluginDetailLoading = false,
                            error = result.message
                        )
                    }
                }
                is ApiResult.NetworkError -> {
                    _pluginState.update { state ->
                        if (state.selectedPluginId != pluginId) return@update state
                        state.copy(
                            isPluginDetailLoading = false,
                            error = result.message
                        )
                    }
                }
            }
        }
    }

    private fun applyPluginDetail(detail: PluginDetail) {
        _pluginState.update { state ->
            if (state.selectedPluginId != detail.pluginId) return@update state
            val newPlugins = state.plugins.map { plugin ->
                if (plugin.pluginId == detail.pluginId) {
                    plugin.merge(detail)
                } else {
                    plugin
                }
            }
            state.copy(
                plugins = newPlugins,
                selectedPluginDetail = detail,
                isPluginDetailLoading = false,
                error = null
            )
        }
    }

    fun rateSelectedPlugin(rating: Int) {
        _pluginState.update {
            it.copy(error = Strings.market_open_source_interaction_unavailable.str())
        }
    }

    fun updatePluginCommentDraft(draft: String) {
        _pluginState.update { it.copy(pluginCommentDraft = draft) }
    }

    fun submitSelectedPluginComment() {
        val selectedPluginId = _pluginState.value.selectedPluginId ?: return
        val content = _pluginState.value.pluginCommentDraft.trim()
        if (content.isBlank()) {
            _pluginState.update {
                it.copy(error = Strings.plugin_marketplace_comment_required.str())
            }
            return
        }

        viewModelScope.launch {
            _pluginState.update {
                it.copy(error = Strings.market_open_source_interaction_unavailable.str())
            }
        }
    }

    fun reportSelectedPluginComment(commentId: String, reason: String, details: String?) {
        _pluginState.update {
            it.copy(error = Strings.market_open_source_interaction_unavailable.str())
        }
    }

    private fun syncPluginInstallState() {
        _pluginState.update { state ->
            val installState = pluginRepository.resolveInstallState(state.plugins)
            MarketScreenPluginStateSupport.applyInstallState(
                state = state,
                installedPlugins = installState.installedPlugins,
                updatablePlugins = installState.updatablePlugins,
            )
        }
    }

    private fun recordPluginDownload(plugin: PluginSummary) {
        viewModelScope.launch {
            userContentRepository.addDownloadHistory(
                DownloadHistoryItem(
                    id = java.util.UUID.randomUUID().toString(),
                    itemType = "plugin",
                    itemId = plugin.pluginId,
                    version = plugin.latestVersion,
                    downloadedAt = System.currentTimeMillis().toString(),
                    fileSize = null,
                    synced = false
                )
            )
        }
    }

    fun togglePluginFavorite(pluginId: String) {
        viewModelScope.launch {
            if (_pluginState.value.favoritedPlugins.contains(pluginId)) {
                removePluginFromFavorites(pluginId)
            } else {
                addPluginToFavorites(pluginId)
            }
        }
    }

    private fun addPluginToFavorites(pluginId: String) {
        viewModelScope.launch {
            val plugin = _pluginState.value.plugins.find { it.pluginId == pluginId }
            val favorite = FavoritePlugin(
                id = java.util.UUID.randomUUID().toString(),
                pluginId = pluginId,
                name = plugin?.name ?: pluginId,
                description = plugin?.description,
                iconUrl = plugin?.iconUrl,
                category = plugin?.category,
                tags = plugin?.tags,
                latestVersion = plugin?.latestVersion,
                addedAt = System.currentTimeMillis().toString(),
                synced = false
            )
            userContentRepository.addFavorite(favorite).onFailure { error ->
                _pluginState.update {
                    it.copy(error = error.message ?: Strings.market_favorite_failed.str())
                }
            }
        }
    }

    private fun removePluginFromFavorites(pluginId: String) {
        viewModelScope.launch {
            userContentRepository.removeFavorite(pluginId).onFailure { error ->
                _pluginState.update {
                    it.copy(error = error.message ?: Strings.market_unfavorite_failed.str())
                }
            }
        }
    }

    fun installPackage(packageId: String) {
        viewModelScope.launch {
            val pkg = _packageState.value.packages.find { p -> p.id == packageId } ?: return@launch

            val result = packageManager.install(packageId, Platform.LINUX) { event -> }
            when (result) {
                is com.wuxianggujun.tinaide.core.packages.model.InstallResult.Success -> {
                    refreshPackageState(packageId)
                    recordPackageDownload(pkg)
                }
                is com.wuxianggujun.tinaide.core.packages.model.InstallResult.Failure -> Unit
            }
        }
    }

    private fun recordPackageDownload(pkg: GUIPackage) {
        viewModelScope.launch {
            val version = pkg.linux?.version ?: pkg.android?.version
            userContentRepository.addDownloadHistory(
                DownloadHistoryItem(
                    id = java.util.UUID.randomUUID().toString(),
                    itemType = "package",
                    itemId = pkg.id,
                    version = version,
                    downloadedAt = System.currentTimeMillis().toString(),
                    fileSize = null,
                    synced = false
                )
            )
        }
    }

    private suspend fun refreshPackageState(packageId: String) {
        val newState = packageManager.getInstallState(packageId)
        _packageState.update {
            it.copy(installStates = it.installStates + (packageId to newState))
        }
    }

    fun clearError() {
        _pluginState.update { it.copy(error = null) }
    }

    fun clearMessage() {
        _pluginState.update { it.copy(message = null) }
    }

    fun selectPlugin(plugin: PluginSummary) {
        _pluginState.update {
            it.copy(
                selectedPluginId = plugin.pluginId,
                selectedPluginDetail = null,
                isPluginDetailLoading = true,
                isPluginRatingSubmitting = false,
                isPluginCommentSubmitting = false,
                isPluginCommentReporting = false,
                reportingCommentId = null,
                pluginCommentDraft = ""
            )
        }
        loadPluginDetail(plugin.pluginId)
    }

    fun closePluginDetails() {
        _pluginState.update {
            it.copy(
                selectedPluginId = null,
                selectedPluginDetail = null,
                isPluginDetailLoading = false,
                isPluginRatingSubmitting = false,
                isPluginCommentSubmitting = false,
                isPluginCommentReporting = false,
                reportingCommentId = null,
                pluginCommentDraft = ""
            )
        }
    }
}

private fun PluginSummary.merge(detail: PluginDetail): PluginSummary = copy(
    name = detail.name,
    description = detail.description,
    category = detail.category,
    tags = detail.tags,
    iconUrl = detail.iconUrl,
    publisher = detail.publisher,
    latestVersion = detail.versions.firstOrNull()?.version ?: latestVersion,
    downloadCount = detail.downloadCount,
    ratingAvg = detail.ratingAvg,
    ratingCount = detail.ratingCount,
    updatedAt = detail.updatedAt
)

data class PluginState(
    val isLoading: Boolean = false,
    val plugins: List<PluginSummary> = emptyList(),
    val installedPlugins: Set<String> = emptySet(),
    val updatablePlugins: Set<String> = emptySet(),
    val favoritedPlugins: Set<String> = emptySet(),
    val downloadingPlugins: Map<String, Float> = emptyMap(),
    val selectedPluginId: String? = null,
    val selectedPluginDetail: PluginDetail? = null,
    val isPluginDetailLoading: Boolean = false,
    val isPluginRatingSubmitting: Boolean = false,
    val isPluginCommentSubmitting: Boolean = false,
    val isPluginCommentReporting: Boolean = false,
    val reportingCommentId: String? = null,
    val pluginCommentDraft: String = "",
    val error: String? = null,
    val message: String? = null
)

data class PackageState(
    val isLoading: Boolean = false,
    val packages: List<GUIPackage> = emptyList(),
    val filteredPackages: List<GUIPackage> = emptyList(),
    val installStates: Map<String, PackageInstallState> = emptyMap(),
    val error: String? = null
)
