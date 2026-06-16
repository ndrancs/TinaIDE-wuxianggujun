package com.wuxianggujun.tinaide.plugin.marketplace

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class PluginMarketplaceSelectionSupportTest {

    @Test
    fun `resolveSelectedPlugin should return plugin by selected id`() {
        val selectedPlugin = PluginMarketplaceSelectionSupport.resolveSelectedPlugin(
            selectedPluginId = "plugin.b",
            plugins = listOf(
                pluginSummary("plugin.a"),
                pluginSummary("plugin.b"),
            ),
        )

        assertThat(selectedPlugin?.pluginId).isEqualTo("plugin.b")
    }

    @Test
    fun `shouldClosePluginDetails should require selected id but missing plugin`() {
        assertThat(
            PluginMarketplaceSelectionSupport.shouldClosePluginDetails(
                selectedPluginId = "plugin.a",
                selectedPlugin = null,
            )
        ).isTrue()
        assertThat(
            PluginMarketplaceSelectionSupport.shouldClosePluginDetails(
                selectedPluginId = null,
                selectedPlugin = null,
            )
        ).isFalse()
        assertThat(
            PluginMarketplaceSelectionSupport.shouldClosePluginDetails(
                selectedPluginId = "plugin.a",
                selectedPlugin = pluginSummary("plugin.a"),
            )
        ).isFalse()
    }

    @Test
    fun `applyInstallState should update install flags without touching selection`() {
        val updated = PluginMarketplaceSelectionSupport.applyInstallState(
            state = PluginMarketplaceUiState(
                plugins = listOf(pluginSummary("plugin.a")),
                selectedPluginId = "plugin.a",
            ),
            installedPlugins = setOf("plugin.a"),
            updatablePlugins = setOf("plugin.a"),
        )

        assertThat(updated.installedPlugins).containsExactly("plugin.a")
        assertThat(updated.updatablePlugins).containsExactly("plugin.a")
        assertThat(updated.selectedPluginId).isEqualTo("plugin.a")
        assertThat(updated.plugins).hasSize(1)
    }

    private fun pluginSummary(pluginId: String): PluginSummary = PluginSummary(
        id = "server-$pluginId",
        pluginId = pluginId,
        name = "Plugin $pluginId",
        description = "desc",
        category = "tools",
        publisher = PluginPublisher(
            id = "publisher-$pluginId",
            displayName = "Publisher $pluginId",
        ),
        latestVersion = "1.0.0",
        updatedAt = "2026-04-22T00:00:00Z",
    )
}
