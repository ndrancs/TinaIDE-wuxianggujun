package com.wuxianggujun.tinaide.ui.dialog

import android.app.Dialog
import android.os.Bundle
import androidx.fragment.app.DialogFragment
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.wuxianggujun.tinaide.R

/**
 * 信息提示对话框
 */
class InfoDialog : DialogFragment() {

    private var title: String = ""
    private var message: String = ""
    private var positiveText: String = "确定"
    private var onPositive: (() -> Unit)? = null

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        savedInstanceState?.let {
            title = it.getString(KEY_TITLE, title)
            message = it.getString(KEY_MESSAGE, message)
            positiveText = it.getString(KEY_POSITIVE_TEXT, positiveText)
        }

        return MaterialAlertDialogBuilder(requireContext(), R.style.ThemeOverlay_App_MaterialAlertDialog)
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton(positiveText) { _, _ ->
                onPositive?.invoke()
            }
            .create()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(KEY_TITLE, title)
        outState.putString(KEY_MESSAGE, message)
        outState.putString(KEY_POSITIVE_TEXT, positiveText)
    }

    companion object {
        private const val KEY_TITLE = "title"
        private const val KEY_MESSAGE = "message"
        private const val KEY_POSITIVE_TEXT = "positiveText"

        fun newInstance(
            title: String,
            message: String,
            positiveText: String = "确定",
            onPositive: (() -> Unit)? = null
        ): InfoDialog {
            return InfoDialog().apply {
                this.title = title
                this.message = message
                this.positiveText = positiveText
                this.onPositive = onPositive
            }
        }
    }
}
