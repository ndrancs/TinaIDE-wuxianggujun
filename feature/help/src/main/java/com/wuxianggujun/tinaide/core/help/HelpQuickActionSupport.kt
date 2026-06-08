package com.wuxianggujun.tinaide.core.help

internal enum class HelpQuickAction {
    CREATE_PLUGIN_PROJECT,
    OPEN_PLUGIN_SETTINGS,
}

internal object HelpQuickActionSupport {
    const val PLUGIN_QUICK_START_DOCUMENT_ID = "plugin-quick-start"

    fun resolveActions(document: HelpDocument): List<HelpQuickAction> = if (document.id == PLUGIN_QUICK_START_DOCUMENT_ID) {
        listOf(
            HelpQuickAction.CREATE_PLUGIN_PROJECT,
            HelpQuickAction.OPEN_PLUGIN_SETTINGS,
        )
    } else {
        emptyList()
    }
}
