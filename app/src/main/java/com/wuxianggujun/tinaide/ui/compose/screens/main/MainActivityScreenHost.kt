package com.wuxianggujun.tinaide.ui.compose.screens.main

import android.app.Activity
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import com.wuxianggujun.tinaide.core.compile.ProcessManager
import com.wuxianggujun.tinaide.editor.IEditorManager
import com.wuxianggujun.tinaide.extensions.toastError
import com.wuxianggujun.tinaide.extensions.toastInfo
import com.wuxianggujun.tinaide.file.IProjectContext
import com.wuxianggujun.tinaide.output.IOutputManager
import com.wuxianggujun.tinaide.ui.BindMainActivityFileTreeState
import com.wuxianggujun.tinaide.ui.BottomPanelViewModel
import com.wuxianggujun.tinaide.ui.CompileActionsHelper
import com.wuxianggujun.tinaide.ui.CompilerViewModel
import com.wuxianggujun.tinaide.ui.DebugViewModel
import com.wuxianggujun.tinaide.ui.EditorStateViewModel
import com.wuxianggujun.tinaide.ui.GitViewModel
import com.wuxianggujun.tinaide.ui.MainActivityActionsDelegate
import com.wuxianggujun.tinaide.ui.MainActivityActionsViewModel
import com.wuxianggujun.tinaide.ui.MainActivityBottomPanelActionBridge
import com.wuxianggujun.tinaide.ui.MainActivityCompileDelegate
import com.wuxianggujun.tinaide.ui.MainActivityDialogCoordinator
import com.wuxianggujun.tinaide.ui.MainActivityEditorActionBridge
import com.wuxianggujun.tinaide.ui.MainActivityFileTreeActionBridge
import com.wuxianggujun.tinaide.ui.MainActivityNavigationDelegate
import com.wuxianggujun.tinaide.ui.MainActivityShortcutDispatcher
import com.wuxianggujun.tinaide.ui.MainActivityWorkspaceActionsDelegate
import com.wuxianggujun.tinaide.ui.MainViewModel
import java.io.File
import kotlinx.coroutines.CoroutineScope

@Composable
internal fun MainActivityScreenHost(
    activity: Activity,
    lifecycleScope: CoroutineScope,
    projectContext: IProjectContext,
    compilerViewModel: CompilerViewModel,
    mainViewModel: MainViewModel,
    editorStateViewModel: EditorStateViewModel,
    debugViewModel: DebugViewModel,
    gitViewModel: GitViewModel,
    editorManager: IEditorManager,
    fileTreeActionBridge: MainActivityFileTreeActionBridge,
    processManager: ProcessManager,
    outputManager: IOutputManager,
    bottomPanelViewModel: BottomPanelViewModel,
    bottomPanelController: MainActivityBottomPanelActionBridge,
    actionsViewModel: MainActivityActionsViewModel,
    compileActionsHelper: CompileActionsHelper,
    actionsDelegate: MainActivityActionsDelegate,
    compileDelegate: MainActivityCompileDelegate,
    navigationDelegate: MainActivityNavigationDelegate,
    shortcutDispatcher: MainActivityShortcutDispatcher,
    editorActionBridge: MainActivityEditorActionBridge,
    dialogCoordinator: MainActivityDialogCoordinator,
    workspaceActions: MainActivityWorkspaceActionsDelegate,
    onOpenWithExternalApp: (File) -> Unit,
    onShareFileOrDirectory: (File) -> Unit,
) {
    val drawerWidth = 300.dp

    val mainScreenState = rememberMainActivityMainScreenState(
        drawerWidth = drawerWidth,
        projectContext = projectContext,
        initialRunConfigManager = compilerViewModel.getRunConfigurationManager(),
        detectBuildSystem = { compilerViewModel.detectBuildSystem() },
        loadAvailableTargets = { compilerViewModel.getAvailableTargets() },
        mainViewModel = mainViewModel,
        editorStateViewModel = editorStateViewModel,
        debugViewModel = debugViewModel,
        gitViewModel = gitViewModel,
    )
    val projectSnapshot = mainScreenState.projectSnapshot
    val fileTreeState = mainScreenState.fileTreeState

    val editorHostState = rememberMainActivityEditorHostState(
        editorManager = editorManager,
        projectRootPathProvider = { projectSnapshot.rootPath },
        onLspDiagnosticsChanged = bottomPanelViewModel::replaceDiagnosticsForFile,
    )

    BindMainActivityFileTreeState(
        fileTreeState = fileTreeState,
        fileTreeActionBridge = fileTreeActionBridge,
        editorContainerState = editorHostState.editorContainerState,
    )
    val workspaceCallbacks = rememberMainActivityWorkspaceCallbacksHost(
        mainScreenState = mainScreenState,
        workspaceActions = workspaceActions,
        dialogCoordinator = dialogCoordinator,
        toastInfo = activity::toastInfo,
        toastError = activity::toastError,
        onOpenWithExternalApp = onOpenWithExternalApp,
        onShareFileOrDirectory = onShareFileOrDirectory,
        onPersistRunConfigManager = compilerViewModel::saveRunConfigurationManager,
        onGitRefresh = gitViewModel::loadCommitHistory,
    )

    MainActivityWorkspaceSection(
        activity = activity,
        lifecycleScope = lifecycleScope,
        projectContext = projectContext,
        processManager = processManager,
        outputManager = outputManager,
        editorManager = editorManager,
        gitViewModel = gitViewModel,
        bottomPanelViewModel = bottomPanelViewModel,
        bottomPanelController = bottomPanelController,
        actionsViewModel = actionsViewModel,
        compileActionsHelper = compileActionsHelper,
        editorStateViewModel = editorStateViewModel,
        debugViewModel = debugViewModel,
        actionsDelegate = actionsDelegate,
        compileDelegate = compileDelegate,
        navigationDelegate = navigationDelegate,
        shortcutDispatcher = shortcutDispatcher,
        editorActionBridge = editorActionBridge,
        dialogCoordinator = dialogCoordinator,
        mainScreenState = mainScreenState,
        editorHostState = editorHostState,
        callbacks = workspaceCallbacks,
    )
}
