package com.wuxianggujun.tinaide.ui.dialog

import android.app.Dialog
import android.os.Bundle
import androidx.fragment.app.DialogFragment
import com.wuxianggujun.tinaide.extensions.*
import com.wuxianggujun.tinaide.R
import com.wuxianggujun.tinaide.file.IFileManager
import java.io.File

/**
 * 文件上下文菜单对话框
 */
class FileContextMenuDialog(
    private val file: File,
    private val fileManager: IFileManager,
    private val onActionComplete: () -> Unit
) : DialogFragment() {
    
    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val items = if (file.isDirectory) {
            arrayOf("新建文件", "新建文件夹", "重命名", "删除", "复制路径")
        } else {
            arrayOf("重命名", "删除", "复制路径")
        }
        
        val dialog = ListDialog.newInstance(
            title = file.name,
            items = items,
            onItemClick = { index, _ ->
                handleMenuItemClick(index, file.isDirectory)
            }
        )
        
        // 直接显示 ListDialog
        dialog.show(parentFragmentManager, "file_context_menu_list")
        
        // 返回一个空对话框（不会显示）
        return super.onCreateDialog(savedInstanceState)
    }
    
    private fun handleMenuItemClick(position: Int, isDirectory: Boolean) {
        if (isDirectory) {
            when (position) {
                0 -> showNewFileDialog()
                1 -> showNewFolderDialog()
                2 -> showRenameDialog()
                3 -> showDeleteConfirmDialog()
                4 -> copyPathToClipboard()
            }
        } else {
            when (position) {
                0 -> showRenameDialog()
                1 -> showDeleteConfirmDialog()
                2 -> copyPathToClipboard()
            }
        }
    }
    
    /**
     * 显示新建文件对话框
     */
    private fun showNewFileDialog() {
        val ctx = context ?: return
        
        val dialog = InputDialog.newInstance(
            title = "新建文件",
            hint = "文件名",
            validator = { input ->
                when {
                    input.isEmpty() -> "文件名不能为空"
                    !input.matches(Regex("[a-zA-Z0-9_.-]+")) -> "文件名包含非法字符"
                    else -> null
                }
            },
            onConfirm = { fileName ->
                try {
                    fileManager.createFile(file, fileName)
                    ctx.toastSuccess("创建成功")
                    onActionComplete()
                } catch (e: Exception) {
                    ctx.handleErrorWithToast(e, "创建失败")
                }
            }
        )
        dialog.show(parentFragmentManager, "new_file_dialog")
    }
    
    /**
     * 显示新建文件夹对话框
     */
    private fun showNewFolderDialog() {
        val ctx = context ?: return
        
        val dialog = InputDialog.newInstance(
            title = "新建文件夹",
            hint = "文件夹名",
            validator = { input ->
                when {
                    input.isEmpty() -> "文件夹名不能为空"
                    !input.matches(Regex("[a-zA-Z0-9_.-]+")) -> "文件夹名包含非法字符"
                    else -> null
                }
            },
            onConfirm = { folderName ->
                try {
                    fileManager.createDirectory(file, folderName)
                    ctx.toastSuccess("文件夹创建成功")
                    onActionComplete()
                } catch (e: Exception) {
                    ctx.handleErrorWithToast(e, "创建失败")
                }
            }
        )
        dialog.show(parentFragmentManager, "new_folder_dialog")
    }
    
    /**
     * 显示重命名对话框
     */
    private fun showRenameDialog() {
        val ctx = context ?: return
        
        val dialog = InputDialog.newInstance(
            title = "重命名",
            hint = "新名称",
            defaultValue = file.name,
            validator = { input ->
                when {
                    input.isEmpty() -> "名称不能为空"
                    input == file.name -> "名称未改变"
                    !input.matches(Regex("[a-zA-Z0-9_.-]+")) -> "名称包含非法字符"
                    else -> null
                }
            },
            onConfirm = { newName ->
                try {
                    val success = fileManager.renameFile(file, newName)
                    if (success) {
                        ctx.toastSuccess("重命名成功")
                        onActionComplete()
                    } else {
                        ctx.toastError("重命名失败")
                    }
                } catch (e: Exception) {
                    ctx.handleErrorWithToast(e, "重命名失败")
                }
            }
        )
        dialog.show(parentFragmentManager, "rename_dialog")
    }
    
    /**
     * 显示删除确认对话框
     */
    private fun showDeleteConfirmDialog() {
        val ctx = context ?: return
        val message = if (file.isDirectory) {
            "确定要删除文件夹 \"${file.name}\" 及其所有内容吗？"
        } else {
            "确定要删除文件 \"${file.name}\" 吗？"
        }
        
        val dialog = ConfirmDialog.newInstance(
            title = "确认删除",
            message = message,
            positiveText = "删除",
            onPositive = {
                try {
                    val success = fileManager.deleteFile(file)
                    if (success) {
                        ctx.toastSuccess("删除成功")
                        onActionComplete()
                    } else {
                        ctx.toastError("删除失败")
                    }
                } catch (e: Exception) {
                    ctx.handleErrorWithToast(e, "删除失败")
                }
            }
        )
        dialog.show(parentFragmentManager, "delete_confirm_dialog")
    }
    
    /**
     * 复制路径到剪贴板
     */
    private fun copyPathToClipboard() {
        val ctx = context ?: return
        val clipboard = ctx.getSystemService(android.content.Context.CLIPBOARD_SERVICE) 
            as android.content.ClipboardManager
        val clip = android.content.ClipData.newPlainText("file_path", file.absolutePath)
        clipboard.setPrimaryClip(clip)
        ctx.toastSuccess("路径已复制到剪贴板")
    }
}
