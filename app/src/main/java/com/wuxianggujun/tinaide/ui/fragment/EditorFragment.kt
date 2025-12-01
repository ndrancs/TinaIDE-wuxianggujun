package com.wuxianggujun.tinaide.ui.fragment

import android.os.Bundle
import android.view.View
import com.wuxianggujun.tinaide.base.BaseBindingFragment
import com.wuxianggujun.tinaide.databinding.FragmentEditorBinding
import com.wuxianggujun.tinaide.R
import com.wuxianggujun.tinaide.core.editor.ClangLanguage
import com.wuxianggujun.tinaide.core.lsp.LspEditorManager
import com.wuxianggujun.tinaide.core.nativebridge.SysrootInstaller
import io.github.rosemoe.sora.lsp.editor.LspEditor
import io.github.rosemoe.sora.widget.CodeEditor
import io.github.rosemoe.sora.langs.textmate.TextMateColorScheme
import io.github.rosemoe.sora.widget.schemes.EditorColorScheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 编辑器 Fragment
 * 包含 SoraEditor 实例
 * 支持 LSP (clangd) 和 libclang 两种语言支持方式
 */
class EditorFragment : BaseBindingFragment<FragmentEditorBinding>(
    FragmentEditorBinding::inflate
) {
    private lateinit var codeEditor: CodeEditor
    private var filePath: String? = null
    private var lspEditor: LspEditor? = null
    
    // ��否优先使用 LSP（可通过设置控制）
    private var preferLsp: Boolean = true
    
    companion object {
        private const val ARG_FILE_PATH = "file_path"
        private const val ARG_PROJECT_PATH = "project_path"
        private const val ARG_PREFER_LSP = "prefer_lsp"
        private const val TAG = "EditorFragment"
        
        fun newInstance(
            filePath: String,
            projectPath: String? = null,
            preferLsp: Boolean = true
        ): EditorFragment {
            return EditorFragment().apply {
                arguments = Bundle().apply {
                    putString(ARG_FILE_PATH, filePath)
                    putString(ARG_PROJECT_PATH, projectPath ?: java.io.File(filePath).parent)
                    putBoolean(ARG_PREFER_LSP, preferLsp)
                }
            }
        }
    }
    
    private val projectPath: String?
        get() = arguments?.getString(ARG_PROJECT_PATH)
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        filePath = arguments?.getString(ARG_FILE_PATH)
        preferLsp = arguments?.getBoolean(ARG_PREFER_LSP, true) ?: true
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
     * 优先尝试 LSP (clangd)，如果不可用则回退到 libclang
     */
    private fun setupLanguage() {
        val path = filePath ?: return
        
        // 检查是否为 C/C++ 文件
        if (!ClangLanguage.isCOrCppFile(path)) {
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
                
                // 尝试使用 LSP
                if (preferLsp && lspManager.isAvailable()) {
                    setupLspLanguage(path, lspManager)
                } else {
                    // 回退到 libclang
                    setupClangLanguage(path, sysrootDir)
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
            // 生成 compile_commands.json（如果不存在）
            val compileCommandsFile = java.io.File(projPath, "compile_commands.json")
            if (!compileCommandsFile.exists()) {
                // 收集项目中的源文件
                val sourceFiles = collectSourceFiles(projPath)
                val includeDirs = collectIncludeDirs(projPath)
                
                if (sourceFiles.isNotEmpty()) {
                    lspManager.generateCompileCommands(
                        projectPath = projPath,
                        sourceFiles = sourceFiles,
                        includeDirs = includeDirs,
                        isCxx = path.endsWith(".cpp") || path.endsWith(".cc") || path.endsWith(".cxx")
                    )
                }
            }
            
            // 创建 LSP 编辑器
            lspEditor = lspManager.createLspEditor(path, projPath, codeEditor)
            
            if (lspEditor != null) {
                android.util.Log.i(TAG, "LSP editor created successfully for: $path")
            } else {
                android.util.Log.w(TAG, "Failed to create LSP editor, falling back to ClangLanguage")
                // 回退到 libclang
                val sysrootDir = SysrootInstaller.ensureInstalled(requireContext().applicationContext)
                setupClangLanguage(path, sysrootDir)
            }
        } catch (e: Exception) {
            android.util.Log.e(TAG, "Error setting up LSP, falling back to ClangLanguage", e)
            // 回退到 libclang
            val sysrootDir = SysrootInstaller.ensureInstalled(requireContext().applicationContext)
            setupClangLanguage(path, sysrootDir)
        }
    }
    
    /**
     * 设置 libclang 语言支持（回退方案）
     */
    private suspend fun setupClangLanguage(path: String, sysrootDir: java.io.File) {
        android.util.Log.i(TAG, "Setting up ClangLanguage for: $path")
        
        // 获取目标架构
        val abi = android.os.Build.SUPPORTED_ABIS.firstOrNull() ?: "arm64-v8a"
        val target = when {
            abi.contains("arm64", ignoreCase = true) -> "aarch64-linux-android28"
            abi.contains("x86_64", ignoreCase = true) -> "x86_64-linux-android28"
            else -> "aarch64-linux-android28"
        }
        
        // 获取项目 include 目录
        val projectDir = java.io.File(path).parentFile
        val includeDirs = mutableListOf<String>()
        projectDir?.let { dir ->
            listOf("include", "includes", "src").forEach { sub ->
                val subDir = java.io.File(dir, sub)
                if (subDir.exists()) {
                    includeDirs.add(subDir.absolutePath)
                }
            }
        }
        
        val language = ClangLanguage(
            sysroot = sysrootDir.absolutePath,
            filePath = path,
            target = target,
            includeDirs = includeDirs
        )
        
        withContext(Dispatchers.Main) {
            if (isAdded && !isDetached) {
                codeEditor.setEditorLanguage(language)
                android.util.Log.d(TAG, "ClangLanguage set for: $path")
            }
        }
    }
    
    /**
     * 收集项目中的源文件
     */
    private fun collectSourceFiles(projectPath: String): List<String> {
        val sourceFiles = mutableListOf<String>()
        val projectDir = java.io.File(projectPath)
        
        val extensions = setOf("c", "cpp", "cc", "cxx")
        
        projectDir.walkTopDown()
            .filter { it.isFile && it.extension.lowercase() in extensions }
            .forEach { sourceFiles.add(it.absolutePath) }
        
        return sourceFiles
    }
    
    /**
     * 收集项目中的 include 目录
     */
    private fun collectIncludeDirs(projectPath: String): List<String> {
        val includeDirs = mutableListOf<String>()
        val projectDir = java.io.File(projectPath)
        
        // 常见的 include 目录名
        val includeNames = setOf("include", "includes", "inc", "src", "headers")
        
        projectDir.walkTopDown()
            .filter { it.isDirectory && it.name.lowercase() in includeNames }
            .forEach { includeDirs.add(it.absolutePath) }
        
        // 添加项目根目录
        includeDirs.add(projectPath)
        
        return includeDirs
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
