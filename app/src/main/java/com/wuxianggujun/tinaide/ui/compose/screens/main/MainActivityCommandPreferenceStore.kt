package com.wuxianggujun.tinaide.ui.compose.screens.main

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

internal const val MAIN_ACTIVITY_COMMAND_PREFS_NAME = "main_activity_commands"
private const val KEY_PINNED_COMMAND_IDS = "pinned_command_ids"
private const val KEY_RECENT_COMMAND_IDS = "recent_command_ids"
private const val MAX_PINNED_COMMANDS = 8
private const val MAX_RECENT_COMMANDS = 16
private const val MAX_STORED_COMMAND_ID_LENGTH = 512

internal enum class MainActivityPinnedCommandMoveDirection {
    UP,
    DOWN
}

internal class MainActivityCommandPreferenceStore(
    context: Context
) {
    private val prefs = context.applicationContext.getSharedPreferences(
        MAIN_ACTIVITY_COMMAND_PREFS_NAME,
        Context.MODE_PRIVATE
    )

    private val pinnedState = MutableStateFlow(readCommandIds(KEY_PINNED_COMMAND_IDS))
    val pinnedCommandIdsFlow: StateFlow<List<String>> = pinnedState.asStateFlow()

    private val recentState = MutableStateFlow(readCommandIds(KEY_RECENT_COMMAND_IDS))
    val recentCommandIdsFlow: StateFlow<List<String>> = recentState.asStateFlow()

    fun togglePinned(commandId: String) {
        val normalizedCommandId = commandId.normalizedCommandIdOrNull() ?: return
        val current = pinnedState.value
        val next = (
            if (normalizedCommandId in current) {
                current - normalizedCommandId
            } else {
                listOf(normalizedCommandId) + current
            }
            ).take(MAX_PINNED_COMMANDS)
        writeCommandIds(KEY_PINNED_COMMAND_IDS, next)
        pinnedState.value = next
    }

    fun movePinned(
        commandId: String,
        direction: MainActivityPinnedCommandMoveDirection
    ) {
        val normalizedCommandId = commandId.normalizedCommandIdOrNull() ?: return
        val current = pinnedState.value
        val index = current.indexOf(normalizedCommandId)
        if (index < 0) return

        val targetIndex = when (direction) {
            MainActivityPinnedCommandMoveDirection.UP -> index - 1
            MainActivityPinnedCommandMoveDirection.DOWN -> index + 1
        }
        if (targetIndex !in current.indices) return

        val next = current.toMutableList().apply {
            this[index] = current[targetIndex]
            this[targetIndex] = normalizedCommandId
        }
        writeCommandIds(KEY_PINNED_COMMAND_IDS, next)
        pinnedState.value = next
    }

    fun recordExecuted(commandId: String) {
        val normalizedCommandId = commandId.normalizedCommandIdOrNull() ?: return
        val next = (listOf(normalizedCommandId) + recentState.value)
            .distinct()
            .take(MAX_RECENT_COMMANDS)
        writeCommandIds(KEY_RECENT_COMMAND_IDS, next)
        recentState.value = next
    }

    fun pruneUnavailablePluginCommands(enabledPluginIds: Set<String>) {
        val nextPinned = pinnedState.value.filterAvailablePluginCommands(enabledPluginIds)
        if (nextPinned != pinnedState.value) {
            writeCommandIds(KEY_PINNED_COMMAND_IDS, nextPinned)
            pinnedState.value = nextPinned
        }

        val nextRecent = recentState.value.filterAvailablePluginCommands(enabledPluginIds)
        if (nextRecent != recentState.value) {
            writeCommandIds(KEY_RECENT_COMMAND_IDS, nextRecent)
            recentState.value = nextRecent
        }
    }

    private fun readCommandIds(key: String): List<String> = prefs.getString(key, null)
        ?.lineSequence()
        ?.mapNotNull { it.normalizedCommandIdOrNull() }
        ?.distinct()
        ?.toList()
        .orEmpty()

    private fun writeCommandIds(key: String, commandIds: List<String>) {
        prefs.edit()
            .putString(key, commandIds.joinToString(separator = "\n"))
            .apply()
    }

    private fun String.normalizedCommandIdOrNull(): String? {
        val value = trim()
        return value.takeIf {
            it.isNotBlank() &&
                it.length <= MAX_STORED_COMMAND_ID_LENGTH &&
                '\n' !in it &&
                '\r' !in it
        }
    }
}

private fun List<String>.filterAvailablePluginCommands(
    enabledPluginIds: Set<String>
): List<String> = filter { commandId ->
    val pluginId = commandId.pluginToolbarPluginIdOrNull()
    pluginId == null || pluginId in enabledPluginIds
}

@Composable
internal fun rememberMainActivityCommandPreferenceStore(): MainActivityCommandPreferenceStore {
    val context = LocalContext.current
    return remember(context) {
        MainActivityCommandPreferenceStore(context)
    }
}
