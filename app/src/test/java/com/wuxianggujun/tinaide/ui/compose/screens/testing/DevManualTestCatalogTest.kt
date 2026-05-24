package com.wuxianggujun.tinaide.ui.compose.screens.testing

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class DevManualTestCatalogTest {

    @Test
    fun entries_shouldExposeStableOrderAndUniqueIds() {
        val entries = DevManualTestCatalog.entries

        assertThat(entries.map { it.registryId }).containsExactly(
            DevTestIds.PluginDatabase,
            DevTestIds.CompilerDiagnostics,
            DevTestIds.AiChat,
        ).inOrder()
        assertThat(entries.map { it.registryId }.distinct()).hasSize(entries.size)
        assertThat(entries.map { it.titleRes }.distinct()).hasSize(entries.size)
        assertThat(entries.map { it.descriptionRes }.distinct()).hasSize(entries.size)
    }

    @Test
    fun toRegistryItems_shouldRoundTripManualMetadata() {
        val registryItems = DevManualTestCatalog.toRegistryItems()

        assertThat(registryItems.map { it.id })
            .containsExactlyElementsIn(DevManualTestCatalog.entries.map { it.registryId })
            .inOrder()
        assertThat(registryItems.map { it.titleRes })
            .containsExactlyElementsIn(DevManualTestCatalog.entries.map { it.titleRes })
            .inOrder()
        assertThat(registryItems.map { it.descriptionRes })
            .containsExactlyElementsIn(DevManualTestCatalog.entries.map { it.descriptionRes })
            .inOrder()
    }
}
