package com.wuxianggujun.tinaide.ui.compose.components

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.NoteAdd
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DriveFileRenameOutline
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.wuxianggujun.tinaide.core.commands.HostCommandExecutor
import com.wuxianggujun.tinaide.core.commands.HostCommandInvocation
import com.wuxianggujun.tinaide.core.commands.HostCommands
import com.wuxianggujun.tinaide.core.i18n.Strings
import com.wuxianggujun.tinaide.plugin.ResolvedHostMenuItem
import com.wuxianggujun.tinaide.ui.compose.components.TinaDropdownMenu
import com.wuxianggujun.tinaide.ui.compose.components.TinaDropdownMenuDangerItem
import com.wuxianggujun.tinaide.ui.compose.components.TinaDropdownMenuDivider
import com.wuxianggujun.tinaide.ui.compose.components.TinaDropdownMenuItem
import com.wuxianggujun.tinaide.ui.compose.components.TinaDropdownMenuSectionHeader
import com.wuxianggujun.tinaide.ui.compose.components.TinaDropdownMenuSectionTitle
import java.io.File

/**
 * 文件上下文菜单
 */
@Composable
internal fun FileContextMenu(
    file: File,
    isDirectory: Boolean,
    expanded: Boolean,
    pluginMenuItems: List<ResolvedHostMenuItem>,
    onDismiss: () -> Unit,
    onAction: (FileContextAction) -> Unit,
    modifier: Modifier = Modifier,
    hostCommandExecutor: HostCommandExecutor? = null
) {
    TinaDropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismiss,
        modifier = modifier
    ) {
        if (isDirectory) {
            TinaDropdownMenuItem(
                text = { Text(stringResource(Strings.action_new_file)) },
                onClick = { onAction(FileContextAction.NewFile(file)) },
                leadingIcon = {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.NoteAdd,
                        contentDescription = null,
                        modifier = Modifier
                    )
                }
            )
            TinaDropdownMenuItem(
                text = { Text(stringResource(Strings.action_new_folder)) },
                onClick = { onAction(FileContextAction.NewFolder(file)) },
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Default.CreateNewFolder,
                        contentDescription = null
                    )
                }
            )
            TinaDropdownMenuDivider()
        }

        TinaDropdownMenuItem(
            text = { Text(stringResource(Strings.action_rename)) },
            onClick = { onAction(FileContextAction.Rename(file)) },
            leadingIcon = {
                Icon(
                    imageVector = Icons.Default.DriveFileRenameOutline,
                    contentDescription = null
                )
            }
        )

        TinaDropdownMenuItem(
            text = { Text(stringResource(Strings.action_copy_path)) },
            onClick = { onAction(FileContextAction.CopyPath(file)) },
            leadingIcon = {
                Icon(
                    imageVector = Icons.Default.ContentCopy,
                    contentDescription = null
                )
            }
        )

        if (pluginMenuItems.isNotEmpty()) {
            TinaDropdownMenuDivider()
            TinaDropdownMenuSectionHeader {
                TinaDropdownMenuSectionTitle(
                    text = stringResource(Strings.action_more)
                )
            }
            pluginMenuItems.forEach { item ->
                TinaDropdownMenuItem(
                    text = { Text(item.title) },
                    onClick = {
                        onDismiss()
                        val handled = hostCommandExecutor?.execute(
                            item.commandId,
                            HostCommandInvocation(
                                file = file,
                                isDirectory = isDirectory
                            )
                        ) == true
                        if (!handled) {
                            toFileContextActionOrNull(item.commandId, file, isDirectory)?.let(onAction)
                        }
                    },
                    leadingIcon = null
                )
            }
        }

        TinaDropdownMenuDivider()
        TinaDropdownMenuSectionHeader {
            TinaDropdownMenuSectionTitle(
                text = stringResource(Strings.action_delete),
                color = MaterialTheme.colorScheme.error
            )
        }
        TinaDropdownMenuDangerItem(
            text = { Text(stringResource(Strings.btn_delete)) },
            onClick = { onAction(FileContextAction.Delete(file)) },
            leadingIcon = {
                Icon(
                    imageVector = Icons.Default.Delete,
                    contentDescription = null
                )
            }
        )
    }
}

private fun toFileContextActionOrNull(
    commandId: String,
    file: File,
    isDirectory: Boolean
): FileContextAction? = when (commandId) {
    HostCommands.FILE_NEW -> if (isDirectory) FileContextAction.NewFile(file) else null
    HostCommands.FILE_NEW_FOLDER -> if (isDirectory) FileContextAction.NewFolder(file) else null
    HostCommands.FILE_RENAME -> FileContextAction.Rename(file)
    HostCommands.FILE_DELETE -> FileContextAction.Delete(file)
    HostCommands.FILE_COPY_PATH -> FileContextAction.CopyPath(file)
    HostCommands.FILE_COPY_NAME -> FileContextAction.CopyName(file)
    HostCommands.FILE_COPY_RELATIVE_PATH -> FileContextAction.CopyRelativePath(file)
    HostCommands.FILE_DUPLICATE -> FileContextAction.Duplicate(file)
    HostCommands.FILE_OPEN_WITH -> FileContextAction.OpenWith(file)
    HostCommands.FILE_SHARE -> FileContextAction.Share(file)
    HostCommands.FILE_REVEAL_IN_FILE_MANAGER -> FileContextAction.RevealInFileManager(file)
    else -> null
}
