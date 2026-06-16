package com.wuxianggujun.tinaide.plugin.marketplace

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class PluginMarketplaceInstallStateResolverTest {

    @Test
    fun resolve_shouldReturnInstalledAndUpdatablePluginSets() {
        val state = PluginMarketplaceInstallStateResolver.resolve(
            plugins = listOf(
                pluginSummary("installed.latest", latestVersion = "1.0.0"),
                pluginSummary("installed.update", latestVersion = "2.0.0"),
                pluginSummary("installed.downgrade", latestVersion = "0.9.0"),
                pluginSummary("not.installed", latestVersion = "3.0.0"),
                pluginSummary("installed.no-latest", latestVersion = null),
            ),
            installedVersions = mapOf(
                "installed.latest" to "1.0.0",
                "installed.update" to "1.2.0",
                "installed.downgrade" to "1.1.0",
                "installed.no-latest" to "5.0.0",
            ),
        )

        assertThat(state.installedPlugins)
            .containsExactly(
                "installed.latest",
                "installed.update",
                "installed.downgrade",
                "installed.no-latest",
            )
        assertThat(state.updatablePlugins).containsExactly("installed.update")
    }

    private fun pluginSummary(pluginId: String, latestVersion: String?): PluginSummary = PluginSummary(
        id = "id.$pluginId",
        pluginId = pluginId,
        name = pluginId,
        description = null,
        category = "tool",
        latestVersion = latestVersion,
        iconUrl = null,
        publisher = PluginPublisher(
            id = "publisher.$pluginId",
            displayName = "Publisher",
            avatarUrl = null,
        ),
        tags = emptyList(),
        updatedAt = "",
    )
}
