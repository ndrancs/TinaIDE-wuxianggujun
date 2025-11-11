package com.wuxianggujun.tinaide.ui.dialog

import android.content.Context
import android.content.DialogInterface
import android.view.LayoutInflater
import android.view.View
import android.widget.EditText
import androidx.appcompat.app.AlertDialog
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.wuxianggujun.tinaide.R

/**
 * Material Design 风格对话框构建器
 * 封装常用对话框，统一 UI 风格
 */
object MaterialDialogBuilder {

    /**
     * 创建基础的 Material Dialog
     */
    fun create(context: Context): MaterialAlertDialogBuilder {
        return MaterialAlertDialogBuilder(context, R.style.ThemeOverlay_App_MaterialAlertDialog)
    }

    /**
     * 显示信息对话框
     */
    fun showInfo(
        context: Context,
        title: String,
        message: String,
        positiveText: String = "确定",
        onPositive: (() -> Unit)? = null
    ): AlertDialog {
        return create(context)
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton(positiveText) { dialog, _ ->
                onPositive?.invoke()
                dialog.dismiss()
            }
            .show()
    }

    /**
     * 显示确认对话框
     */
    fun showConfirm(
        context: Context,
        title: String,
        message: String,
        positiveText: String = "确定",
        negativeText: String = "取消",
        onPositive: () -> Unit,
        onNegative: (() -> Unit)? = null
    ): AlertDialog {
        return create(context)
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton(positiveText) { dialog, _ ->
                onPositive()
                dialog.dismiss()
            }
            .setNegativeButton(negativeText) { dialog, _ ->
                onNegative?.invoke()
                dialog.dismiss()
            }
            .show()
    }

    /**
     * 显示警告对话框
     */
    fun showWarning(
        context: Context,
        title: String,
        message: String,
        positiveText: String = "我知道了",
        onPositive: (() -> Unit)? = null
    ): AlertDialog {
        return create(context)
            .setTitle(title)
            .setMessage(message)
            .setIcon(android.R.drawable.ic_dialog_alert)
            .setPositiveButton(positiveText) { dialog, _ ->
                onPositive?.invoke()
                dialog.dismiss()
            }
            .show()
    }

    /**
     * 显示错误对话框
     */
    fun showError(
        context: Context,
        title: String = "错误",
        message: String,
        positiveText: String = "确定",
        onPositive: (() -> Unit)? = null
    ): AlertDialog {
        return create(context)
            .setTitle(title)
            .setMessage(message)
            .setIcon(android.R.drawable.ic_dialog_alert)
            .setPositiveButton(positiveText) { dialog, _ ->
                onPositive?.invoke()
                dialog.dismiss()
            }
            .show()
    }

    /**
     * 显示单选列表对话框
     */
    fun showSingleChoice(
        context: Context,
        title: String,
        items: Array<String>,
        selectedIndex: Int = -1,
        onSelected: (index: Int, item: String) -> Unit
    ): AlertDialog {
        return create(context)
            .setTitle(title)
            .setSingleChoiceItems(items, selectedIndex) { dialog, which ->
                onSelected(which, items[which])
                dialog.dismiss()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /**
     * 显示多选列表对话框
     */
    fun showMultiChoice(
        context: Context,
        title: String,
        items: Array<String>,
        checkedItems: BooleanArray,
        positiveText: String = "确定",
        negativeText: String = "取消",
        onConfirm: (selectedIndices: List<Int>) -> Unit
    ): AlertDialog {
        val selectedList = mutableListOf<Int>()
        
        return create(context)
            .setTitle(title)
            .setMultiChoiceItems(items, checkedItems) { _, which, isChecked ->
                if (isChecked) {
                    selectedList.add(which)
                } else {
                    selectedList.remove(which)
                }
            }
            .setPositiveButton(positiveText) { dialog, _ ->
                onConfirm(selectedList)
                dialog.dismiss()
            }
            .setNegativeButton(negativeText, null)
            .show()
    }

    /**
     * 显示输入对话框（使用 TextInputLayout）
     */
    fun showInput(
        context: Context,
        title: String,
        hint: String = "",
        defaultValue: String = "",
        positiveText: String = "确定",
        negativeText: String = "取消",
        validator: ((String) -> String?)? = null, // 返回 null 表示验证通过，否则返回错误信息
        onConfirm: (value: String) -> Unit
    ): AlertDialog {
        // 创建自定义布局
        val view = LayoutInflater.from(context).inflate(R.layout.dialog_input, null)
        val textInputLayout = view.findViewById<TextInputLayout>(R.id.text_input_layout)
        val editText = view.findViewById<TextInputEditText>(R.id.edit_text)
        
        textInputLayout.hint = hint
        editText.setText(defaultValue)
        editText.setSelection(defaultValue.length)
        
        val dialog = create(context)
            .setTitle(title)
            .setView(view)
            .setPositiveButton(positiveText, null) // 先设为 null，后面手动处理
            .setNegativeButton(negativeText, null)
            .create()
        
        dialog.setOnShowListener {
            val positiveButton = dialog.getButton(DialogInterface.BUTTON_POSITIVE)
            positiveButton.setOnClickListener {
                val value = editText.text?.toString() ?: ""
                
                // 验证输入
                val error = validator?.invoke(value)
                if (error != null) {
                    textInputLayout.error = error
                } else {
                    textInputLayout.error = null
                    onConfirm(value)
                    dialog.dismiss()
                }
            }
        }
        
        dialog.show()
        // 自动弹出键盘
        editText.requestFocus()
        
        return dialog
    }

    /**
     * 显示进度对话框（不确定进度）
     */
    fun showProgress(
        context: Context,
        title: String,
        message: String,
        cancelable: Boolean = false
    ): AlertDialog {
        val view = LayoutInflater.from(context).inflate(R.layout.dialog_progress, null)
        
        return create(context)
            .setTitle(title)
            .setMessage(message)
            .setView(view)
            .setCancelable(cancelable)
            .show()
    }

    /**
     * 显示列表对话框
     */
    fun showList(
        context: Context,
        title: String,
        items: Array<String>,
        onItemClick: (index: Int, item: String) -> Unit
    ): AlertDialog {
        return create(context)
            .setTitle(title)
            .setItems(items) { dialog, which ->
                onItemClick(which, items[which])
                dialog.dismiss()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /**
     * 显示自定义视图对话框
     */
    fun showCustomView(
        context: Context,
        title: String? = null,
        view: View,
        positiveText: String? = null,
        negativeText: String? = null,
        onPositive: (() -> Unit)? = null,
        onNegative: (() -> Unit)? = null
    ): AlertDialog {
        val builder = create(context)
            .setView(view)
        
        title?.let { builder.setTitle(it) }
        
        positiveText?.let {
            builder.setPositiveButton(it) { dialog, _ ->
                onPositive?.invoke()
                dialog.dismiss()
            }
        }
        
        negativeText?.let {
            builder.setNegativeButton(it) { dialog, _ ->
                onNegative?.invoke()
                dialog.dismiss()
            }
        }
        
        return builder.show()
    }
}
