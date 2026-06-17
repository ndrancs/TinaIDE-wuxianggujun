package com.wuxianggujun.tinaide.ui.compose.state.editor

import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.snapshots.SnapshotStateList
import java.io.File

internal class EditorNavigationHistoryManager(
    private val currentLocationProvider: () -> EditorContainerState.NavigationHistoryEntry?,
    private val openLocation: (EditorContainerState.NavigationHistoryEntry) -> Boolean,
    private val maxHistorySize: Int = DEFAULT_MAX_NAVIGATION_HISTORY_SIZE,
) {
    val backStack: SnapshotStateList<EditorContainerState.NavigationHistoryEntry> = mutableStateListOf()
    val forwardStack: SnapshotStateList<EditorContainerState.NavigationHistoryEntry> = mutableStateListOf()

    fun canNavigateBack(): Boolean = backStack.isNotEmpty()

    fun canNavigateForward(): Boolean = forwardStack.isNotEmpty()

    fun navigateBack(): Boolean = navigateHistory(
        sourceStack = backStack,
        destinationStack = forwardStack
    )

    fun navigateForward(): Boolean = navigateHistory(
        sourceStack = forwardStack,
        destinationStack = backStack
    )

    fun entryOf(file: File, line: Int, column: Int): EditorContainerState.NavigationHistoryEntry =
        EditorContainerState.NavigationHistoryEntry(
            filePath = file.absolutePath,
            line = line.coerceAtLeast(0),
            column = column.coerceAtLeast(0)
        )

    fun recordTransition(
        source: EditorContainerState.NavigationHistoryEntry?,
        target: EditorContainerState.NavigationHistoryEntry
    ) {
        if (source == null || source.isSameNavigationLocation(target)) return
        pushNavigationEntry(backStack, source)
        forwardStack.clear()
    }

    fun clear() {
        backStack.clear()
        forwardStack.clear()
    }

    private fun navigateHistory(
        sourceStack: SnapshotStateList<EditorContainerState.NavigationHistoryEntry>,
        destinationStack: SnapshotStateList<EditorContainerState.NavigationHistoryEntry>
    ): Boolean {
        val current = currentLocationProvider() ?: return false
        while (sourceStack.isNotEmpty()) {
            val target = sourceStack.removeAt(sourceStack.lastIndex)
            if (target.isSameNavigationLocation(current)) continue

            if (openLocation(target)) {
                pushNavigationEntry(destinationStack, current)
                return true
            }
        }
        return false
    }

    private fun pushNavigationEntry(
        stack: SnapshotStateList<EditorContainerState.NavigationHistoryEntry>,
        entry: EditorContainerState.NavigationHistoryEntry
    ) {
        if (stack.lastOrNull()?.isSameNavigationLocation(entry) == true) return
        stack.add(entry)
        while (stack.size > maxHistorySize) {
            stack.removeAt(0)
        }
    }

    private fun EditorContainerState.NavigationHistoryEntry.isSameNavigationLocation(
        other: EditorContainerState.NavigationHistoryEntry
    ): Boolean = normalizeOpenTabLookupPath(filePath) == normalizeOpenTabLookupPath(other.filePath) &&
        line == other.line &&
        column == other.column

    private companion object {
        const val DEFAULT_MAX_NAVIGATION_HISTORY_SIZE = 100
    }
}
