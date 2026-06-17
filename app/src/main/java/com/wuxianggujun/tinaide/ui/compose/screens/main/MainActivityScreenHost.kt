package com.wuxianggujun.tinaide.ui.compose.screens.main

import android.app.Activity
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import com.wuxianggujun.tinaide.extensions.toastError
import com.wuxianggujun.tinaide.extensions.toastInfo
import com.wuxianggujun.tinaide.ui.BindMainActivityFileTreeState
import kotlinx.coroutines.CoroutineScope

@Composable
internal fun MainActivityScreenHost(
    activity: Activity,
    lifecycleScope: CoroutineScope,
    viewModels: MainActivityContentViewModels,
    services: MainActivityContentServices,
    bridges: MainActivityContentBridges,
    delegates: MainActivityContentDelegates,
    externalFileActions: MainActivityExternalFileActions,
) {
    val drawerWidth = 300.dp

    val mainScreenState = rememberMainActivityMainScreenState(
        drawerWidth = drawerWidth,
        projectContext = services.projectContext,
        initialRunConfigManager = viewModels.compiler.getRunConfigurationManager(),
        detectBuildSystem = { viewModels.compiler.detectBuildSystem() },
        loadAvailableTargets = { viewModels.compiler.getAvailableTargets() },
        mainViewModel = viewModels.main,
        editorStateViewModel = viewModels.editorState,
        debugViewModel = viewModels.debug,
        gitViewModel = viewModels.git,
    )
    val projectSnapshot = mainScreenState.projectSnapshot
    val fileTreeState = mainScreenState.fileTreeState

    val editorHostState = rememberMainActivityEditorHostState(
        editorManager = services.editorManager,
        projectRootPathProvider = { projectSnapshot.rootPath },
        onLspDiagnosticsChanged = viewModels.bottomPanel::replaceDiagnosticsForFile,
    )

    BindMainActivityFileTreeState(
        fileTreeState = fileTreeState,
        fileTreeActionBridge = bridges.fileTreeActions,
        editorContainerState = editorHostState.editorContainerState,
    )
    val workspaceCallbacks = rememberMainActivityWorkspaceCallbacksHost(
        mainScreenState = mainScreenState,
        workspaceActions = delegates.workspaceActions,
        dialogCoordinator = delegates.dialogCoordinator,
        toastInfo = activity::toastInfo,
        toastError = activity::toastError,
        onOpenWithExternalApp = externalFileActions.openWithExternalApp,
        onShareFileOrDirectory = externalFileActions.shareFileOrDirectory,
        onPersistRunConfigManager = viewModels.compiler::saveRunConfigurationManager,
        onGitRefresh = viewModels.git::loadCommitHistory,
    )

    MainActivityWorkspaceSection(
        activity = activity,
        lifecycleScope = lifecycleScope,
        projectContext = services.projectContext,
        processManager = services.processManager,
        outputManager = services.outputManager,
        editorManager = services.editorManager,
        gitViewModel = viewModels.git,
        bottomPanelViewModel = viewModels.bottomPanel,
        bottomPanelController = bridges.bottomPanelActions,
        actionsViewModel = viewModels.actions,
        compileActionsHelper = delegates.compileActionsHelper,
        editorStateViewModel = viewModels.editorState,
        debugViewModel = viewModels.debug,
        actionsDelegate = delegates.actions,
        compileDelegate = delegates.compile,
        navigationDelegate = delegates.navigation,
        shortcutDispatcher = delegates.shortcuts,
        editorActionBridge = bridges.editorActions,
        dialogCoordinator = delegates.dialogCoordinator,
        mainScreenState = mainScreenState,
        editorHostState = editorHostState,
        callbacks = workspaceCallbacks,
    )
}
