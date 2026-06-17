package com.wuxianggujun.tinaide.ui.compose.screens.main

import com.wuxianggujun.tinaide.core.compile.ProcessManager
import com.wuxianggujun.tinaide.editor.IEditorManager
import com.wuxianggujun.tinaide.file.IProjectContext
import com.wuxianggujun.tinaide.output.IOutputManager
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

internal data class MainActivityContentViewModels(
    val compiler: CompilerViewModel,
    val main: MainViewModel,
    val editorState: EditorStateViewModel,
    val debug: DebugViewModel,
    val git: GitViewModel,
    val bottomPanel: BottomPanelViewModel,
    val actions: MainActivityActionsViewModel,
)

internal data class MainActivityContentServices(
    val projectContext: IProjectContext,
    val editorManager: IEditorManager,
    val processManager: ProcessManager,
    val outputManager: IOutputManager,
)

internal data class MainActivityContentBridges(
    val fileTreeActions: MainActivityFileTreeActionBridge,
    val bottomPanelActions: MainActivityBottomPanelActionBridge,
    val editorActions: MainActivityEditorActionBridge,
)

internal data class MainActivityContentDelegates(
    val actions: MainActivityActionsDelegate,
    val compileActionsHelper: CompileActionsHelper,
    val compile: MainActivityCompileDelegate,
    val navigation: MainActivityNavigationDelegate,
    val shortcuts: MainActivityShortcutDispatcher,
    val dialogCoordinator: MainActivityDialogCoordinator,
    val workspaceActions: MainActivityWorkspaceActionsDelegate,
)

internal data class MainActivityExternalFileActions(
    val openWithExternalApp: (File) -> Unit,
    val shareFileOrDirectory: (File) -> Unit,
)
