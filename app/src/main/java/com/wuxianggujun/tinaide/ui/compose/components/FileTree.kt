package com.wuxianggujun.tinaide.ui.compose.components

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.wuxianggujun.tinaide.core.commands.HostCommandExecutor
import com.wuxianggujun.tinaide.core.git.FileStatus
import com.wuxianggujun.tinaide.core.i18n.Strings
import com.wuxianggujun.tinaide.plugin.PluginManager
import java.io.File
import kotlin.math.roundToInt
import kotlinx.coroutines.launch

/**
 * 文件 Git 状态信息
 */
@Immutable
data class FileGitStatus(
    val status: FileStatus,
    val isStaged: Boolean
)

private data class FileTreeContextMenuState(
    val path: String,
    val isDirectory: Boolean,
    val anchor: IntOffset
)

/**
 * 文件树组件
 *
 * @param gitStatusMap 文件路径到 Git 状态的映射（相对路径 -> 状态）
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FileTree(
    state: FileTreeState,
    pluginManager: PluginManager,
    onFileClick: (File) -> Unit,
    modifier: Modifier = Modifier,
    hostCommandExecutor: HostCommandExecutor? = null,
    onFileLongClick: (File) -> Unit = {},
    onContextAction: (FileContextAction) -> Unit = {},
    gitStatusMap: Map<String, FileGitStatus> = emptyMap()
) {
    val scope = rememberCoroutineScope()
    val pullToRefreshState = rememberPullToRefreshState()
    val uiState by state.uiState.collectAsState()
    val enabledPlugins by pluginManager.enabledPluginsFlow.collectAsState()
    val artifactsLabel = stringResource(Strings.file_tree_build_artifacts)
    val iconResolver = remember(enabledPlugins) {
        FileTreeIconResolver(
            pluginManager.resolveFileTreeIcons(enabledPlugins)
        )
    }

    var rootOriginInRoot by remember { mutableStateOf(Offset.Zero) }
    var contextMenuState by remember { mutableStateOf<FileTreeContextMenuState?>(null) }
    val latestRootOriginInRoot = rememberUpdatedState(rootOriginInRoot)
    val latestOnFileClick = rememberUpdatedState(onFileClick)
    val latestOnFileLongClick = rememberUpdatedState(onFileLongClick)
    val contextMenuStateOrNull = remember(contextMenuState, uiState.visibleNodes) {
        val menuState = contextMenuState ?: return@remember null
        if (uiState.visibleNodes.any { it.absolutePath == menuState.path }) menuState else null
    }
    val contextMenuFile = remember(contextMenuStateOrNull?.path) {
        contextMenuStateOrNull?.path?.let(::File)
    }
    val handleRowClick = remember(state, scope) {
        { node: FileTreeNode ->
            state.selectPath(node.absolutePath, node.isDirectory)
            if (node.isDirectory) {
                scope.launch { state.toggleNode(node.absolutePath) }
            } else {
                val targetFile = File(node.absolutePath)
                latestOnFileClick.value(targetFile)
            }
            Unit
        }
    }
    val handleRowLongClick = remember(state) {
        { node: FileTreeNode, anchorInRoot: Offset ->
            state.selectPath(node.absolutePath, node.isDirectory)
            contextMenuState = FileTreeContextMenuState(
                path = node.absolutePath,
                isDirectory = node.isDirectory,
                anchor = run {
                    val rootOrigin = latestRootOriginInRoot.value
                    IntOffset(
                        x = (anchorInRoot.x - rootOrigin.x).roundToInt(),
                        y = (anchorInRoot.y - rootOrigin.y).roundToInt()
                    )
                }
            )
            latestOnFileLongClick.value(File(node.absolutePath))
        }
    }
    val contextMenuPluginItems = remember(enabledPlugins, contextMenuFile, contextMenuStateOrNull?.isDirectory) {
        if (contextMenuFile != null && contextMenuStateOrNull != null) {
            pluginManager.resolveFileTreeContextMenuItems(
                installedPlugins = enabledPlugins,
                file = contextMenuFile,
                isDirectory = contextMenuStateOrNull.isDirectory
            )
        } else {
            emptyList()
        }
    }
    LaunchedEffect(uiState.visibleNodes, contextMenuState?.path) {
        if (contextMenuState?.path != null && contextMenuStateOrNull == null) {
            contextMenuState = null
        }
    }

    PullToRefreshBox(
        isRefreshing = uiState.isRefreshing,
        onRefresh = { scope.launch { state.refresh() } },
        state = pullToRefreshState,
        modifier = modifier.fillMaxSize()
    ) {
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .onGloballyPositioned { coordinates ->
                    rootOriginInRoot = coordinates.boundsInRoot().topLeft
                }
        ) {
            val containerWidth = maxWidth
            val horizontalScrollState = rememberScrollState()

            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .horizontalScroll(horizontalScrollState)
            ) {
                items(
                    items = uiState.visibleNodes,
                    key = { it.absolutePath },
                    contentType = { if (it.isDirectory) 0 else 1 }
                ) { node ->
                    val gitStatus = node.relativePath?.let(gitStatusMap::get)
                    val displayName = if (node.isArtifactsDirectory) artifactsLabel else node.name
                    val iconSource = remember(node, iconResolver) { iconResolver.resolve(node) }
                    FileTreeItem(
                        node = node,
                        displayName = displayName,
                        iconSource = iconSource,
                        isSelected = uiState.selectedPath == node.absolutePath,
                        gitStatus = gitStatus,
                        containerWidth = containerWidth,
                        onClick = handleRowClick,
                        onLongClick = handleRowLongClick,
                    )
                }
            }

            if (contextMenuStateOrNull != null && contextMenuFile != null) {
                Box(
                    modifier = Modifier
                        .offset { contextMenuState?.anchor ?: IntOffset.Zero }
                        .size(1.dp)
                ) {
                    FileContextMenu(
                        file = contextMenuFile,
                        isDirectory = contextMenuStateOrNull.isDirectory,
                        expanded = true,
                        pluginMenuItems = contextMenuPluginItems,
                        hostCommandExecutor = hostCommandExecutor,
                        onDismiss = { contextMenuState = null },
                        onAction = { action ->
                            contextMenuState = null
                            onContextAction(action)
                        }
                    )
                }
            }
        }
    }
}
