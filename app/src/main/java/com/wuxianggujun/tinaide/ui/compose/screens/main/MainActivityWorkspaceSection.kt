package com.wuxianggujun.tinaide.ui.compose.screens.main

import android.app.Activity
import androidx.compose.runtime.Composable
import com.wuxianggujun.tinaide.core.compile.ProcessManager
import com.wuxianggujun.tinaide.core.i18n.Strings
import com.wuxianggujun.tinaide.core.i18n.strOr
import com.wuxianggujun.tinaide.editor.IEditorManager
import com.wuxianggujun.tinaide.file.IProjectContext
import com.wuxianggujun.tinaide.output.IOutputManager
import com.wuxianggujun.tinaide.ui.BindMainActivityEditorHost
import com.wuxianggujun.tinaide.ui.BindPluginDiagnosticsProvider
import com.wuxianggujun.tinaide.ui.BindPluginHostCommandExecutor
import com.wuxianggujun.tinaide.ui.BindPluginHostEvents
import com.wuxianggujun.tinaide.ui.BottomPanelViewModel
import com.wuxianggujun.tinaide.ui.CompileActionsHelper
import com.wuxianggujun.tinaide.ui.DebugViewModel
import com.wuxianggujun.tinaide.ui.EditorStateViewModel
import com.wuxianggujun.tinaide.ui.GitViewModel
import com.wuxianggujun.tinaide.ui.MainActivityActionsDelegate
import com.wuxianggujun.tinaide.ui.MainActivityActionsViewModel
import com.wuxianggujun.tinaide.ui.MainActivityBottomPanelActionBridge
import com.wuxianggujun.tinaide.ui.MainActivityCompileDelegate
import com.wuxianggujun.tinaide.ui.MainActivityDialogCoordinator
import com.wuxianggujun.tinaide.ui.MainActivityEditorActionBridge
import com.wuxianggujun.tinaide.ui.MainActivityNavigationDelegate
import com.wuxianggujun.tinaide.ui.MainActivityShortcutDispatcher
import kotlinx.coroutines.CoroutineScope

@Composable
internal fun MainActivityWorkspaceSection(
    activity: Activity,
    lifecycleScope: CoroutineScope,
    projectContext: IProjectContext,
    processManager: ProcessManager,
    outputManager: IOutputManager,
    editorManager: IEditorManager,
    gitViewModel: GitViewModel,
    bottomPanelViewModel: BottomPanelViewModel,
    bottomPanelController: MainActivityBottomPanelActionBridge,
    actionsViewModel: MainActivityActionsViewModel,
    compileActionsHelper: CompileActionsHelper,
    editorStateViewModel: EditorStateViewModel,
    debugViewModel: DebugViewModel,
    actionsDelegate: MainActivityActionsDelegate,
    compileDelegate: MainActivityCompileDelegate,
    navigationDelegate: MainActivityNavigationDelegate,
    shortcutDispatcher: MainActivityShortcutDispatcher,
    editorActionBridge: MainActivityEditorActionBridge,
    dialogCoordinator: MainActivityDialogCoordinator,
    mainScreenState: MainActivityMainScreenState,
    editorHostState: MainActivityEditorHostState,
    callbacks: MainActivityWorkspaceCallbacks,
) {
    val buildUiState = mainScreenState.buildUiState
    val dialogState = mainScreenState.dialogState
    val drawerState = mainScreenState.drawerState
    val editorActionsState = mainScreenState.editorActionsState
    val fileTreeState = mainScreenState.fileTreeState
    val gitDialogState = mainScreenState.gitDialogState
    val locationDialogState = mainScreenState.locationDialogState
    val projectSnapshot = mainScreenState.projectSnapshot
    val screenUiState = mainScreenState.screenUiState
    val uiScope = mainScreenState.scope
    val editorContainerState = editorHostState.editorContainerState

    val currentAiChatViewModel = mainActivityHostEffects(
        context = activity,
        lifecycleScope = lifecycleScope,
        projectContext = projectContext,
        fileTreeState = fileTreeState,
        gitViewModel = gitViewModel,
        uiScope = uiScope,
        editorContainerState = editorContainerState,
        bottomPanelViewModel = bottomPanelViewModel,
        processManager = processManager,
        buildUiState = buildUiState,
        editorManager = editorManager,
        outputManager = outputManager,
        bottomPanelController = bottomPanelController,
    )

    BindMainActivityEditorHost(
        editorContainerState = editorContainerState,
        editorActionBridge = editorActionBridge,
        actionsDelegate = actionsDelegate,
        shortcutDispatcher = shortcutDispatcher,
        editorActionsState = editorActionsState,
        locationDialogState = locationDialogState,
        scope = uiScope,
        onFileNotExist = {
            callbacks.toastError(Strings.toast_file_not_exist.strOr(activity))
        },
        onToastInfo = callbacks.toastInfo,
        onToastError = callbacks.toastError,
    )

    val workspaceUi = rememberMainActivityWorkspaceUi(
        activity = activity,
        editorContainerState = editorContainerState,
        fileTreeState = fileTreeState,
        projectContext = projectContext,
        drawerState = drawerState,
        dialogState = dialogState,
        bottomPanelViewModel = bottomPanelViewModel,
        bottomPanelController = bottomPanelController,
        actionsViewModel = actionsViewModel,
        compileActionsHelper = compileActionsHelper,
        hostScope = lifecycleScope,
        onOpenRunConfig = { buildUiState.openRunConfigDialog() },
        callbacks = callbacks,
    )

    BindPluginHostCommandExecutor(workspaceUi.hostCommandExecutor)
    BindPluginDiagnosticsProvider(
        bottomPanelViewModel = bottomPanelViewModel,
        projectRootProvider = { projectSnapshot.rootPath },
    )
    BindPluginHostEvents(
        projectContext = projectContext,
        isCompiling = screenUiState.isCompiling,
    )

    MainActivityDrawerSection(
        uiState = screenUiState,
        fileTreeState = fileTreeState,
        editorContainerState = editorContainerState,
        dialogState = dialogState,
        gitDialogState = gitDialogState,
        currentAiChatViewModel = currentAiChatViewModel,
        drawerState = drawerState,
        buildUiState = buildUiState,
        hostCommandExecutor = workspaceUi.hostCommandExecutor,
        uiScope = uiScope,
        projectContext = projectContext,
        editorManager = editorManager,
        projectSymbolIndexService = editorHostState.projectSymbolIndexService,
        gitViewModel = gitViewModel,
        bottomPanelController = bottomPanelController,
        bottomPanelViewModel = bottomPanelViewModel,
        editorStateViewModel = editorStateViewModel,
        debugViewModel = debugViewModel,
        actionsDelegate = actionsDelegate,
        compileDelegate = compileDelegate,
        navigationDelegate = navigationDelegate,
        callbacks = workspaceUi.screenCallbacks,
    )

    MainActivityDialogsSection(
        editorActionsState = editorActionsState,
        locationDialogState = locationDialogState,
        dialogState = dialogState,
        buildUiState = buildUiState,
        gitUiState = screenUiState.gitUiState,
        gitDialogState = gitDialogState,
        projectName = screenUiState.projectName,
        projectRoot = projectSnapshot.projectRoot,
        buildDir = projectSnapshot.buildDir,
        showUnsavedExitDialog = dialogCoordinator.showUnsavedExitDialog,
        uiScope = uiScope,
        editorContainerState = editorContainerState,
        actionsViewModel = actionsViewModel,
        fileTreeState = fileTreeState,
        gitViewModel = gitViewModel,
        editorManager = editorManager,
        saveScope = lifecycleScope,
        onCloseProject = dialogCoordinator::closeProject,
        onPersistRunConfigManager = callbacks.screenCallbacks.onPersistRunConfigManager,
        onShowUnsavedExitDialogChange = dialogCoordinator::onShowUnsavedExitDialogChange,
        onFinish = activity::finish,
    )
}
