package com.wuxianggujun.tinaide.plugin

enum class ResolvedPluginCommandSurface {
    EDITOR_CONTEXT,
    EDITOR_TOOLBAR,
    FILE_TREE_CONTEXT
}

enum class ResolvedPluginCommandSource {
    HOST,
    PLUGIN
}

data class ResolvedPluginCommand(
    val title: String,
    val commandId: String,
    val group: String,
    val pluginId: String,
    val pluginName: String,
    val surface: ResolvedPluginCommandSurface,
    val source: ResolvedPluginCommandSource
)
