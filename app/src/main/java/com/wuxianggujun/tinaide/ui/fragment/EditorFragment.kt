package com.wuxianggujun.tinaide.ui.fragment

import android.os.Bundle
import android.view.View
import com.wuxianggujun.tinaide.base.BaseBindingFragment
import com.wuxianggujun.tinaide.databinding.FragmentEditorBinding
import com.wuxianggujun.tinaide.R
import io.github.rosemoe.sora.widget.CodeEditor
import io.github.rosemoe.sora.langs.textmate.TextMateColorScheme
import io.github.rosemoe.sora.widget.schemes.EditorColorScheme

/**
 * 编辑器 Fragment
 * 包含 SoraEditor 实例
 */
class EditorFragment : BaseBindingFragment<FragmentEditorBinding>(
    FragmentEditorBinding::inflate
) {
    private lateinit var codeEditor: CodeEditor
    private var filePath: String? = null
    
    companion object {
        private const val ARG_FILE_PATH = "file_path"
        
        fun newInstance(filePath: String): EditorFragment {
            return EditorFragment().apply {
                arguments = Bundle().apply {
                    putString(ARG_FILE_PATH, filePath)
                }
            }
        }
    }
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        filePath = arguments?.getString(ARG_FILE_PATH)
    }
    
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        
        codeEditor = binding.codeEditor
        setupEditor()
        loadFileContent()
    }
    
    private fun loadFileContent() {
        filePath?.let { path ->
            try {
                val file = java.io.File(path)
                if (file.exists() && file.isFile) {
                    val content = file.readText()
                    android.util.Log.d("EditorFragment", "Loading file: $path, content length: ${content.length}")
                    codeEditor.setText(content)
                } else {
                    android.util.Log.e("EditorFragment", "File not found or not a file: $path")
                }
            } catch (e: Exception) {
                android.util.Log.e("EditorFragment", "Error loading file: $path", e)
            }
        }
    }
    
    private fun setupEditor() {
        // 基本配置
        codeEditor.apply {
            // 字体大小（从 Prefs 读取）
            setTextSize(com.wuxianggujun.tinaide.core.config.Prefs.editorFontSize)

            // 显示行号
            isLineNumberEnabled = com.wuxianggujun.tinaide.core.config.Prefs.editorShowLineNumbers

            // 自动换行
            isWordwrap = com.wuxianggujun.tinaide.core.config.Prefs.editorWordWrap

            // 显示不可打印字符（保持原有策略）
            nonPrintablePaintingFlags = CodeEditor.FLAG_DRAW_WHITESPACE_LEADING or 
                                        CodeEditor.FLAG_DRAW_LINE_SEPARATOR or 
                                        CodeEditor.FLAG_DRAW_WHITESPACE_IN_SELECTION

            // 启用自动缩进
            tabWidth = com.wuxianggujun.tinaide.core.config.Prefs.editorTabSize
            
            // 启用代码块线
            isBlockLineEnabled = true
            
            // 启用光标动画
            isCursorAnimationEnabled = true
            
            // 设置颜色方案
            colorScheme = EditorColorScheme.getDefault()
        }
    }
    
    /**
     * 获取编辑器实例
     */
    fun getEditor(): CodeEditor {
        return codeEditor
    }
    
    /**
     * 获取文件路径
     */
    fun getFilePath(): String? {
        return filePath
    }
    
    /**
     * 设置文本内容
     */
    fun setText(text: String) {
        codeEditor.setText(text)
    }
    
    /**
     * 获取文本内容
     */
    fun getText(): String {
        return codeEditor.text.toString()
    }
    
    /**
     * 检查是否有未保存的修改
     */
    fun isDirty(): Boolean {
        // TODO: 实现修改状态跟踪
        return false
    }
    
    /**
     * 撤销
     */
    fun undo() {
        if (codeEditor.canUndo()) {
            codeEditor.undo()
        }
    }
    
    /**
     * 重做
     */
    fun redo() {
        if (codeEditor.canRedo()) {
            codeEditor.redo()
        }
    }
    
    /**
     * 设置字体大小
     */
    fun setTextSize(size: Float) {
        codeEditor.setTextSize(size)
    }
    
    /**
     * 设置颜色方案
     */
    fun setColorScheme(scheme: EditorColorScheme) {
        codeEditor.colorScheme = scheme
    }
}
