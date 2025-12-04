package com.wuxianggujun.tinaide.ui.fragment

import android.net.Uri
import android.os.Bundle
import android.view.View
import androidx.lifecycle.lifecycleScope
import com.wuxianggujun.tinaide.BuildConfig
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.wuxianggujun.tinaide.R
import com.wuxianggujun.tinaide.base.BaseBindingFragment
import com.wuxianggujun.tinaide.databinding.FragmentEditorBinding
import com.wuxianggujun.tinaide.core.lsp.CompileCommandsGenerator
import com.wuxianggujun.tinaide.core.lsp.CompileCommandsGenerator.BuildVariant
import com.wuxianggujun.tinaide.core.lsp.CppProjectScanner
import com.wuxianggujun.tinaide.core.lsp.LspConfig
import com.wuxianggujun.tinaide.core.lsp.NativeLspDocumentBridge
import com.wuxianggujun.tinaide.core.lsp.NativeLspRequestBridge
import com.wuxianggujun.tinaide.core.nativebridge.SysrootInstaller
import com.wuxianggujun.tinaide.editor.EditorDocumentExtras
import com.wuxianggujun.tinaide.editor.language.cpp.CppTreeSitterLanguageProvider
import com.wuxianggujun.tinaide.extensions.toastInfo
import com.wuxianggujun.tinaide.extensions.toastWarning
import com.wuxianggujun.tinaide.lsp.NativeLspService
import com.wuxianggujun.tinaide.lsp.model.CompletionResult
import com.wuxianggujun.tinaide.lsp.model.DiagnosticItem
import com.wuxianggujun.tinaide.lsp.model.HoverResult
import com.wuxianggujun.tinaide.lsp.model.Location
import io.github.rosemoe.sora.event.SelectionChangeEvent
import io.github.rosemoe.sora.event.SubscriptionReceipt
import io.github.rosemoe.sora.text.Content
import io.github.rosemoe.sora.widget.CodeEditor
import io.github.rosemoe.sora.widget.schemes.EditorColorScheme
import io.github.rosemoe.sora.widget.subscribeEvent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import io.github.rosemoe.sora.lang.diagnostic.DiagnosticDetail
import io.github.rosemoe.sora.lang.diagnostic.DiagnosticRegion
import io.github.rosemoe.sora.lang.diagnostic.DiagnosticsContainer

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
    private var nativeLspHandle: NativeLspDocumentBridge.Handle? = null
    private var hoverSubscription: SubscriptionReceipt<SelectionChangeEvent>? = null
    private var lastNativeHoverSignature: String? = null
    private var lastNativeCompletionSignature: String? = null
    private var lastNativeDefinitionSignature: String? = null
    private var diagnosticsListener: NativeLspService.DiagnosticsListener? = null
    private var currentFileUri: String? = null
    
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
                    val extras = EditorDocumentExtras.create(filePath = path, projectPath = projectPath)
                    codeEditor.setText(content, extras)
                } else {
                    android.util.Log.e(TAG, "File not found or not a file: $path")
                }
            } catch (e: Exception) {
                android.util.Log.e(TAG, "Error loading file: $path", e)
            }
        }
    }
    
    /**
     * 根据文件类型设置语言支持（Native LSP / Tree-sitter）
     */
    private fun setupLanguage() {
        val path = filePath ?: return

        if (!isCppFile(path)) {
            android.util.Log.d(TAG, "Not a C/C++ file: $path")
            clearDiagnostics()
            return
        }

        applyCppSyntaxHighlight()

        if (!shouldAttachNativeBridge(path)) {
            android.util.Log.i(TAG, "Native LSP disabled for $path, skip bridge attachment")
            clearDiagnostics()
            return
        }

        val projectDir = projectPath
        if (projectDir != null) {
            viewLifecycleOwner.lifecycleScope.launch {
                ensureCompileCommandsGenerated(projectDir)
                attachNativeBridge(path)
                subscribeDiagnostics(path)
            }
        } else {
            attachNativeBridge(path)
            subscribeDiagnostics(path)
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
    
    fun openNativeDefinitionPicker() {
        val path = filePath ?: run {
            requireContext().toastInfo("没有打开的文件")
            return
        }
        if (!shouldAttachNativeBridge(path)) {
            requireContext().toastInfo("Native LSP 仅支持 C/C++ 文件")
            return
        }
        val cursor = codeEditor.cursor
        NativeLspRequestBridge.requestDefinition(
            filePath = path,
            line = cursor.leftLine,
            column = cursor.leftColumn,
            workDir = projectPath
        ) { locations ->
            if (locations.isNullOrEmpty()) {
                requireContext().toastInfo("未找到定义")
            } else {
                showLocationDialog(
                    title = getString(R.string.native_definition_results),
                    locations = locations
                )
            }
        }
    }

    fun openNativeReferencesPicker(includeDeclaration: Boolean = true) {
        val path = filePath ?: run {
            requireContext().toastInfo("没有打开的文件")
            return
        }
        if (!shouldAttachNativeBridge(path)) {
            requireContext().toastInfo("Native LSP 仅支持 C/C++ 文件")
            return
        }
        val cursor = codeEditor.cursor
        NativeLspRequestBridge.requestReferences(
            filePath = path,
            line = cursor.leftLine,
            column = cursor.leftColumn,
            includeDeclaration = includeDeclaration,
            workDir = projectPath
        ) { locations ->
            if (locations.isNullOrEmpty()) {
                requireContext().toastInfo("未找到引用")
            } else {
                showLocationDialog(
                    title = getString(R.string.native_references_results),
                    locations = locations
                )
            }
        }
    }

    fun jumpToLocation(location: Location) {
        val targetLine = location.startLine.coerceAtLeast(0)
        val targetColumn = location.startCharacter.coerceAtLeast(0)
        binding.root.post {
            codeEditor.setSelection(targetLine, targetColumn)
        }
    }

    private fun showLocationDialog(title: String, locations: List<Location>) {
        val displayItems = locations.map { formatLocation(it) }.toTypedArray()
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(title)
            .setItems(displayItems) { _, index ->
                locations.getOrNull(index)?.let { navigateToLocation(it) }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun formatLocation(location: Location): String {
        val fileName = java.io.File(location.filePath).name
        val line = location.startLine + 1
        val column = location.startCharacter + 1
        return "$fileName:$line:$column"
    }

    private fun navigateToLocation(location: Location) {
        if (location.filePath == filePath) {
            jumpToLocation(location)
            return
        }
        val host = parentFragment as? EditorContainerFragment
        if (host != null) {
            host.openLocation(location)
        } else {
            requireContext().toastWarning("无法打开 ${location.filePath}")
        }
    }

    private fun shouldAttachNativeBridge(path: String): Boolean {
        return LspConfig.useNativeClient && isCppFile(path)
    }

    private fun applyCppSyntaxHighlight() {
        try {
            val language = CppTreeSitterLanguageProvider.create(requireContext())
            codeEditor.setEditorLanguage(language)
            android.util.Log.i(TAG, "Applied Tree-sitter language for: $filePath")
        } catch (e: Exception) {
            android.util.Log.e(TAG, "Failed to setup Tree-sitter highlighter", e)
        }
    }
    
    override fun onDestroyView() {
        nativeLspHandle?.dispose()
        nativeLspHandle = null
        hoverSubscription?.unsubscribe()
        hoverSubscription = null
        diagnosticsListener?.let { NativeLspService.removeDiagnosticsListener(it) }
        diagnosticsListener = null
        currentFileUri = null
        
        super.onDestroyView()
    }

    private fun subscribeNativeHover(path: String) {
        hoverSubscription?.unsubscribe()
        hoverSubscription = codeEditor.subscribeEvent<SelectionChangeEvent> { _, _ ->
            if (!shouldAttachNativeBridge(path)) {
                return@subscribeEvent
            }
            val cursor = codeEditor.cursor
            if (cursor.isSelected) {
                return@subscribeEvent
            }
            requestNativeHover(path, cursor.leftLine, cursor.leftColumn)
        }
    }

    private fun requestNativeHover(filePath: String, line: Int, column: Int) {
        NativeLspRequestBridge.requestHover(
            filePath = filePath,
            line = line,
            column = column,
            workDir = projectPath
        ) { result ->
            if (result != null) {
                showNativeHover(filePath, line, column, result)
            }
        }
    }

    private fun showNativeHover(filePath: String, line: Int, column: Int, result: HoverResult) {
        val normalized = result.content.trim()
        if (normalized.isEmpty()) {
            return
        }
        val signature = "${result.startLine}:${result.startCharacter}:$normalized"
        if (signature == lastNativeHoverSignature) {
            return
        }
        lastNativeHoverSignature = signature
        binding.root.post {
            requireContext().toastInfo("Native Hover: $normalized")
        }
        requestNativeCompletion(filePath, line, column)
        requestNativeDefinition(filePath, line, column)
    }

    private fun requestNativeCompletion(filePath: String, line: Int, column: Int) {
        NativeLspRequestBridge.requestCompletion(
            filePath = filePath,
            line = line,
            column = column,
            workDir = projectPath
        ) { result ->
            showNativeCompletion(line, column, result)
        }
    }

    private fun showNativeCompletion(line: Int, column: Int, result: CompletionResult?) {
        val first = result?.items?.firstOrNull() ?: return
        val signature = "$line:$column:${first.label}"
        if (signature == lastNativeCompletionSignature) {
            return
        }
        lastNativeCompletionSignature = signature
        binding.root.post {
            requireContext().toastInfo("Native Completion ▸ ${first.label}")
        }
    }

    private fun requestNativeDefinition(filePath: String, line: Int, column: Int) {
        NativeLspRequestBridge.requestDefinition(
            filePath = filePath,
            line = line,
            column = column,
            workDir = projectPath
        ) { locations ->
            showNativeDefinition(locations?.firstOrNull())
        }
    }

    private fun showNativeDefinition(location: Location?) {
        if (location == null) {
            return
        }
        val signature = "${location.filePath}:${location.startLine}:${location.startCharacter}"
        if (signature == lastNativeDefinitionSignature) {
            return
        }
        lastNativeDefinitionSignature = signature
        binding.root.post {
            val preview = "${location.filePath}:${location.startLine + 1}"
            requireContext().toastInfo("Native Definition ▸ $preview")
        }
    }

    private fun attachNativeBridge(filePath: String) {
        nativeLspHandle?.dispose()
        nativeLspHandle = NativeLspDocumentBridge.bind(
            requireContext().applicationContext,
            codeEditor,
            filePath,
            projectPath
        )
        subscribeNativeHover(filePath)
    }

    private fun subscribeDiagnostics(filePath: String) {
        val fileUri = Uri.fromFile(java.io.File(filePath)).toString()
        currentFileUri = fileUri
        diagnosticsListener?.let { NativeLspService.removeDiagnosticsListener(it) }
        val listener = NativeLspService.DiagnosticsListener { uri, diagnostics ->
            if (uri == currentFileUri) {
                applyDiagnostics(diagnostics)
            }
        }
        diagnosticsListener = listener
        NativeLspService.addDiagnosticsListener(listener)
        applyDiagnostics(NativeLspService.latestDiagnostics(fileUri))
    }

    private fun applyDiagnostics(diagnostics: List<DiagnosticItem>) {
        if (!isAdded) return
        val container = buildDiagnosticsContainer(diagnostics)
        binding.root.post {
            codeEditor.setDiagnostics(container)
        }
    }

    private fun buildDiagnosticsContainer(diagnostics: List<DiagnosticItem>): DiagnosticsContainer? {
        if (diagnostics.isEmpty()) {
            return null
        }
        val text = codeEditor.text
        val container = DiagnosticsContainer()
        diagnostics.forEach { item ->
            val start = safeCharIndex(text, item.startLine, item.startCharacter)
            val end = safeCharIndex(text, item.endLine, item.endCharacter).coerceAtLeast(start + 1)
            val severity = mapSeverity(item)
            val detail = DiagnosticDetail(
                briefMessage = item.message,
                detailedMessage = item.source ?: item.code ?: ""
            )
            val region = DiagnosticRegion(
                start,
                end,
                severity,
                item.code?.hashCode()?.toLong() ?: 0L,
                detail
            )
            container.addDiagnostic(region)
        }
        return container
    }

    private fun safeCharIndex(text: Content, line: Int, column: Int): Int {
        val clampedLine = line.coerceIn(0, (text.lineCount - 1).coerceAtLeast(0))
        val clampedColumn = column.coerceAtLeast(0)
        return runCatching { text.getCharIndex(clampedLine, clampedColumn) }.getOrDefault(0)
    }

    private fun mapSeverity(item: DiagnosticItem): Short = when (item.severity) {
        1 -> DiagnosticRegion.SEVERITY_ERROR
        2 -> DiagnosticRegion.SEVERITY_WARNING
        3 -> DiagnosticRegion.SEVERITY_TYPO
        4 -> DiagnosticRegion.SEVERITY_TYPO
        else -> DiagnosticRegion.SEVERITY_WARNING
    }

    private fun clearDiagnostics() {
        diagnosticsListener?.let { NativeLspService.removeDiagnosticsListener(it) }
        diagnosticsListener = null
        currentFileUri = null
        binding.root.post { codeEditor.setDiagnostics(null) }
    }

    private suspend fun ensureCompileCommandsGenerated(projectDir: String) {
        val variant = if (BuildConfig.DEBUG) BuildVariant.Debug else BuildVariant.Release
        val target = CompileCommandsGenerator.getCompileCommandsFile(projectDir, variant)
        if (target.exists()) {
            android.util.Log.d(TAG, "compile_commands.json already exists at ${target.absolutePath}")
            return
        }

        val context = requireContext().applicationContext
        val sysrootDir = runCatching { SysrootInstaller.ensureInstalled(context) }
            .onFailure {
                android.util.Log.e(TAG, "Sysroot installation failed", it)
                withContext(Dispatchers.Main) {
                    requireContext().toastWarning("未准备好 sysroot，无法生成 compile_commands.json")
                }
            }
            .getOrNull() ?: return

        val scan = CppProjectScanner.scanProject(projectDir)
        if (scan.sourceFiles.isEmpty()) {
            withContext(Dispatchers.Main) {
                requireContext().toastWarning("未找到任何 C/C++ 源文件，跳过 compile_commands 自动生成")
            }
            return
        }

        android.util.Log.i(
            TAG,
            "Generating compile_commands.json automatically (sources=${scan.sourceFiles.size})"
        )

        runCatching {
            CompileCommandsGenerator.generate(
                projectPath = projectDir,
                sysrootDir = sysrootDir,
                sourceFiles = scan.sourceFiles,
                includeDirs = scan.includeDirs,
                isCxx = scan.hasCppSources,
                variant = variant
            )
        }.onSuccess {
            withContext(Dispatchers.Main) {
                requireContext().toastInfo("已自动生成 compile_commands.json")
            }
        }.onFailure {
            android.util.Log.e(TAG, "Failed to auto generate compile_commands.json", it)
            withContext(Dispatchers.Main) {
                requireContext().toastWarning("生成 compile_commands.json 失败：${it.message}")
            }
        }
    }

    private fun isCppFile(path: String): Boolean {
        val ext = path.substringAfterLast('.', "").lowercase()
        return ext in setOf("c", "cpp", "cc", "cxx", "m", "mm", "h", "hpp", "hh", "hxx")
    }
}
