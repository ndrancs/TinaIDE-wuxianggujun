package com.wuxianggujun.tinaide.ui.dialog

import android.app.Dialog
import android.os.Bundle
import androidx.fragment.app.DialogFragment
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.wuxianggujun.tinaide.R

/**
 * 列表选择对话框
 */
class ListDialog : DialogFragment() {

    private var title: String = ""
    private var items: Array<String> = emptyArray()
    private var onItemClick: ((index: Int, item: String) -> Unit)? = null

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        savedInstanceState?.let {
            title = it.getString(KEY_TITLE, title)
            items = it.getStringArray(KEY_ITEMS) ?: emptyArray()
        }

        return MaterialAlertDialogBuilder(requireContext(), R.style.ThemeOverlay_App_MaterialAlertDialog)
            .setTitle(title)
            .setItems(items) { _, which ->
                onItemClick?.invoke(which, items[which])
                dismiss()
            }
            .setNegativeButton("取消", null)
            .create()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(KEY_TITLE, title)
        outState.putStringArray(KEY_ITEMS, items)
    }

    companion object {
        private const val KEY_TITLE = "title"
        private const val KEY_ITEMS = "items"

        fun newInstance(
            title: String,
            items: Array<String>,
            onItemClick: (index: Int, item: String) -> Unit
        ): ListDialog {
            return ListDialog().apply {
                this.title = title
                this.items = items
                this.onItemClick = onItemClick
            }
        }
    }
}
