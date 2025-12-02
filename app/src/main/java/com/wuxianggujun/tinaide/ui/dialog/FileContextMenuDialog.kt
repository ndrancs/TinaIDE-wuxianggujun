package com.wuxianggujun.tinaide.ui.dialog

import android.app.Dialog
import android.os.Bundle
import android.widget.EditText
import androidx.fragment.app.DialogFragment
import com.wuxianggujun.tinaide.extensions.*
import com.wuxianggujun.tinaide.utils.FileUtils
import com.wuxianggujun.tinaide.ui.dialog.MaterialDialogBuilder
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
        
        return MaterialDialogBuilder.create(requireContext())
            .setTitle(file.name)
            .setItems(items) { _, which ->
                handleMenuItemClick(which, file.isDirectory)
            }
            .setNegativeButton("取消", null)
            .create()
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
        val input = EditText(ctx)
        input.hint = "文件名"
        
        MaterialDialogBuilder.create(ctx)
            .setTitle("新建文件")
            .setView(input)
            .setPositiveButton("创建") { _, _ ->
                val fileName = input.text.toString().trim()
                if (fileName.isEmpty()) {
                    ctx.toastError("文件名不能为空")
                    return@setPositiveButton
                }
                
                try {
                    fileManager.createFile(file, fileName)
                    ctx.toastSuccess("创建成功")
                    onActionComplete()
                } catch (e: Exception) {
                    ctx.handleErrorWithToast(e, "创建失败")
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }
    
    /**
     * 显示新建文件夹对话框
     */
    private fun showNewFolderDialog() {
        val ctx = context ?: return
        val input = EditText(ctx)
        input.hint = "文件夹名"
        
        MaterialDialogBuilder.create(ctx)
            .setTitle("新建文件夹")
            .setView(input)
            .setPositiveButton("创建") { _, _ ->
                val folderName = input.text.toString().trim()
                if (folderName.isEmpty()) {
                    ctx.toastError("文件夹名不能为空")
                    return@setPositiveButton
                }
                
                try {
                    fileManager.createDirectory(file, folderName)
                    ctx.toastSuccess("文件夹创建成功")
                    onActionComplete()
                } catch (e: Exception) {
                    ctx.handleErrorWithToast(e, "创建失败")
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }
    
    /**
     * 显示重命名对话框
     */
    private fun showRenameDialog() {
        val ctx = context ?: return
        val input = EditText(ctx)
        input.setText(file.name)
        input.selectAll()
        
        MaterialDialogBuilder.create(ctx)
            .setTitle("重命名")
            .setView(input)
            .setPositiveButton("确定") { _, _ ->
                val newName = input.text.toString().trim()
                if (newName.isEmpty()) {
                    ctx.toastError("名称不能为空")
                    return@setPositiveButton
                }
                
                if (newName == file.name) {
                    return@setPositiveButton
                }
                
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
            .setNegativeButton("取消", null)
            .show()
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
        
        MaterialDialogBuilder.create(ctx)
            .setTitle("确认删除")
            .setMessage(message)
            .setPositiveButton("删除") { _, _ ->
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
            .setNegativeButton("取消", null)
            .show()
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
