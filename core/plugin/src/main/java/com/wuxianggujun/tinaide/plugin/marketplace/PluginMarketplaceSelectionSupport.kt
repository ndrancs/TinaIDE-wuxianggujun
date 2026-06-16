package com.wuxianggujun.tinaide.plugin.marketplace

object PluginMarketplaceSelectionSupport {

    fun resolveSelectedPlugin(
        selectedPluginId: String?,
        plugins: List<PluginSummary>,
    ): PluginSummary? = selectedPluginId?.let { pluginId ->
        plugins.find { plugin -> plugin.pluginId == pluginId }
    }

    fun shouldClosePluginDetails(
        selectedPluginId: String?,
        selectedPlugin: PluginSummary?,
    ): Boolean = selectedPluginId != null && selectedPlugin == null

    fun applyInstallState(
        state: PluginMarketplaceUiState,
        installedPlugins: Set<String>,
        updatablePlugins: Set<String>,
    ): PluginMarketplaceUiState = state.copy(
        installedPlugins = installedPlugins,
        updatablePlugins = updatablePlugins,
    )
}
