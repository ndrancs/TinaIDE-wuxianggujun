package com.wuxianggujun.tinaide.ui.compose.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.wuxianggujun.tinaide.core.i18n.Strings
import kotlinx.coroutines.delay

/**
 * Git 提交对话框
 */
@Composable
fun GitCommitDialog(
    stagedCount: Int,
    isLoading: Boolean,
    error: String?,
    onCommit: (String) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    var message by remember { mutableStateOf("") }
    val focusRequester = remember { FocusRequester() }
    val keyboardController = LocalSoftwareKeyboardController.current
    val trimmedMessage = message.trim()

    fun submit() {
        if (trimmedMessage.isEmpty()) return
        keyboardController?.hide()
        onCommit(trimmedMessage)
    }

    LaunchedEffect(Unit) {
        delay(100)
        focusRequester.requestFocus()
        keyboardController?.show()
    }

    TinaAlertDialog(
        onDismissRequest = {
            if (!isLoading) {
                keyboardController?.hide()
                onDismiss()
            }
        },
        title = { TinaDialogTitleText(stringResource(Strings.git_commit_title)) },
        text = {
            TinaDialogContentColumn(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                TinaDialogCard(
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.22f),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
                ) {
                    Text(
                        text = stringResource(Strings.git_commit_staged_count, stagedCount),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                OutlinedTextField(
                    value = message,
                    onValueChange = { message = it },
                    label = { Text(stringResource(Strings.git_commit_message_label)) },
                    placeholder = { Text(stringResource(Strings.git_commit_message_placeholder)) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(focusRequester),
                    minLines = 3,
                    maxLines = 5,
                    enabled = !isLoading,
                    isError = error != null,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { submit() })
                )

                error?.let { messageError ->
                    TinaDialogMessageCard(
                        message = messageError,
                        color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.36f),
                        textColor = MaterialTheme.colorScheme.onErrorContainer,
                        border = BorderStroke(
                            1.dp,
                            MaterialTheme.colorScheme.error.copy(alpha = 0.18f)
                        )
                    )
                }

                if (isLoading) {
                    GitDialogLoadingCard(text = stringResource(Strings.git_committing))
                }
            }
        },
        confirmButton = {
            TinaPrimaryButton(
                text = stringResource(Strings.git_commit),
                onClick = ::submit,
                enabled = !isLoading && trimmedMessage.isNotEmpty()
            )
        },
        dismissButton = {
            TinaTextButton(
                text = stringResource(Strings.btn_cancel),
                onClick = {
                    keyboardController?.hide()
                    onDismiss()
                },
                enabled = !isLoading
            )
        },
        modifier = modifier
    )
}

@Composable
private fun GitDialogLoadingCard(text: String) {
    TinaDialogCard(
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.18f),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        verticalArrangement = Arrangement.spacedBy(0.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            CircularProgressIndicator(
                modifier = Modifier.size(20.dp),
                strokeWidth = 2.dp
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = text,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
