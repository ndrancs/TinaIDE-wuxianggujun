package com.wuxianggujun.tinaide.ui.dialog

import android.app.Dialog
import android.os.Bundle
import android.view.LayoutInflater
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import androidx.fragment.app.DialogFragment

import io.github.rosemoe.sora.widget.CodeEditor
import com.wuxianggujun.tinaide.R
import com.wuxianggujun.tinaide.extensions.*

/**
 * 查找和替换对话框
 */
class FindReplaceDialog(
    private val editor: CodeEditor
) : DialogFragment() {
    
    private lateinit var etFind: EditText
    private lateinit var etReplace: EditText
    private lateinit var cbCaseSensitive: CheckBox
    private lateinit var cbWholeWord: CheckBox
    private lateinit var cbRegex: CheckBox
    
    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val view = LayoutInflater.from(requireContext())
            .inflate(R.layout.dialog_find_replace, null)
        
        etFind = view.findViewById(R.id.et_find)
        etReplace = view.findViewById(R.id.et_replace)
        cbCaseSensitive = view.findViewById(R.id.cb_case_sensitive)
        cbWholeWord = view.findViewById(R.id.cb_whole_word)
        cbRegex = view.findViewById(R.id.cb_regex)
        
        val btnFindNext = view.findViewById<Button>(R.id.btn_find_next)
        val btnFindPrevious = view.findViewById<Button>(R.id.btn_find_previous)
        val btnReplace = view.findViewById<Button>(R.id.btn_replace)
        val btnReplaceAll = view.findViewById<Button>(R.id.btn_replace_all)
        
        btnFindNext.setOnClickListener { findNext() }
        btnFindPrevious.setOnClickListener { findPrevious() }
        btnReplace.setOnClickListener { replace() }
        btnReplaceAll.setOnClickListener { replaceAll() }
        
        return com.google.android.material.dialog.MaterialAlertDialogBuilder(
            requireContext(),
            R.style.ThemeOverlay_App_MaterialAlertDialog
        )
            .setTitle("查找和替换")
            .setView(view)
            .setNegativeButton("关闭", null)
            .create()
    }
    
    private fun findNext() {
        val searchText = etFind.text.toString()
        if (searchText.isEmpty()) {
            requireContext().toastWarning("请输入查找内容")
            return
        }
        
        try {
            // 使用简单的文本搜索
            val text = editor.text.toString()
            val caseSensitive = cbCaseSensitive.isChecked
            val cursor = editor.cursor
            val startPos = cursor.right
            
            val index = if (caseSensitive) {
                text.indexOf(searchText, startPos)
            } else {
                text.indexOf(searchText, startPos, ignoreCase = true)
            }
            
            if (index >= 0) {
                // 计算行列号
                val (startLine, startCol) = getLineColumn(text, index)
                val (endLine, endCol) = getLineColumn(text, index + searchText.length)
                editor.setSelectionRegion(startLine, startCol, endLine, endCol)
                requireContext().toastSuccess("找到匹配项")
            } else {
                // 从头开始查找
                val indexFromStart = if (caseSensitive) {
                    text.indexOf(searchText)
                } else {
                    text.indexOf(searchText, ignoreCase = true)
                }
                
                if (indexFromStart >= 0) {
                    val (startLine, startCol) = getLineColumn(text, indexFromStart)
                    val (endLine, endCol) = getLineColumn(text, indexFromStart + searchText.length)
                    editor.setSelectionRegion(startLine, startCol, endLine, endCol)
                    requireContext().toastInfo("已从头开始查找")
                } else {
                    requireContext().toast("未找到匹配项")
                }
            }
        } catch (e: Exception) {
            requireContext().handleErrorWithToast(e, "查找失败")
        }
    }
    
    private fun findPrevious() {
        val searchText = etFind.text.toString()
        if (searchText.isEmpty()) {
            requireContext().toastWarning("请输入查找内容")
            return
        }
        
        try {
            val text = editor.text.toString()
            val caseSensitive = cbCaseSensitive.isChecked
            val cursor = editor.cursor
            val endPos = cursor.left - 1
            
            val index = if (caseSensitive) {
                text.lastIndexOf(searchText, endPos)
            } else {
                text.lastIndexOf(searchText, endPos, ignoreCase = true)
            }
            
            if (index >= 0) {
                val (startLine, startCol) = getLineColumn(text, index)
                val (endLine, endCol) = getLineColumn(text, index + searchText.length)
                editor.setSelectionRegion(startLine, startCol, endLine, endCol)
                requireContext().toastSuccess("找到匹配项")
            } else {
                requireContext().toast("未找到匹配项")
            }
        } catch (e: Exception) {
            requireContext().handleErrorWithToast(e, "查找失败")
        }
    }
    
    private fun replace() {
        val searchText = etFind.text.toString()
        val replaceText = etReplace.text.toString()
        
        if (searchText.isEmpty()) {
            requireContext().toastWarning("请输入查找内容")
            return
        }
        
        try {
            // 获取当前选中的文本
            val cursor = editor.cursor
            if (cursor.isSelected) {
                val selectedText = editor.text.subSequence(
                    cursor.left,
                    cursor.right
                ).toString()
                
                val caseSensitive = cbCaseSensitive.isChecked
                val matches = if (caseSensitive) {
                    selectedText == searchText
                } else {
                    selectedText.equals(searchText, ignoreCase = true)
                }
                
                if (matches) {
                    editor.text.replace(
                        cursor.leftLine,
                        cursor.leftColumn,
                        cursor.rightLine,
                        cursor.rightColumn,
                        replaceText
                    )
                    requireContext().toastSuccess("已替换")
                }
            }
            findNext()
        } catch (e: Exception) {
            requireContext().handleErrorWithToast(e, "替换失败")
        }
    }
    
    private fun replaceAll() {
        val searchText = etFind.text.toString()
        val replaceText = etReplace.text.toString()
        
        if (searchText.isEmpty()) {
            requireContext().toastWarning("请输入查找内容")
            return
        }
        
        val text = editor.text.toString()
        val caseSensitive = cbCaseSensitive.isChecked
        
        val newText = if (caseSensitive) {
            text.replace(searchText, replaceText)
        } else {
            text.replace(searchText, replaceText, ignoreCase = true)
        }
        
        val count = (text.length - newText.length) / (searchText.length - replaceText.length)
        
        if (count > 0) {
            editor.setText(newText)
            requireContext().toastSuccess("共替换 $count 处")
        } else {
            requireContext().toast("未找到匹配项")
        }
    }
    
    /**
     * 将字符索引转换为行列号
     */
    private fun getLineColumn(text: String, index: Int): Pair<Int, Int> {
        var line = 0
        var col = 0
        var pos = 0
        
        while (pos < index && pos < text.length) {
            if (text[pos] == '\n') {
                line++
                col = 0
            } else {
                col++
            }
            pos++
        }
        
        return Pair(line, col)
    }
}
