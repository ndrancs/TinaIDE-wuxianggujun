package com.wuxianggujun.tinaide.ui.dialog

import android.app.AlertDialog
import android.app.Dialog
import android.os.Bundle
import android.widget.EditText
import android.widget.Toast
import androidx.fragment.app.DialogFragment
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
        
        return AlertDialog.Builder(requireContext())
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
        val input = EditText(requireContext())
        input.hint = "文件名"
        
        AlertDialog.Builder(requireContext())
            .setTitle("新建文件")
            .setView(input)
            .setPositiveButton("创建") { _, _ ->
                val fileName = input.text.toString().trim()
                if (fileName.isEmpty()) {
                    Toast.makeText(requireContext(), "文件名不能为空", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                
                try {
                    fileManager.createFile(file, fileName)
                    Toast.makeText(requireContext(), "文件创建成功", Toast.LENGTH_SHORT).show()
                    onActionComplete()
                } catch (e: Exception) {
                    Toast.makeText(requireContext(), "创建失败: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }
    
    /**
     * 显示新建文件夹对话框
     */
    private fun showNewFolderDialog() {
        val input = EditText(requireContext())
        input.hint = "文件夹名"
        
        AlertDialog.Builder(requireContext())
            .setTitle("新建文件夹")
            .setView(input)
            .setPositiveButton("创建") { _, _ ->
                val folderName = input.text.toString().trim()
                if (folderName.isEmpty()) {
                    Toast.makeText(requireContext(), "文件夹名不能为空", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                
                try {
                    fileManager.createDirectory(file, folderName)
                    Toast.makeText(requireContext(), "文件夹创建成功", Toast.LENGTH_SHORT).show()
                    onActionComplete()
                } catch (e: Exception) {
                    Toast.makeText(requireContext(), "创建失败: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }
    
    /**
     * 显示重命名对话框
     */
    private fun showRenameDialog() {
        val input = EditText(requireContext())
        input.setText(file.name)
        input.selectAll()
        
        AlertDialog.Builder(requireContext())
            .setTitle("重命名")
            .setView(input)
            .setPositiveButton("确定") { _, _ ->
                val newName = input.text.toString().trim()
                if (newName.isEmpty()) {
                    Toast.makeText(requireContext(), "名称不能为空", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                
                if (newName == file.name) {
                    return@setPositiveButton
                }
                
                try {
                    val success = fileManager.renameFile(file, newName)
                    if (success) {
                        Toast.makeText(requireContext(), "重命名成功", Toast.LENGTH_SHORT).show()
                        onActionComplete()
                    } else {
                        Toast.makeText(requireContext(), "重命名失败", Toast.LENGTH_SHORT).show()
                    }
                } catch (e: Exception) {
                    Toast.makeText(requireContext(), "重命名失败: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }
    
    /**
     * 显示删除确认对话框
     */
    private fun showDeleteConfirmDialog() {
        val message = if (file.isDirectory) {
            "确定要删除文件夹 \"${file.name}\" 及其所有内容吗？"
        } else {
            "确定要删除文件 \"${file.name}\" 吗？"
        }
        
        AlertDialog.Builder(requireContext())
            .setTitle("确认删除")
            .setMessage(message)
            .setPositiveButton("删除") { _, _ ->
                try {
                    val success = fileManager.deleteFile(file)
                    if (success) {
                        Toast.makeText(requireContext(), "删除成功", Toast.LENGTH_SHORT).show()
                        onActionComplete()
                    } else {
                        Toast.makeText(requireContext(), "删除失败", Toast.LENGTH_SHORT).show()
                    }
                } catch (e: Exception) {
                    Toast.makeText(requireContext(), "删除失败: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }
    
    /**
     * 复制路径到剪贴板
     */
    private fun copyPathToClipboard() {
        val clipboard = requireContext().getSystemService(android.content.Context.CLIPBOARD_SERVICE) 
            as android.content.ClipboardManager
        val clip = android.content.ClipData.newPlainText("file_path", file.absolutePath)
        clipboard.setPrimaryClip(clip)
        Toast.makeText(requireContext(), "路径已复制", Toast.LENGTH_SHORT).show()
    }
}
