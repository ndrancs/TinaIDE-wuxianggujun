package com.wuxianggujun.tinaide.ui.compose.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.wuxianggujun.tinaide.core.git.GitRemote
import com.wuxianggujun.tinaide.core.i18n.Strings
import kotlinx.coroutines.delay

@Composable
fun GitRemoteDialog(
    remotes: List<GitRemote>,
    isLoading: Boolean,
    error: String?,
    onRefresh: () -> Unit,
    onClearError: () -> Unit,
    onAddRemote: (name: String, url: String, onSuccess: () -> Unit) -> Unit,
    onEditRemoteUrl: (name: String, url: String, onSuccess: () -> Unit) -> Unit,
    onRemoveRemote: (name: String, onSuccess: () -> Unit) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    var editorVisible by remember { mutableStateOf(false) }
    var editorIsEdit by remember { mutableStateOf(false) }
    var editorName by remember { mutableStateOf("") }
    var editorUrl by remember { mutableStateOf("") }
    var remotePendingRemoval by remember { mutableStateOf<GitRemote?>(null) }
    val keyboardController = LocalSoftwareKeyboardController.current
    val nameFocusRequester = remember { FocusRequester() }
    val urlFocusRequester = remember { FocusRequester() }

    fun openAddEditor() {
        editorIsEdit = false
        editorName = ""
        editorUrl = ""
        onClearError()
        editorVisible = true
    }

    fun openEditEditor(remote: GitRemote) {
        editorIsEdit = true
        editorName = remote.name
        editorUrl = remote.fetchUrl
        onClearError()
        editorVisible = true
    }

    fun closeEditor() {
        if (isLoading) return
        keyboardController?.hide()
        editorVisible = false
        editorName = ""
        editorUrl = ""
        onClearError()
    }

    fun closeRemoveDialog() {
        if (isLoading) return
        remotePendingRemoval = null
        onClearError()
    }

    fun submitEditor() {
        val name = editorName.trim()
        val url = editorUrl.trim()
        if (name.isEmpty() || url.isEmpty()) return

        val onSuccess = {
            keyboardController?.hide()
            editorVisible = false
            editorName = ""
            editorUrl = ""
            onClearError()
        }

        if (editorIsEdit) {
            onEditRemoteUrl(name, url, onSuccess)
        } else {
            onAddRemote(name, url, onSuccess)
        }
    }

    LaunchedEffect(Unit) {
        onRefresh()
    }

    LaunchedEffect(editorVisible, editorIsEdit) {
        if (!editorVisible) return@LaunchedEffect
        delay(100)
        if (editorIsEdit) {
            urlFocusRequester.requestFocus()
        } else {
            nameFocusRequester.requestFocus()
        }
        keyboardController?.show()
    }

    if (editorVisible) {
        TinaAlertDialog(
            onDismissRequest = { closeEditor() },
            title = {
                TinaDialogTitleText(
                    if (editorIsEdit) {
                        stringResource(Strings.git_remote_edit_title)
                    } else {
                        stringResource(Strings.git_remote_add_title)
                    }
                )
            },
            text = {
                TinaDialogContentColumn(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    if (editorIsEdit) {
                        CreationTargetSection(
                            title = stringResource(Strings.git_remote_name_label),
                            value = editorName
                        )
                    } else {
                        OutlinedTextField(
                            value = editorName,
                            onValueChange = { editorName = it },
                            enabled = !isLoading,
                            label = { Text(stringResource(Strings.git_remote_name_label)) },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                            keyboardActions = KeyboardActions(
                                onNext = { urlFocusRequester.requestFocus() }
                            ),
                            modifier = Modifier
                                .fillMaxWidth()
                                .focusRequester(nameFocusRequester)
                        )
                    }

                    OutlinedTextField(
                        value = editorUrl,
                        onValueChange = { editorUrl = it },
                        enabled = !isLoading,
                        label = { Text(stringResource(Strings.git_remote_url_label)) },
                        singleLine = true,
                        isError = error != null,
                        supportingText = if (error != null) {
                            {
                                Text(
                                    text = error,
                                    color = MaterialTheme.colorScheme.error
                                )
                            }
                        } else {
                            null
                        },
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = { submitEditor() }),
                        modifier = Modifier
                            .fillMaxWidth()
                            .focusRequester(urlFocusRequester)
                    )

                    if (isLoading) {
                        GitRemoteLoadingIndicator()
                    }
                }
            },
            confirmButton = {
                TinaPrimaryButton(
                    text = stringResource(Strings.btn_ok),
                    onClick = { submitEditor() },
                    enabled = !isLoading && editorName.isNotBlank() && editorUrl.isNotBlank()
                )
            },
            dismissButton = {
                TinaTextButton(
                    text = stringResource(Strings.btn_cancel),
                    onClick = { closeEditor() },
                    enabled = !isLoading
                )
            }
        )
    }

    remotePendingRemoval?.let { remote ->
        TinaAlertDialog(
            onDismissRequest = { closeRemoveDialog() },
            title = {
                TinaDialogTitleText(stringResource(Strings.git_remote_remove_title))
            },
            text = {
                TinaDialogContentColumn(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    TinaDialogMessageCard(
                        message = stringResource(Strings.git_remote_remove_message, remote.name)
                    )

                    CreationTargetSection(
                        title = stringResource(Strings.git_remote_name_label),
                        value = remote.name
                    )

                    CreationTargetSection(
                        title = stringResource(Strings.git_remote_url_label),
                        value = remote.fetchUrl
                    )

                    error?.let { removeError ->
                        Text(
                            text = removeError,
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }

                    if (isLoading) {
                        GitRemoteLoadingIndicator()
                    }
                }
            },
            confirmButton = {
                TinaDangerOutlinedButton(
                    text = stringResource(Strings.action_delete),
                    onClick = {
                        onRemoveRemote(remote.name) {
                            remotePendingRemoval = null
                            onClearError()
                        }
                    },
                    enabled = !isLoading
                )
            },
            dismissButton = {
                TinaTextButton(
                    text = stringResource(Strings.btn_cancel),
                    onClick = { closeRemoveDialog() },
                    enabled = !isLoading
                )
            }
        )
    }

    TinaAlertDialog(
        onDismissRequest = {
            if (!isLoading) {
                onClearError()
                onDismiss()
            }
        },
        title = {
            TinaCustomDialogHeader(
                title = stringResource(Strings.git_remotes_title),
                trailingContent = {
                    GitRemoteActionButton(
                        onClick = onRefresh,
                        enabled = !isLoading,
                        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.2f)
                    ) {
                        if (isLoading) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                strokeWidth = 2.dp
                            )
                        } else {
                            Icon(
                                imageVector = Icons.Default.Refresh,
                                contentDescription = stringResource(Strings.menu_refresh),
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }

                    Spacer(modifier = Modifier.width(6.dp))

                    GitRemoteActionButton(
                        onClick = { openAddEditor() },
                        enabled = !isLoading,
                        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.2f)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Add,
                            contentDescription = stringResource(Strings.git_remote_add_title),
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            )
        },
        text = {
            TinaDialogContentColumn(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                error?.let { remoteError ->
                    TinaDialogCard(
                        color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.36f),
                        border = BorderStroke(
                            1.dp,
                            MaterialTheme.colorScheme.error.copy(alpha = 0.2f)
                        ),
                        verticalArrangement = Arrangement.spacedBy(0.dp)
                    ) {
                        Text(
                            text = remoteError,
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }

                when {
                    isLoading && remotes.isEmpty() -> {
                        GitRemoteContentState(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(180.dp)
                        ) {
                            CircularProgressIndicator()
                        }
                    }

                    remotes.isEmpty() -> {
                        GitRemoteContentState(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(140.dp)
                        ) {
                            Text(
                                text = stringResource(Strings.git_remotes_empty),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    else -> {
                        LazyColumn(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 360.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            items(remotes, key = { it.name }) { remote ->
                                TinaDialogCard(
                                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.2f),
                                    border = BorderStroke(
                                        1.dp,
                                        MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.7f)
                                    ),
                                    contentPadding = PaddingValues(14.dp),
                                    verticalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            text = remote.name,
                                            style = MaterialTheme.typography.titleSmall,
                                            fontWeight = FontWeight.SemiBold,
                                            modifier = Modifier.weight(1f)
                                        )

                                        GitRemoteActionButton(
                                            onClick = { openEditEditor(remote) },
                                            enabled = !isLoading,
                                            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.18f)
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.Edit,
                                                contentDescription = stringResource(Strings.action_edit),
                                                modifier = Modifier.size(18.dp)
                                            )
                                        }

                                        Spacer(modifier = Modifier.width(6.dp))

                                        GitRemoteActionButton(
                                            onClick = {
                                                onClearError()
                                                remotePendingRemoval = remote
                                            },
                                            enabled = !isLoading,
                                            color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.52f)
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.Delete,
                                                contentDescription = stringResource(Strings.action_delete),
                                                modifier = Modifier.size(18.dp),
                                                tint = MaterialTheme.colorScheme.error
                                            )
                                        }
                                    }
                                    Text(
                                        text = remote.fetchUrl,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TinaTextButton(
                text = stringResource(Strings.btn_close),
                onClick = {
                    onClearError()
                    onDismiss()
                },
                enabled = !isLoading
            )
        },
        modifier = modifier
    )
}

@Composable
private fun GitRemoteActionButton(
    onClick: () -> Unit,
    enabled: Boolean,
    color: Color,
    modifier: Modifier = Modifier,
    minHeight: Dp = 32.dp,
    content: @Composable BoxScope.() -> Unit
) {
    TinaPanelSegmentButton(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.defaultMinSize(minWidth = minHeight, minHeight = minHeight),
        minHeight = minHeight,
        shape = MaterialTheme.shapes.small,
        color = color,
        contentPadding = PaddingValues(0.dp),
        content = content
    )
}

@Composable
private fun GitRemoteContentState(
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit
) {
    Box(
        modifier = modifier,
        contentAlignment = Alignment.Center
    ) {
        TinaOverlayPanelSurface(
            shape = MaterialTheme.shapes.small,
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.18f),
            tonalElevation = 0.dp,
            shadowElevation = 0.dp
        ) {
            Box(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                contentAlignment = Alignment.Center,
                content = content
            )
        }
    }
}

@Composable
private fun GitRemoteLoadingIndicator() {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        CircularProgressIndicator(
            modifier = Modifier.size(20.dp),
            strokeWidth = 2.dp
        )
    }
}
