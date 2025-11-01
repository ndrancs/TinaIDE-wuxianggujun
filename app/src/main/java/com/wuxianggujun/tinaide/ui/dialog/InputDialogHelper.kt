package com.wuxianggujun.tinaide.ui.dialog

import android.app.AlertDialog
import android.content.Context
import android.widget.EditText
import android.widget.LinearLayout

/**
 * 输入对话框辅助类
 */
object InputDialogHelper {
    
    /**
     * 显示输入对话框
     */
    fun showInputDialog(
        context: Context,
        title: String,
        hint: String,
        defaultValue: String = "",
        onConfirm: (String) -> Unit
    ) {
        val input = EditText(context).apply {
            this.hint = hint
            setText(defaultValue)
            if (defaultValue.isNotEmpty()) {
                selectAll()
            }
            
            // 设置内边距
            val padding = (16 * context.resources.displayMetrics.density).toInt()
            setPadding(padding, padding, padding, padding)
        }
        
        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            val padding = (16 * context.resources.displayMetrics.density).toInt()
            setPadding(padding, padding, padding, padding)
            addView(input)
        }
        
        AlertDialog.Builder(context)
            .setTitle(title)
            .setView(container)
            .setPositiveButton("确定") { _, _ ->
                val value = input.text.toString().trim()
                if (value.isNotEmpty()) {
                    onConfirm(value)
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }
    
    /**
     * 显示确认对话框
     */
    fun showConfirmDialog(
        context: Context,
        title: String,
        message: String,
        onConfirm: () -> Unit
    ) {
        AlertDialog.Builder(context)
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton("确定") { _, _ ->
                onConfirm()
            }
            .setNegativeButton("取消", null)
            .show()
    }
}
