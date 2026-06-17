package com.wuxianggujun.tinaide.ui.compose.screens.main

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.wuxianggujun.tinaide.ai.integration.AiToolsIntegrationManager
import com.wuxianggujun.tinaide.ai.viewmodel.AiChatViewModel
import com.wuxianggujun.tinaide.core.compile.CompileProjectUseCase
import com.wuxianggujun.tinaide.core.compile.ProcessManager
import com.wuxianggujun.tinaide.core.linux.LinuxEnvironmentProvider
import com.wuxianggujun.tinaide.editor.IEditorManager
import com.wuxianggujun.tinaide.editor.symbol.ProjectSymbolIndexService
import com.wuxianggujun.tinaide.file.IFileOperations
import com.wuxianggujun.tinaide.file.IProjectContext
import com.wuxianggujun.tinaide.output.IOutputManager
import com.wuxianggujun.tinaide.ui.BottomPanelController
import com.wuxianggujun.tinaide.ui.BottomPanelViewModel
import com.wuxianggujun.tinaide.ui.GitViewModel
import com.wuxianggujun.tinaide.ui.compose.components.FileTreeState
import com.wuxianggujun.tinaide.ui.compose.state.editor.EditorContainerState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.koin.core.context.GlobalContext

@Composable
internal fun rememberMainActivityAiChatViewModel(): AiChatViewModel = remember {
    GlobalContext.get().get<AiChatViewModel>()
}

@Composable
internal fun mainActivityHostEffects(
    context: Context,
    lifecycleScope: CoroutineScope,
    projectContext: IProjectContext,
    fileTreeState: FileTreeState,
    gitViewModel: GitViewModel,
    uiScope: CoroutineScope,
    editorContainerState: EditorContainerState,
    bottomPanelViewModel: BottomPanelViewModel,
    processManager: ProcessManager,
    buildUiState: MainActivityBuildUiState,
    editorManager: IEditorManager,
    outputManager: IOutputManager,
    bottomPanelController: BottomPanelController,
): AiChatViewModel {
    val currentAiChatViewModel = rememberMainActivityAiChatViewModel()

    MainActivityAiToolsEffect(
        context = context,
        lifecycleScope = lifecycleScope,
        projectContext = projectContext,
        currentAiChatViewModel = currentAiChatViewModel,
        editorContainerState = editorContainerState,
        bottomPanelViewModel = bottomPanelViewModel,
        processManager = processManager,
        buildUiState = buildUiState,
        editorManager = editorManager,
        outputManager = outputManager,
        bottomPanelController = bottomPanelController,
    )

    MainActivityProjectEffects(
        projectContext = projectContext,
        fileTreeState = fileTreeState,
        gitViewModel = gitViewModel,
        uiScope = uiScope,
    )

    return currentAiChatViewModel
}

@Composable
internal fun MainActivityAiToolsEffect(
    context: Context,
    lifecycleScope: CoroutineScope,
    projectContext: IProjectContext,
    currentAiChatViewModel: AiChatViewModel,
    editorContainerState: EditorContainerState,
    bottomPanelViewModel: BottomPanelViewModel,
    processManager: ProcessManager,
    buildUiState: MainActivityBuildUiState,
    editorManager: IEditorManager,
    outputManager: IOutputManager,
    bottomPanelController: BottomPanelController,
) {
    val aiToolsManager = remember(currentAiChatViewModel) {
        val linuxEnvironmentProvider = GlobalContext.get().get<LinuxEnvironmentProvider>()
        val fileOperations = GlobalContext.get().get<IFileOperations>()
        AiToolsIntegrationManager(
            context = context,
            viewModel = currentAiChatViewModel,
            scope = lifecycleScope,
            linuxEnvironmentProvider = linuxEnvironmentProvider,
            fileOperations = fileOperations,
        )
    }

    LaunchedEffect(currentAiChatViewModel, editorContainerState, bottomPanelViewModel) {
        val projectRoot = projectContext.getCurrentProject()?.rootPath ?: return@LaunchedEffect
        val symbolIndexService = GlobalContext.getOrNull()?.getOrNull<ProjectSymbolIndexService>()
        val compileUseCase = GlobalContext.get().get<CompileProjectUseCase>()
        aiToolsManager.initializeAll(
            projectRoot = projectRoot,
            editorState = editorContainerState,
            symbolIndexService = symbolIndexService,
            processManager = processManager,
            runConfigManager = buildUiState.runConfigManager,
            bottomPanelViewModel = bottomPanelViewModel,
            editorManager = editorManager,
            outputManager = outputManager,
            compileProjectUseCase = compileUseCase,
            bottomPanelController = bottomPanelController,
        )
    }
}

@Composable
internal fun MainActivityProjectEffects(
    projectContext: IProjectContext,
    fileTreeState: FileTreeState,
    gitViewModel: GitViewModel,
    uiScope: CoroutineScope,
) {
    val projectRoot = projectContext.getCurrentProject()?.rootPath
    LaunchedEffect(projectRoot) {
        val rootPath = projectRoot ?: return@LaunchedEffect
        fileTreeState.loadRoot(rootPath)
        gitViewModel.setProjectPath(rootPath)
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, fileTreeState, uiScope) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> fileTreeState.setAppVisibility(true)
                Lifecycle.Event.ON_STOP -> fileTreeState.setAppVisibility(false)
                Lifecycle.Event.ON_RESUME -> {
                    if (fileTreeState.consumePendingResumeRefresh()) {
                        uiScope.launch { fileTreeState.refresh() }
                    }
                }
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }
}
