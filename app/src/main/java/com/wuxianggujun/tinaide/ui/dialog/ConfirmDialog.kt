package com.wuxianggujun.tinaide.ui.dialog

import android.app.Dialog
import android.os.Bundle
import androidx.fragment.app.DialogFragment
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.wuxianggujun.tinaide.R

/**
 * Material Design 3 风格的确认对话框
 */
class ConfirmDialog : DialogFragment() {

    private var title: String = ""
    private var message: String = ""
    private var positiveText: String = "确定"
    private var negativeText: String = "取消"
    private var onPositive: (() -> Unit)? = null
    private var onNegative: (() -> Unit)? = null

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        // 恢复状态
        savedInstanceState?.let {
            title = it.getString(KEY_TITLE, title)
            message = it.getString(KEY_MESSAGE, message)
            positiveText = it.getString(KEY_POSITIVE_TEXT, positiveText)
            negativeText = it.getString(KEY_NEGATIVE_TEXT, negativeText)
        }

        return MaterialAlertDialogBuilder(requireContext(), R.style.ThemeOverlay_App_MaterialAlertDialog)
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton(positiveText) { _, _ ->
                onPositive?.invoke()
            }
            .setNegativeButton(negativeText) { _, _ ->
                onNegative?.invoke()
            }
            .create()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(KEY_TITLE, title)
        outState.putString(KEY_MESSAGE, message)
        outState.putString(KEY_POSITIVE_TEXT, positiveText)
        outState.putString(KEY_NEGATIVE_TEXT, negativeText)
    }

    companion object {
        private const val KEY_TITLE = "title"
        private const val KEY_MESSAGE = "message"
        private const val KEY_POSITIVE_TEXT = "positiveText"
        private const val KEY_NEGATIVE_TEXT = "negativeText"

        /**
         * 创建确认对话框
         */
        fun newInstance(
            title: String,
            message: String,
            positiveText: String = "确定",
            negativeText: String = "取消",
            onPositive: () -> Unit,
            onNegative: (() -> Unit)? = null
        ): ConfirmDialog {
            return ConfirmDialog().apply {
                this.title = title
                this.message = message
                this.positiveText = positiveText
                this.negativeText = negativeText
                this.onPositive = onPositive
                this.onNegative = onNegative
            }
        }
    }
}
