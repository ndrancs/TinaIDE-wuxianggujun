package com.wuxianggujun.tinaide.ui.compose.screens.main

import androidx.annotation.StringRes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import com.wuxianggujun.tinaide.core.config.KeyboardShortcutManager
import com.wuxianggujun.tinaide.core.config.ShortcutAction
import com.wuxianggujun.tinaide.core.i18n.Strings
import com.wuxianggujun.tinaide.ui.compose.components.TinaCustomDialog
import com.wuxianggujun.tinaide.ui.compose.components.TinaCustomDialogHeader
import com.wuxianggujun.tinaide.ui.compose.components.TinaTextField

@Composable
internal fun MainActivityCommandPalette(
    commands: List<MainActivityCommand>,
    pinnedCommandIds: List<String>,
    recentCommandIds: List<String>,
    onTogglePinned: (MainActivityCommand) -> Unit,
    onMovePinned: (MainActivityCommand, MainActivityPinnedCommandMoveDirection) -> Unit,
    onExecuteCommand: (MainActivityCommand) -> Unit,
    onDismissRequest: () -> Unit,
) {
    val context = LocalContext.current
    val focusRequester = remember { FocusRequester() }
    val listState = rememberLazyListState()
    var query by remember { mutableStateOf("") }
    var selectedIndex by remember { mutableStateOf(0) }
    val visibleCommands = remember(commands, pinnedCommandIds, recentCommandIds, query, context) {
        orderMainActivityCommands(
            commands = commands,
            context = context,
            pinnedCommandIds = pinnedCommandIds,
            recentCommandIds = recentCommandIds,
            query = query
        )
    }
    val visibleRows = remember(visibleCommands, pinnedCommandIds, recentCommandIds) {
        groupMainActivityCommands(
            commands = visibleCommands,
            pinnedCommandIds = pinnedCommandIds,
            recentCommandIds = recentCommandIds
        ).toCommandPaletteRows()
    }

    fun executeCommand(command: MainActivityCommand) {
        if (!command.enabled) return
        onDismissRequest()
        onExecuteCommand(command)
    }

    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
    }

    LaunchedEffect(visibleCommands) {
        selectedIndex = selectedIndex.coerceIn(0, visibleCommands.lastIndex.coerceAtLeast(0))
    }

    LaunchedEffect(selectedIndex, visibleRows) {
        if (visibleCommands.isNotEmpty()) {
            val selectedCommandId = visibleCommands.getOrNull(selectedIndex)?.id
            val selectedRowIndex = visibleRows.indexOfFirst { row ->
                row is CommandPaletteRow.Command && row.command.id == selectedCommandId
            }
            if (selectedRowIndex >= 0) {
                listState.animateScrollToItem(selectedRowIndex)
            }
        }
    }

    TinaCustomDialog(
        onDismissRequest = onDismissRequest,
        modifier = Modifier
            .fillMaxWidth(0.96f)
            .widthIn(max = 640.dp)
            .heightIn(max = 560.dp),
        properties = DialogProperties(usePlatformDefaultWidth = false),
        contentPadding = PaddingValues(16.dp)
    ) {
        TinaCustomDialogHeader(
            title = stringResource(Strings.command_palette_title),
            subtitle = KeyboardShortcutManager
                .getShortcut(ShortcutAction.COMMAND_PALETTE)
                .toDisplayString(),
            trailingContent = {
                IconButton(onClick = onDismissRequest) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = stringResource(Strings.btn_close)
                    )
                }
            }
        )

        Spacer(modifier = Modifier.height(12.dp))

        TinaTextField(
            value = query,
            onValueChange = { query = it },
            modifier = Modifier
                .focusRequester(focusRequester)
                .onPreviewKeyEvent { event ->
                    when {
                        event.type == KeyEventType.KeyDown && event.key == Key.Enter -> {
                            visibleCommands.getOrNull(selectedIndex)?.let(::executeCommand) != null
                        }

                        event.type == KeyEventType.KeyDown && event.key == Key.DirectionDown -> {
                            if (visibleCommands.isNotEmpty()) {
                                selectedIndex = (selectedIndex + 1).coerceAtMost(visibleCommands.lastIndex)
                            }
                            true
                        }

                        event.type == KeyEventType.KeyDown && event.key == Key.DirectionUp -> {
                            if (visibleCommands.isNotEmpty()) {
                                selectedIndex = (selectedIndex - 1).coerceAtLeast(0)
                            }
                            true
                        }

                        event.type == KeyEventType.KeyDown && event.key == Key.Escape -> {
                            onDismissRequest()
                            true
                        }

                        else -> false
                    }
                },
            placeholder = stringResource(Strings.command_palette_search_hint),
            singleLine = true,
            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                imeAction = ImeAction.Search
            ),
            leadingIcon = {
                Icon(
                    imageVector = Icons.Default.Search,
                    contentDescription = null
                )
            }
        )

        Spacer(modifier = Modifier.height(12.dp))

        if (visibleCommands.isEmpty()) {
            CommandPaletteEmptyState()
        } else {
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 400.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                items(
                    items = visibleRows,
                    key = CommandPaletteRow::key
                ) { row ->
                    when (row) {
                        is CommandPaletteRow.Section -> {
                            CommandPaletteSectionHeader(titleRes = row.titleRes)
                        }

                        is CommandPaletteRow.Command -> {
                            val command = row.command
                            CommandPaletteItem(
                                command = command,
                                selected = visibleCommands.getOrNull(selectedIndex)?.id == command.id,
                                pinnedState = command.pinnedState(pinnedCommandIds),
                                onClick = {
                                    selectedIndex = visibleCommands.indexOfFirst { it.id == command.id }
                                        .coerceAtLeast(0)
                                    executeCommand(command)
                                },
                                onTogglePinned = { onTogglePinned(command) },
                                onMovePinned = { direction -> onMovePinned(command, direction) }
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CommandPaletteEmptyState() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(160.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = stringResource(Strings.command_palette_empty),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun CommandPaletteSectionHeader(
    @StringRes titleRes: Int
) {
    Text(
        text = stringResource(titleRes),
        modifier = Modifier.padding(start = 4.dp, top = 8.dp, bottom = 2.dp),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.primary,
        fontWeight = FontWeight.SemiBold,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis
    )
}

@Composable
private fun CommandPaletteItem(
    command: MainActivityCommand,
    selected: Boolean,
    pinnedState: CommandPalettePinnedState,
    onClick: () -> Unit,
    onTogglePinned: () -> Unit,
    onMovePinned: (MainActivityPinnedCommandMoveDirection) -> Unit,
) {
    val pinned = pinnedState.pinned
    val shortcutText = command.shortcutAction
        ?.let(KeyboardShortcutManager::getShortcut)
        ?.toDisplayString()
    val titleText = command.title.asString()
    val disabledReasonText = command.disabledReason?.asString()
    val subtitleText = when {
        !command.enabled && disabledReasonText != null && command.sourceName != null -> {
            stringResource(
                Strings.command_palette_plugin_unavailable_source,
                command.sourceName,
                disabledReasonText
            )
        }

        !command.enabled && disabledReasonText != null -> disabledReasonText
        command.sourceName != null -> stringResource(Strings.command_palette_plugin_source, command.sourceName)
        else -> stringResource(command.category.titleRes)
    }
    val titleColor = if (command.enabled) {
        MaterialTheme.colorScheme.onSurface
    } else {
        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.48f)
    }
    val subtitleColor = if (command.enabled) {
        MaterialTheme.colorScheme.onSurfaceVariant
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.62f)
    }

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(
                enabled = command.enabled,
                onClick = onClick
            ),
        shape = MaterialTheme.shapes.small,
        color = if (selected) {
            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.42f)
        } else {
            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.18f)
        },
        border = BorderStroke(
            width = 1.dp,
            color = if (selected) {
                MaterialTheme.colorScheme.primary.copy(alpha = 0.65f)
            } else {
                MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f)
            }
        )
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                Text(
                    text = titleText,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                    color = titleColor,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = subtitleText,
                    style = MaterialTheme.typography.bodySmall,
                    color = subtitleColor,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            if (shortcutText != null) {
                Surface(
                    shape = MaterialTheme.shapes.extraSmall,
                    color = MaterialTheme.colorScheme.surface,
                    border = BorderStroke(
                        width = 1.dp,
                        color = MaterialTheme.colorScheme.outlineVariant
                    )
                ) {
                    Text(
                        text = shortcutText,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1
                    )
                }
            }

            if (pinned) {
                IconButton(
                    onClick = { onMovePinned(MainActivityPinnedCommandMoveDirection.UP) },
                    enabled = pinnedState.canMoveUp,
                    modifier = Modifier.size(32.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.ArrowUpward,
                        contentDescription = stringResource(Strings.command_palette_move_pinned_up),
                        tint = if (pinnedState.canMoveUp) {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f)
                        },
                        modifier = Modifier.size(18.dp)
                    )
                }
                IconButton(
                    onClick = { onMovePinned(MainActivityPinnedCommandMoveDirection.DOWN) },
                    enabled = pinnedState.canMoveDown,
                    modifier = Modifier.size(32.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.ArrowDownward,
                        contentDescription = stringResource(Strings.command_palette_move_pinned_down),
                        tint = if (pinnedState.canMoveDown) {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f)
                        },
                        modifier = Modifier.size(18.dp)
                    )
                }
            }

            IconButton(
                onClick = onTogglePinned,
                modifier = Modifier.size(36.dp)
            ) {
                Icon(
                    imageVector = if (pinned) Icons.Filled.Star else Icons.Outlined.StarBorder,
                    contentDescription = stringResource(
                        if (pinned) {
                            Strings.command_palette_unpin_command
                        } else {
                            Strings.command_palette_pin_command
                        }
                    ),
                    tint = if (pinned) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    modifier = Modifier.size(20.dp)
                )
            }
        }
    }
}

private data class CommandPalettePinnedState(
    val pinned: Boolean,
    val canMoveUp: Boolean,
    val canMoveDown: Boolean
)

private fun MainActivityCommand.pinnedState(
    pinnedCommandIds: List<String>
): CommandPalettePinnedState {
    val index = pinnedCommandIds.indexOf(id)
    return CommandPalettePinnedState(
        pinned = index >= 0,
        canMoveUp = index > 0,
        canMoveDown = index >= 0 && index < pinnedCommandIds.lastIndex
    )
}

@Composable
private fun MainActivityCommandText.asString(): String = when (this) {
    is MainActivityCommandText.Literal -> value
    is MainActivityCommandText.Resource -> stringResource(resId)
}

private sealed interface CommandPaletteRow {
    val key: String

    data class Section(
        @param:StringRes @get:StringRes val titleRes: Int,
        val index: Int
    ) : CommandPaletteRow {
        override val key: String = "section:$index:$titleRes"
    }

    data class Command(
        val command: MainActivityCommand
    ) : CommandPaletteRow {
        override val key: String = "command:${command.id}"
    }
}

private fun List<MainActivityCommandGroup>.toCommandPaletteRows(): List<CommandPaletteRow> = buildList {
    this@toCommandPaletteRows.forEachIndexed { index, group ->
        add(
            CommandPaletteRow.Section(
                titleRes = group.titleRes,
                index = index
            )
        )
        group.commands.forEach { command ->
            add(CommandPaletteRow.Command(command))
        }
    }
}
