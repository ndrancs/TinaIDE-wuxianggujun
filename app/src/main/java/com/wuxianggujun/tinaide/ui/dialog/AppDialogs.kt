package com.wuxianggujun.tinaide.ui.dialog

import android.content.Context
import androidx.fragment.app.FragmentManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.wuxianggujun.tinaide.R

/**
 * TinaIDE 统一对话框工具类
 *
 * 提供 Material Design 3 风格的对话框快捷创建方法。
 * 所有对话框自动使用 ThemeOverlay.App.MaterialAlertDialog 主题。
 */
object AppDialogs {

    /**
     * 创建 MD3 风格的 MaterialAlertDialogBuilder
     * 这是所有对话框的基础，自动应用统一主题
     */
    fun builder(context: Context): MaterialAlertDialogBuilder {
        return MaterialAlertDialogBuilder(context, R.style.ThemeOverlay_App_MaterialAlertDialog)
    }

    // ========== 快捷方法（直接显示） ==========

    /**
     * 显示信息提示对话框
     */
    fun showInfo(
        context: Context,
        title: String,
        message: String,
        positiveText: String = "确定",
        onPositive: (() -> Unit)? = null
    ) {
        builder(context)
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton(positiveText) { _, _ -> onPositive?.invoke() }
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
    ) {
        builder(context)
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton(positiveText) { _, _ -> onPositive() }
            .setNegativeButton(negativeText) { _, _ -> onNegative?.invoke() }
            .show()
    }

    /**
     * 显示危险操作确认对话框（如删除）
     */
    fun showDangerConfirm(
        context: Context,
        title: String,
        message: String,
        positiveText: String = "删除",
        negativeText: String = "取消",
        onPositive: () -> Unit
    ) {
        builder(context)
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton(positiveText) { _, _ -> onPositive() }
            .setNegativeButton(negativeText, null)
            .show()
    }

    /**
     * 显示列表选择对话框
     */
    fun showList(
        context: Context,
        title: String,
        items: Array<String>,
        onItemClick: (index: Int, item: String) -> Unit
    ) {
        builder(context)
            .setTitle(title)
            .setItems(items) { _, which ->
                onItemClick(which, items[which])
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /**
     * 显示单选列表对话框
     */
    fun showSingleChoice(
        context: Context,
        title: String,
        items: Array<String>,
        checkedItem: Int = 0,
        positiveText: String = "确定",
        negativeText: String = "取消",
        onItemSelected: (index: Int) -> Unit
    ) {
        var selectedIndex = checkedItem
        builder(context)
            .setTitle(title)
            .setSingleChoiceItems(items, checkedItem) { _, which ->
                selectedIndex = which
            }
            .setPositiveButton(positiveText) { _, _ ->
                onItemSelected(selectedIndex)
            }
            .setNegativeButton(negativeText, null)
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
        onItemsSelected: (selectedIndices: List<Int>) -> Unit
    ) {
        val currentChecked = checkedItems.copyOf()
        builder(context)
            .setTitle(title)
            .setMultiChoiceItems(items, currentChecked) { _, which, isChecked ->
                currentChecked[which] = isChecked
            }
            .setPositiveButton(positiveText) { _, _ ->
                val selectedIndices = currentChecked.withIndex()
                    .filter { it.value }
                    .map { it.index }
                onItemsSelected(selectedIndices)
            }
            .setNegativeButton(negativeText, null)
            .show()
    }

    // ========== DialogFragment 方式（支持配置变化） ==========

    /**
     * 显示输入对话框（DialogFragment）
     * 推荐用于需要支持屏幕旋转的场景
     */
    fun showInput(
        fragmentManager: FragmentManager,
        title: String,
        hint: String = "",
        defaultValue: String = "",
        positiveText: String = "确定",
        negativeText: String = "取消",
        validator: ((String) -> String?)? = null,
        onConfirm: (String) -> Unit
    ) {
        InputDialog.newInstance(
            title = title,
            hint = hint,
            defaultValue = defaultValue,
            positiveText = positiveText,
            negativeText = negativeText,
            validator = validator,
            onConfirm = onConfirm
        ).show(fragmentManager, "InputDialog")
    }

    /**
     * 显示确认对话框（DialogFragment）
     * 推荐用于需要支持屏幕旋转的场景
     */
    fun showConfirmDialog(
        fragmentManager: FragmentManager,
        title: String,
        message: String,
        positiveText: String = "确定",
        negativeText: String = "取消",
        onPositive: () -> Unit,
        onNegative: (() -> Unit)? = null
    ) {
        ConfirmDialog.newInstance(
            title = title,
            message = message,
            positiveText = positiveText,
            negativeText = negativeText,
            onPositive = onPositive,
            onNegative = onNegative
        ).show(fragmentManager, "ConfirmDialog")
    }

    /**
     * 显示信息对话框（DialogFragment）
     */
    fun showInfoDialog(
        fragmentManager: FragmentManager,
        title: String,
        message: String,
        positiveText: String = "确定",
        onPositive: (() -> Unit)? = null
    ) {
        InfoDialog.newInstance(
            title = title,
            message = message,
            positiveText = positiveText,
            onPositive = onPositive
        ).show(fragmentManager, "InfoDialog")
    }

    /**
     * 显示列表对话框（DialogFragment）
     */
    fun showListDialog(
        fragmentManager: FragmentManager,
        title: String,
        items: Array<String>,
        onItemClick: (index: Int, item: String) -> Unit
    ) {
        ListDialog.newInstance(
            title = title,
            items = items,
            onItemClick = onItemClick
        ).show(fragmentManager, "ListDialog")
    }
}
