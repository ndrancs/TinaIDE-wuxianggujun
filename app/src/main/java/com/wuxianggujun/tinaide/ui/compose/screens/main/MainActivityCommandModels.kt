package com.wuxianggujun.tinaide.ui.compose.screens.main

import android.content.Context
import androidx.annotation.StringRes
import com.wuxianggujun.tinaide.core.commands.HostCommandCategory
import com.wuxianggujun.tinaide.core.config.ShortcutAction
import com.wuxianggujun.tinaide.core.i18n.Strings
import java.net.URLDecoder
import java.net.URLEncoder

internal const val PLUGIN_TOOLBAR_COMMAND_PREFIX = "pluginToolbar:"
private const val PLUGIN_TOOLBAR_COMMAND_ENCODING_VERSION = "v2"

internal fun String.pluginToolbarPluginIdOrNull(): String? {
    return pluginToolbarCommandKeyOrNull()?.pluginId
}

internal data class PluginToolbarCommandKey(
    val pluginId: String,
    val group: String,
    val commandId: String
)

internal fun buildPluginToolbarCommandId(
    pluginId: String,
    group: String,
    commandId: String,
): String {
    return listOf(
        PLUGIN_TOOLBAR_COMMAND_ENCODING_VERSION,
        pluginId.encodePluginToolbarCommandPart(),
        group.encodePluginToolbarCommandPart(),
        commandId.encodePluginToolbarCommandPart()
    ).joinToString(
        separator = ":",
        prefix = PLUGIN_TOOLBAR_COMMAND_PREFIX
    )
}

internal fun String.pluginToolbarCommandKeyOrNull(): PluginToolbarCommandKey? {
    if (!startsWith(PLUGIN_TOOLBAR_COMMAND_PREFIX)) return null

    val payload = removePrefix(PLUGIN_TOOLBAR_COMMAND_PREFIX)
    val parts = payload.split(':')
    if (parts.firstOrNull() == PLUGIN_TOOLBAR_COMMAND_ENCODING_VERSION) {
        if (parts.size != 4) return null
        val pluginId = parts[1].decodePluginToolbarCommandPartOrNull()?.takeIf(String::isNotBlank)
            ?: return null
        val group = parts[2].decodePluginToolbarCommandPartOrNull().orEmpty()
        val commandId = parts[3].decodePluginToolbarCommandPartOrNull()?.takeIf(String::isNotBlank)
            ?: return null
        return PluginToolbarCommandKey(
            pluginId = pluginId,
            group = group,
            commandId = commandId
        )
    }

    return payload.legacyPluginToolbarCommandKeyOrNull()
}

private fun String.legacyPluginToolbarCommandKeyOrNull(): PluginToolbarCommandKey? {
    val pluginId = substringBefore(':', missingDelimiterValue = "")
        .takeIf(String::isNotBlank)
        ?: return null
    val remainder = substringAfter(':', missingDelimiterValue = "")
    return PluginToolbarCommandKey(
        pluginId = pluginId,
        group = remainder.substringBefore(':', missingDelimiterValue = ""),
        commandId = remainder.substringAfter(':', missingDelimiterValue = "")
    )
}

private fun String.encodePluginToolbarCommandPart(): String {
    return URLEncoder.encode(this, Charsets.UTF_8.name())
}

private fun String.decodePluginToolbarCommandPartOrNull(): String? {
    return runCatching {
        URLDecoder.decode(this, Charsets.UTF_8.name())
    }.getOrNull()
}

internal sealed interface MainActivityCommandText {
    fun resolve(context: Context): String

    data class Resource(
        @param:StringRes @get:StringRes val resId: Int
    ) : MainActivityCommandText {
        override fun resolve(context: Context): String = context.getString(resId)
    }

    data class Literal(
        val value: String
    ) : MainActivityCommandText {
        override fun resolve(context: Context): String = value
    }
}

internal enum class MainActivityCommandCategory(
    @param:StringRes @get:StringRes val titleRes: Int,
    val order: Int
) {
    FILE(Strings.menu_section_file, 10),
    CODE(Strings.menu_section_code, 20),
    BUILD(Strings.menu_section_build, 30),
    VIEW(Strings.menu_section_view, 40),
    TERMINAL(Strings.menu_terminal, 50),
    PLUGIN(Strings.menu_section_plugin, 90)
}

internal fun HostCommandCategory.toMainActivityCommandCategory(): MainActivityCommandCategory {
    return when (this) {
        HostCommandCategory.FILE -> MainActivityCommandCategory.FILE
        HostCommandCategory.CODE -> MainActivityCommandCategory.CODE
        HostCommandCategory.BUILD -> MainActivityCommandCategory.BUILD
        HostCommandCategory.VIEW -> MainActivityCommandCategory.VIEW
        HostCommandCategory.TERMINAL -> MainActivityCommandCategory.TERMINAL
    }
}

internal enum class MainActivityCommandSource {
    BUILT_IN,
    PLUGIN
}

internal data class MainActivityCommandGroup(
    @param:StringRes @get:StringRes val titleRes: Int,
    val commands: List<MainActivityCommand>
)

internal data class MainActivityCommand(
    val id: String,
    val title: MainActivityCommandText,
    val category: MainActivityCommandCategory,
    val enabled: Boolean = true,
    val disabledReason: MainActivityCommandText? = null,
    val shortcutAction: ShortcutAction? = null,
    val keywords: List<String> = emptyList(),
    val source: MainActivityCommandSource = MainActivityCommandSource.BUILT_IN,
    val sourceName: String? = null,
    val execute: () -> Unit,
) {
    fun matches(context: Context, query: String): Boolean {
        val normalizedQuery = query.trim()
        if (normalizedQuery.isEmpty()) return true

        val titleText = title.resolve(context)
        val categoryText = context.getString(category.titleRes)
        val disabledReasonText = disabledReason?.resolve(context)
        return titleText.contains(normalizedQuery, ignoreCase = true) ||
            categoryText.contains(normalizedQuery, ignoreCase = true) ||
            disabledReasonText?.contains(normalizedQuery, ignoreCase = true) == true ||
            sourceName?.contains(normalizedQuery, ignoreCase = true) == true ||
            keywords.any { keyword -> keyword.contains(normalizedQuery, ignoreCase = true) }
    }
}

internal fun orderMainActivityCommands(
    commands: List<MainActivityCommand>,
    context: Context,
    pinnedCommandIds: List<String>,
    recentCommandIds: List<String>,
    query: String
): List<MainActivityCommand> {
    val pinnedRank = pinnedCommandIds.rankByCommandId()
    val recentRank = recentCommandIds.rankByCommandId()
    return commands
        .asSequence()
        .filter { command -> command.isVisibleInCommandPalette() && command.matches(context, query) }
        .sortedWith(
            compareBy<MainActivityCommand> { command -> pinnedRank[command.id] ?: Int.MAX_VALUE }
                .thenBy { command -> recentRank[command.id] ?: Int.MAX_VALUE }
                .thenBy { command -> command.category.order }
                .thenBy { command -> command.title.resolve(context).lowercase() }
                .thenBy { command -> command.id }
        )
        .toList()
}

private fun MainActivityCommand.isVisibleInCommandPalette(): Boolean {
    return enabled || disabledReason != null
}

internal fun groupMainActivityCommands(
    commands: List<MainActivityCommand>,
    pinnedCommandIds: List<String>,
    recentCommandIds: List<String>,
): List<MainActivityCommandGroup> {
    val pinnedCommandIdSet = pinnedCommandIds.toSet()
    val recentCommandIdSet = recentCommandIds.toSet()
    val groups = mutableListOf<MainActivityCommandGroupBuilder>()

    commands.forEach { command ->
        val titleRes = command.groupTitleRes(
            pinnedCommandIdSet = pinnedCommandIdSet,
            recentCommandIdSet = recentCommandIdSet
        )
        val currentGroup = groups.lastOrNull()
        if (currentGroup?.titleRes == titleRes) {
            currentGroup.commands += command
        } else {
            groups += MainActivityCommandGroupBuilder(
                titleRes = titleRes,
                commands = mutableListOf(command)
            )
        }
    }

    return groups.map { group ->
        MainActivityCommandGroup(
            titleRes = group.titleRes,
            commands = group.commands.toList()
        )
    }
}

private fun MainActivityCommand.groupTitleRes(
    pinnedCommandIdSet: Set<String>,
    recentCommandIdSet: Set<String>,
): Int {
    return when (id) {
        in pinnedCommandIdSet -> Strings.command_palette_pinned
        in recentCommandIdSet -> Strings.command_palette_quick_actions
        else -> category.titleRes
    }
}

private data class MainActivityCommandGroupBuilder(
    @param:StringRes @get:StringRes val titleRes: Int,
    val commands: MutableList<MainActivityCommand>
)

private fun List<String>.rankByCommandId(): Map<String, Int> {
    return distinct().mapIndexed { index, commandId -> commandId to index }.toMap()
}
