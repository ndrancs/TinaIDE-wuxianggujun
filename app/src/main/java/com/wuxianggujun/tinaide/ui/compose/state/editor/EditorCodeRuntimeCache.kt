package com.wuxianggujun.tinaide.ui.compose.state.editor

import android.content.Context
import com.wuxianggujun.tinaide.core.editorview.EditorConfig
import com.wuxianggujun.tinaide.core.editorview.EditorState
import com.wuxianggujun.tinaide.core.textengine.RopeTextBuffer
import com.wuxianggujun.tinaide.core.treesitter.TreeSitterFoldingProvider
import com.wuxianggujun.tinaide.core.treesitter.TreeSitterHighlighter
import com.wuxianggujun.tinaide.ui.compose.components.editor.EditorTabState
import timber.log.Timber

internal class EditorCodeRuntimeCache(
    private val context: Context,
    private val cacheLimit: Int,
    private val projectRootPathProvider: () -> String?,
    private val activeTabIdProvider: () -> String?,
    private val isSplitEditorEnabledProvider: () -> Boolean,
    private val splitPaneState: EditorSplitPaneState,
    private val attachedCodeEditorTabIdsProvider: () -> Set<String>,
    private val openTabsProvider: () -> List<EditorTabState>,
) {
    private val runtimesByTabId =
        java.util.LinkedHashMap<String, EditorContainerState.CodeEditorRuntime>(16, 0.75f, true)

    fun getOrCreate(tab: EditorTabState): EditorContainerState.CodeEditorRuntime {
        val runtime = runtimesByTabId.getOrPut(tab.id) {
            val buffer = RopeTextBuffer()
            EditorContainerState.CodeEditorRuntime(
                buffer = buffer,
                editorState = EditorState(
                    textBuffer = buffer,
                    file = tab.file,
                    projectRootPath = projectRootPathProvider(),
                    config = EditorConfig.fromPrefs()
                )
            )
        }
        trim(protectedTabIds = setOf(tab.id))
        return runtime
    }

    fun getOrCreateSyntaxHighlighter(tab: EditorTabState): TreeSitterHighlighter? {
        val runtime = getOrCreate(tab)
        if (!runtime.syntaxHighlighterCreationAttempted) {
            runtime.syntaxHighlighterCreationAttempted = true
            runtime.syntaxHighlighter = TreeSitterHighlighter.create(context.applicationContext, tab.file)
        }
        return runtime.syntaxHighlighter
    }

    fun getOrCreateFoldingProvider(tab: EditorTabState): TreeSitterFoldingProvider? {
        val runtime = getOrCreate(tab)
        if (!runtime.foldingProviderCreationAttempted) {
            runtime.foldingProviderCreationAttempted = true
            runtime.foldingProvider = TreeSitterFoldingProvider.create(context.applicationContext, tab.file)
        }
        return runtime.foldingProvider
    }

    fun isLoaded(tabId: String): Boolean = runtimesByTabId[tabId]?.isContentLoaded == true

    fun markLoaded(tabId: String) {
        runtimesByTabId[tabId]?.isContentLoaded = true
        trim(protectedTabIds = setOf(tabId))
    }

    fun remove(tabId: String) {
        runtimesByTabId.remove(tabId)?.disposeCodeEditorRuntime()
    }

    fun trim(protectedTabIds: Set<String> = emptySet()) {
        if (runtimesByTabId.size <= cacheLimit) return

        val effectiveProtectedTabIds = buildSet {
            addAll(protectedTabIds)
            activeTabIdProvider()?.let(::add)
            if (isSplitEditorEnabledProvider()) {
                splitPaneState.activeTabIds.forEach(::add)
            }
            attachedCodeEditorTabIdsProvider().forEach(::add)
        }

        val tabs = openTabsProvider()
        while (runtimesByTabId.size > cacheLimit) {
            val evictableTabId = runtimesByTabId.keys.firstOrNull { tabId ->
                tabId !in effectiveProtectedTabIds && tabs.firstOrNull { it.id == tabId }?.isDirty != true
            } ?: break

            runtimesByTabId.remove(evictableTabId)?.disposeCodeEditorRuntime()
            Timber.tag("EditorContainerState").d(
                "trimCodeEditorRuntimeCache: evicted tab=%s, remaining=%d",
                evictableTabId,
                runtimesByTabId.size
            )
        }
    }

    fun remapTabIds(idMap: Map<String, String>) {
        idMap.forEach { (oldId, newId) ->
            runtimesByTabId.remove(oldId)?.let { runtime -> runtimesByTabId[newId] = runtime }
        }
    }

    fun release() {
        runtimesByTabId.values.forEach { it.disposeCodeEditorRuntime() }
        runtimesByTabId.clear()
    }

    private fun EditorContainerState.CodeEditorRuntime.disposeCodeEditorRuntime() {
        syntaxHighlighter?.setOnStateUpdated(null)
        if (syntaxHighlighter != null && editorState.highlighter === syntaxHighlighter) {
            editorState.highlighter = null
        }
        syntaxHighlighter?.dispose()
        foldingProvider?.dispose()
        syntaxHighlighter = null
        foldingProvider = null
        isTreeSitterSnapshotReady = false
    }
}
