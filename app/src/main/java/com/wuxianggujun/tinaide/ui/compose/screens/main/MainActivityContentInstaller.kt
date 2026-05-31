package com.wuxianggujun.tinaide.ui.compose.screens.main

import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.lifecycle.lifecycleScope
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
import com.wuxianggujun.tinaide.ui.theme.TinaIDETheme
import java.io.File

internal fun installMainActivityContent(
    activity: ComponentActivity,
    projectContext: IProjectContext,
    compilerViewModel: CompilerViewModel,
    mainViewModel: MainViewModel,
    editorStateViewModel: EditorStateViewModel,
    debugViewModel: DebugViewModel,
    gitViewModel: GitViewModel,
    editorManager: IEditorManager,
    fileTreeActionBridge: MainActivityFileTreeActionBridge,
    processManager: com.wuxianggujun.tinaide.core.compile.ProcessManager,
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
    activity.setContent {
        TinaIDETheme {
            MainActivityScreenHost(
                activity = activity,
                lifecycleScope = activity.lifecycleScope,
                projectContext = projectContext,
                compilerViewModel = compilerViewModel,
                mainViewModel = mainViewModel,
                editorStateViewModel = editorStateViewModel,
                debugViewModel = debugViewModel,
                gitViewModel = gitViewModel,
                editorManager = editorManager,
                fileTreeActionBridge = fileTreeActionBridge,
                processManager = processManager,
                outputManager = outputManager,
                bottomPanelViewModel = bottomPanelViewModel,
                bottomPanelController = bottomPanelController,
                actionsViewModel = actionsViewModel,
                compileActionsHelper = compileActionsHelper,
                actionsDelegate = actionsDelegate,
                compileDelegate = compileDelegate,
                navigationDelegate = navigationDelegate,
                shortcutDispatcher = shortcutDispatcher,
                editorActionBridge = editorActionBridge,
                dialogCoordinator = dialogCoordinator,
                workspaceActions = workspaceActions,
                onOpenWithExternalApp = onOpenWithExternalApp,
                onShareFileOrDirectory = onShareFileOrDirectory,
            )
        }
    }
}
