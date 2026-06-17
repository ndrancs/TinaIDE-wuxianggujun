package com.wuxianggujun.tinaide.ui.compose.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.wuxianggujun.tinaide.core.i18n.Strings

/**
 * 退出时未保存更改确认对话框
 *
 * 当用户使用返回手势且有未保存的文件时显示此对话框
 *
 * @param unsavedCount 未保存文件的数量
 * @param onSaveAllAndExit 点击"全部保存并退出"
 * @param onDiscardAndExit 点击"不保存退出"
 * @param onDismiss 关闭对话框
 */
@Composable
fun UnsavedChangesOnExitDialog(
    unsavedCount: Int,
    onSaveAllAndExit: () -> Unit,
    onDiscardAndExit: () -> Unit,
    onDismiss: () -> Unit
) {
    TinaAlertDialog(
        onDismissRequest = onDismiss,
        title = { TinaDialogTitleText(stringResource(Strings.unsaved_changes_on_exit_title)) },
        text = {
            TinaDialogContentColumn {
                TinaDialogMessageCard(
                    message = stringResource(Strings.unsaved_changes_on_exit_message, unsavedCount)
                )
            }
        },
        confirmButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TinaDangerOutlinedButton(
                    text = stringResource(Strings.btn_discard_and_exit),
                    onClick = onDiscardAndExit
                )
                TinaPrimaryButton(
                    text = stringResource(Strings.btn_save_all_and_exit),
                    onClick = onSaveAllAndExit
                )
            }
        }
    )
}
