package com.wuxianggujun.tinaide.ui.compose.screens.main

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ExitToApp
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.wuxianggujun.tinaide.core.commands.HostCommands
import com.wuxianggujun.tinaide.core.compile.RunConfiguration
import com.wuxianggujun.tinaide.core.compile.RunConfigurationManager
import com.wuxianggujun.tinaide.core.config.DebugToolbarPosition
import com.wuxianggujun.tinaide.core.config.Prefs
import com.wuxianggujun.tinaide.core.i18n.Drawables
import com.wuxianggujun.tinaide.core.i18n.Strings
import com.wuxianggujun.tinaide.ui.DebugViewModel
import com.wuxianggujun.tinaide.ui.compose.components.DebugBar
import com.wuxianggujun.tinaide.ui.compose.components.DebugStatus
import com.wuxianggujun.tinaide.ui.compose.components.RunConfigSelector
import com.wuxianggujun.tinaide.ui.compose.icons.rememberTinaPainter
import com.wuxianggujun.tinaide.ui.compose.state.editor.EditorContainerState.SplitEditorLayout

internal class TopBarCallbacks(
    val onOpenDrawer: () -> Unit,
    val onOpenCommandPalette: () -> Unit,
    val onBuild: () -> Unit,
    val onCompile: () -> Unit,
    val onRebuildAndRun: () -> Unit,
    val onCompileInTerminal: () -> Unit,
    val onDebug: () -> Unit,
    val onSave: () -> Unit,
    val onSaveAll: () -> Unit,
    val onFormatCode: () -> Unit,
    val onGotoLine: () -> Unit,
    val onNavigateBack: () -> Unit = {},
    val onNavigateForward: () -> Unit = {},
    val onPeekDefinition: () -> Unit = {},
    val onGotoDefinition: () -> Unit = {},
    val onFindReferences: () -> Unit = {},
    val onGotoTypeDefinition: () -> Unit = {},
    val onGotoImplementation: () -> Unit = {},
    val onCallHierarchyIncoming: () -> Unit = {},
    val onCodeActions: () -> Unit = {},
    val onRenameSymbol: () -> Unit = {},
    val onSwitchHeaderSource: () -> Unit = {},
    val onToggleSplitEditor: () -> Unit = {},
    val onSetSplitEditorLayout: (SplitEditorLayout) -> Unit = {},
    val onMoveTabToSecondaryPane: () -> Unit = {},
    val onCopyTabToSecondaryPane: () -> Unit = {},
    val onOpenExplorer: () -> Unit,
    val onOpenGlobalSearch: () -> Unit,
    val onOpenBookmarks: () -> Unit,
    val onToggleBookmark: () -> Unit,
    val onPrevBookmark: () -> Unit,
    val onNextBookmark: () -> Unit,
    val onOpenTerminal: () -> Unit,
    val onOpenSettings: () -> Unit,
    val onExitWorkspace: () -> Unit,
    val onPackageApk: () -> Unit = {},
    val onCmakeOpenArtifactsDir: () -> Unit = {},
    val onCmakeReconfigure: () -> Unit = {},
    val onCmakeCleanAndReconfigure: () -> Unit = {},
    val onCmakeClearBuildDir: () -> Unit = {},
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun MainActivityTopBar(
    scrollBehavior: TopAppBarScrollBehavior? = null,
    isCompiling: Boolean,
    isDirty: Boolean,
    isDebugActive: Boolean,
    debugStatus: DebugStatus,
    runConfigManager: RunConfigurationManager,
    onRunConfigManagerChange: (RunConfigurationManager) -> Unit,
    onEditConfig: (RunConfiguration?) -> Unit,
    onShowRunConfigDialog: () -> Unit,
    callbacks: TopBarCallbacks,
    debugViewModel: DebugViewModel,
    quickCommands: List<MainActivityCommand>,
    onExecuteCommand: (MainActivityCommand) -> Unit,
) {
    val debugToolbarPosition by Prefs.debugToolbarPositionFlow.collectAsStateWithLifecycle()
    val showDebugBarInTop =
        isDebugActive && debugToolbarPosition != DebugToolbarPosition.BOTTOM

    @Composable
    fun CommandPaletteButton() {
        IconButton(onClick = callbacks.onOpenCommandPalette) {
            Icon(
                imageVector = Icons.Default.Search,
                contentDescription = stringResource(Strings.command_palette_title),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }

    @Composable
    fun QuickCommandButton(command: MainActivityCommand) {
        val title = command.titleText()
        IconButton(
            onClick = { onExecuteCommand(command) },
            enabled = command.enabled
        ) {
            Icon(
                imageVector = command.iconVector(),
                contentDescription = title,
                tint = if (command.enabled) {
                    MaterialTheme.colorScheme.onSurfaceVariant
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f)
                },
                modifier = Modifier.size(20.dp)
            )
        }
    }

    TopAppBar(
        expandedHeight = 48.dp,
        title = {
            val screenWidthPx = LocalWindowInfo.current.containerSize.width
            val compactTitleWidthPx = with(LocalDensity.current) { 360.dp.toPx() }
            val useCompactTitleLayout = screenWidthPx < compactTitleWidthPx
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = androidx.compose.foundation.layout.Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (showDebugBarInTop) {
                    DebugBar(
                        debugStatus = debugStatus,
                        onContinue = { debugViewModel.continueExecution() },
                        onStepOver = { debugViewModel.stepOver() },
                        onStepInto = { debugViewModel.stepInto() },
                        onStepOut = { debugViewModel.stepOut() },
                        onPause = { debugViewModel.pauseExecution() },
                        onStop = { debugViewModel.stopSession() },
                        modifier = if (useCompactTitleLayout) Modifier.fillMaxWidth() else Modifier
                    )
                } else if (!isDebugActive) {
                    val defaultRunConfigName = stringResource(Strings.run_config_default_name)
                    RunConfigSelector(
                        configManager = runConfigManager,
                        onSelectConfig = { id ->
                            onRunConfigManagerChange(runConfigManager.selectConfig(id))
                        },
                        onAddConfig = {
                            onEditConfig(RunConfiguration(name = defaultRunConfigName))
                            onShowRunConfigDialog()
                        },
                        onEditConfig = {
                            onEditConfig(runConfigManager.selectedConfig)
                            onShowRunConfigDialog()
                        },
                        onDuplicateConfig = { id ->
                            onRunConfigManagerChange(runConfigManager.duplicateConfig(id))
                        },
                        onDeleteConfig = { id ->
                            onRunConfigManagerChange(runConfigManager.removeConfig(id))
                        },
                        onBuild = callbacks.onBuild,
                        onRun = callbacks.onCompile,
                        onRebuildAndRun = callbacks.onRebuildAndRun,
                        onRunInTerminal = callbacks.onCompileInTerminal,
                        onDebug = callbacks.onDebug,
                        isBuildEnabled = !isCompiling,
                        isRunEnabled = !isCompiling,
                        isDebugEnabled = !isCompiling,
                        buildIconRes = Drawables.ic_build,
                        debugIconRes = Drawables.ic_debug,
                        runTint = Color(0xFF4CAF50),
                        disabledTint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                        configSegmentMaxWidth = if (useCompactTitleLayout) 72.dp else 110.dp,
                        showBuildButton = true
                    )
                }
            }
        },
        navigationIcon = {
            IconButton(onClick = callbacks.onOpenDrawer) {
                Icon(Icons.Default.Menu, stringResource(Strings.content_desc_open_file_tree))
            }
        },
        actions = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(end = 0.dp)
            ) {
                if (!isDebugActive) {
                    SaveActionButton(
                        isDirty = isDirty,
                        onSave = callbacks.onSave
                    )
                    quickCommands.forEach { command ->
                        QuickCommandButton(command)
                    }
                }
                CommandPaletteButton()
            }
        },
        scrollBehavior = scrollBehavior
    )
}

@Composable
private fun SaveActionButton(
    isDirty: Boolean,
    onSave: () -> Unit
) {
    IconButton(onClick = onSave, enabled = isDirty) {
        Icon(
            painter = rememberTinaPainter(Drawables.ic_save),
            contentDescription = stringResource(Strings.content_desc_save),
            tint = if (isDirty) {
                MaterialTheme.colorScheme.onSurface
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f)
            },
            modifier = Modifier.size(20.dp)
        )
    }
}

private fun MainActivityCommand.iconVector(): ImageVector = when (id) {
    HostCommands.PROJECT_BUILD,
    "project.rebuildRun",
    "project.packageApk",
    "project.cmake.reconfigure",
    "project.cmake.cleanReconfigure",
    "project.cmake.clearBuildDir",
    "project.cmake.openArtifacts" -> Icons.Default.Build

    HostCommands.PROJECT_RUN,
    "project.runTerminal",
    "project.debug" -> Icons.Default.PlayArrow

    "view.globalSearch" -> Icons.Default.Search
    HostCommands.VIEW_TOGGLE_TERMINAL -> Icons.Default.Terminal
    HostCommands.VIEW_SETTINGS -> Icons.Default.Settings
    HostCommands.VIEW_TOGGLE_FILE_TREE -> Icons.Default.Folder
    HostCommands.PROJECT_CLOSE -> Icons.AutoMirrored.Filled.ExitToApp
    HostCommands.EDITOR_FORMAT,
    HostCommands.EDITOR_GOTO_LINE,
    HostCommands.EDITOR_CODE_ACTIONS,
    HostCommands.EDITOR_RENAME_SYMBOL -> Icons.Default.Code

    else -> when (category) {
        MainActivityCommandCategory.BUILD -> Icons.Default.Build
        MainActivityCommandCategory.CODE -> Icons.Default.Code
        MainActivityCommandCategory.FILE -> Icons.Default.Folder
        MainActivityCommandCategory.TERMINAL -> Icons.Default.Terminal
        MainActivityCommandCategory.VIEW -> Icons.Default.Search
        MainActivityCommandCategory.PLUGIN -> Icons.Default.Code
    }
}

@Composable
private fun MainActivityCommand.titleText(): String = when (val commandTitle = title) {
    is MainActivityCommandText.Literal -> commandTitle.value
    is MainActivityCommandText.Resource -> stringResource(commandTitle.resId)
}
