package com.wuxianggujun.tinaide.ui.fragment

import android.os.Bundle
import android.view.View
import com.wuxianggujun.tinaide.BuildConfig
import com.wuxianggujun.tinaide.base.BaseBindingFragment
import com.wuxianggujun.tinaide.databinding.FragmentEditorBinding
import com.wuxianggujun.tinaide.R
import com.wuxianggujun.tinaide.core.lsp.ClangdServerDefinition
import com.wuxianggujun.tinaide.core.lsp.LspEditorManager
import com.wuxianggujun.tinaide.core.lsp.LspEditorManager.BuildType
import com.wuxianggujun.tinaide.core.nativebridge.SysrootInstaller
import com.wuxianggujun.tinaide.extensions.toastInfo
import io.github.rosemoe.sora.lsp.editor.LspEditor
import io.github.rosemoe.sora.widget.CodeEditor
import io.github.rosemoe.sora.widget.schemes.EditorColorScheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 编辑器 Fragment
 * 包含 SoraEditor 实例
 * 使用 LSP (clangd) 提供 C/C++ 语言支持
 */
class EditorFragment : BaseBindingFragment<FragmentEditorBinding>(
    FragmentEditorBinding::inflate
) {
    private lateinit var codeEditor: CodeEditor
    private var filePath: String? = null
    private var lspEditor: LspEditor? = null
    
    companion object {
        private const val ARG_FILE_PATH = "file_path"
        private const val ARG_PROJECT_PATH = "project_path"
        private const val TAG = "EditorFragment"
        
        fun newInstance(
            filePath: String,
            projectPath: String? = null
        ): EditorFragment {
            return EditorFragment().apply {
                arguments = Bundle().apply {
                    putString(ARG_FILE_PATH, filePath)
                    putString(ARG_PROJECT_PATH, projectPath ?: java.io.File(filePath).parent)
                }
            }
        }
    }
    
    private val projectPath: String?
        get() = arguments?.getString(ARG_PROJECT_PATH)
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        filePath = arguments?.getString(ARG_FILE_PATH)
    }
    
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        
        codeEditor = binding.codeEditor
        setupEditor()
        loadFileContent()
        setupLanguage()
    }
    
    private fun loadFileContent() {
        filePath?.let { path ->
            try {
                val file = java.io.File(path)
                if (file.exists() && file.isFile) {
                    val content = file.readText()
                    android.util.Log.d(TAG, "Loading file: $path, content length: ${content.length}")
                    codeEditor.setText(content)
                } else {
                    android.util.Log.e(TAG, "File not found or not a file: $path")
                }
            } catch (e: Exception) {
                android.util.Log.e(TAG, "Error loading file: $path", e)
            }
        }
    }
    
    /**
     * 根据文件类型设置语言支持
     * 使用 LSP (clangd) 提供 C/C++ 语言支持
     */
    private fun setupLanguage() {
        val path = filePath ?: return
        
        // 检查是否为 C/C++ 文件
        if (!ClangdServerDefinition.isCppFile(path)) {
            android.util.Log.d(TAG, "Not a C/C++ file: $path")
            return
        }
        
        // 异步初始化语言支持
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val context = requireContext().applicationContext
                val sysrootDir = SysrootInstaller.ensureInstalled(context)
                
                // 初始化 LSP 管理器
                val lspManager = LspEditorManager.getInstance(context)
                lspManager.initialize(sysrootDir)
                lspManager.buildType = if (BuildConfig.DEBUG) BuildType.Debug else BuildType.Release
                
                // 使用 LSP
                if (lspManager.isAvailable()) {
                    setupLspLanguage(path, lspManager)
                } else {
                    android.util.Log.w(TAG, "LSP not available for: $path")
                    android.util.Log.w(TAG, "Please ensure libclangd.so is installed in sysroot/usr/lib/<triple>/runtime/")
                }
            } catch (e: Exception) {
                android.util.Log.e(TAG, "Error setting up language support", e)
            }
        }
    }
    
    /**
     * 设置 LSP 语言支持 (clangd)
     */
    private suspend fun setupLspLanguage(path: String, lspManager: LspEditorManager) {
        val projPath = projectPath ?: java.io.File(path).parent ?: return
        
        android.util.Log.i(TAG, "Setting up LSP for: $path (project: $projPath)")
        
        try {
            val compileCommandsFile = lspManager.getCompileCommandsFile(projPath, lspManager.buildType)
            if (!compileCommandsFile.exists()) {
                withContext(Dispatchers.Main) {
                    requireContext().toastInfo("未检测到 compile_commands.json，请在右上角菜单中手动生成后重新打开文件")
                }
                return
            }

            // 创建 LSP 编辑器
            lspEditor = lspManager.createLspEditor(path, projPath, codeEditor)
            
            if (lspEditor != null) {
                android.util.Log.i(TAG, "LSP editor created successfully for: $path")
            } else {
                android.util.Log.w(TAG, "Failed to create LSP editor for: $path")
                android.util.Log.w(TAG, "Check logcat for ClangdConnectionProvider errors")
            }
        } catch (e: Exception) {
            android.util.Log.e(TAG, "Error setting up LSP for: $path", e)
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
    
    /**
     * 获取 LSP 编辑器实例（如果使用 LSP）
     */
    fun getLspEditor(): LspEditor? {
        return lspEditor
    }
    
    /**
     * 检查是否正在使用 LSP
     */
    fun isUsingLsp(): Boolean {
        return lspEditor?.isConnected == true
    }
    
    override fun onDestroyView() {
        // 关闭 LSP 编辑器
        filePath?.let { path ->
            LspEditorManager.getInstance(requireContext().applicationContext).closeEditor(path)
        }
        lspEditor = null
        
        super.onDestroyView()
    }
}
