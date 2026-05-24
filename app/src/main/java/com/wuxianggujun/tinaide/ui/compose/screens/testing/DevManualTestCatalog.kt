package com.wuxianggujun.tinaide.ui.compose.screens.testing

import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import com.wuxianggujun.tinaide.core.i18n.Strings

internal data class DevManualTestEntry(
    val registryId: String,
    @param:StringRes @get:StringRes val titleRes: Int,
    @param:StringRes @get:StringRes val descriptionRes: Int,
    val registryContent: @Composable (onNavigateBack: () -> Unit) -> Unit,
)

internal object DevManualTestCatalog {
    val pluginDatabase = DevManualTestEntry(
        registryId = DevTestIds.PluginDatabase,
        titleRes = Strings.dev_options_plugin_db_test,
        descriptionRes = Strings.dev_options_plugin_db_test_desc,
        registryContent = { onBack -> PluginDatabaseTestScreen(onNavigateBack = onBack) },
    )

    val compilerDiagnostics = DevManualTestEntry(
        registryId = DevTestIds.CompilerDiagnostics,
        titleRes = Strings.dev_options_compiler_diagnostics_test,
        descriptionRes = Strings.dev_options_compiler_diagnostics_test_desc,
        registryContent = { onBack -> CompilerDiagnosticsTestScreen(onNavigateBack = onBack) },
    )

    val aiChat = DevManualTestEntry(
        registryId = DevTestIds.AiChat,
        titleRes = Strings.dev_options_ai_chat_test,
        descriptionRes = Strings.dev_options_ai_chat_test_desc,
        registryContent = { onBack -> AiChatTestScreen(onNavigateBack = onBack) },
    )

    val entries: List<DevManualTestEntry> = listOf(
        pluginDatabase,
        compilerDiagnostics,
        aiChat,
    )

    fun toRegistryItems(): List<DevTestItem> = entries.map { entry ->
        DevTestItem(
            id = entry.registryId,
            titleRes = entry.titleRes,
            descriptionRes = entry.descriptionRes,
            content = entry.registryContent,
        )
    }
}
