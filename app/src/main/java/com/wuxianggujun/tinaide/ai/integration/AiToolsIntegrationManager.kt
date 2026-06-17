package com.wuxianggujun.tinaide.ai.integration

import android.content.Context
import com.wuxianggujun.tinaide.ai.viewmodel.AiChatViewModel
import com.wuxianggujun.tinaide.core.compile.ProcessManager
import com.wuxianggujun.tinaide.core.compile.RunConfigurationManager
import com.wuxianggujun.tinaide.core.linux.LinuxEnvironmentProvider
import com.wuxianggujun.tinaide.editor.symbol.ProjectSymbolIndexService
import com.wuxianggujun.tinaide.file.IFileOperations
import com.wuxianggujun.tinaide.ui.BottomPanelController
import com.wuxianggujun.tinaide.ui.BottomPanelViewModel
import com.wuxianggujun.tinaide.ui.compose.state.editor.EditorContainerState
import kotlinx.coroutines.CoroutineScope

/**
 * AI 工具集成管理器
 *
 * 负责初始化和管理所有 AI 工具回调
 * 所有回调实现都在 app 模块中，直接集成当前编辑器状态能力
 */
class AiToolsIntegrationManager(
    private val context: Context,
    private val viewModel: AiChatViewModel,
    private val scope: CoroutineScope,
    private val linuxEnvironmentProvider: LinuxEnvironmentProvider,
    private val fileOperations: IFileOperations
) {
    /**
     * 初始化项目上下文
     */
    fun initializeProject(
        projectRoot: String,
        editorState: EditorContainerState
    ) {
        viewModel.initializeProjectContext(
            projectRoot = projectRoot,
            getCurrentFile = {
                editorState.getActiveFileAbsolutePathOrNull()
            },
            getCurrentFileContent = {
                editorState.readActiveTabText()
            }
        )
    }

    /**
     * 注册所有回调
     */
    fun registerCallbacks(
        projectRoot: String,
        editorState: EditorContainerState,
        symbolIndexService: ProjectSymbolIndexService?,
        processManager: ProcessManager,
        runConfigManager: RunConfigurationManager,
        bottomPanelViewModel: BottomPanelViewModel,
        editorManager: com.wuxianggujun.tinaide.editor.IEditorManager,
        outputManager: com.wuxianggujun.tinaide.output.IOutputManager,
        compileProjectUseCase: com.wuxianggujun.tinaide.core.compile.CompileProjectUseCase,
        bottomPanelController: BottomPanelController
    ) {
        val editorCallbacks = EditorToolCallbacksImpl(
            context = context,
            editorState = editorState,
            projectRoot = projectRoot,
            linuxEnvironmentProvider = linuxEnvironmentProvider
        )
        viewModel.setEditorCallbacks(editorCallbacks)

        val fileSystemCallbacks = FileSystemCallbacksImpl(
            context = context,
            projectRoot = projectRoot,
            editorState = editorState,
            fileOperations = fileOperations
        )
        viewModel.setFileSystemCallbacks(fileSystemCallbacks)

        val codeAnalysisCallbacks = CodeAnalysisCallbacksImpl(projectRoot, symbolIndexService)
        viewModel.setCodeAnalysisCallbacks(codeAnalysisCallbacks)

        val diagnosticsCallbacks = DiagnosticsCallbacksImpl(bottomPanelViewModel, projectRoot)
        viewModel.setDiagnosticsCallbacks(diagnosticsCallbacks)

        val executionCallbacks = ExecutionCallbacksImpl(
            projectRoot = projectRoot,
            processManager = processManager,
            runConfigManager = runConfigManager,
            editorManager = editorManager,
            outputManager = outputManager,
            compileProjectUseCase = compileProjectUseCase,
            scope = scope,
            bottomPanelViewModel = bottomPanelViewModel,
            bottomPanelController = bottomPanelController
        )
        viewModel.setExecutionCallbacks(executionCallbacks)
    }

    /**
     * 完整初始化
     */
    fun initializeAll(
        projectRoot: String,
        editorState: EditorContainerState,
        symbolIndexService: ProjectSymbolIndexService?,
        processManager: ProcessManager,
        runConfigManager: RunConfigurationManager,
        bottomPanelViewModel: BottomPanelViewModel,
        editorManager: com.wuxianggujun.tinaide.editor.IEditorManager,
        outputManager: com.wuxianggujun.tinaide.output.IOutputManager,
        compileProjectUseCase: com.wuxianggujun.tinaide.core.compile.CompileProjectUseCase,
        bottomPanelController: BottomPanelController
    ) {
        initializeProject(projectRoot, editorState)
        registerCallbacks(
            projectRoot = projectRoot,
            editorState = editorState,
            symbolIndexService = symbolIndexService,
            processManager = processManager,
            runConfigManager = runConfigManager,
            bottomPanelViewModel = bottomPanelViewModel,
            editorManager = editorManager,
            outputManager = outputManager,
            compileProjectUseCase = compileProjectUseCase,
            bottomPanelController = bottomPanelController
        )
    }
}
