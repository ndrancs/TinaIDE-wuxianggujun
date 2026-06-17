package com.wuxianggujun.tinaide.ui.compose.state.editor

import com.wuxianggujun.tinaide.editor.session.SaveResult
import com.wuxianggujun.tinaide.ui.compose.components.editor.EditorTabState

internal class EditorSaveAllNotificationTracker {
    private var pendingTargets: List<EditorContainerState.ActiveSaveTarget> = emptyList()

    fun rememberDirtyTabs(tabs: List<EditorTabState>) {
        pendingTargets = tabs
            .asSequence()
            .filter { it.isDirty }
            .map { tab ->
                EditorContainerState.ActiveSaveTarget(
                    tabId = tab.id,
                    file = tab.file
                )
            }
            .toList()
    }

    fun resolveSuccessfulTargets(
        results: List<SaveResult>
    ): List<EditorContainerState.ActiveSaveTarget> {
        val targets = pendingTargets
        pendingTargets = emptyList()
        if (targets.isEmpty() || results.isEmpty()) return emptyList()
        return targets.zip(results).mapNotNull { (target, result) ->
            target.takeIf { result is SaveResult.Success }
        }
    }
}
