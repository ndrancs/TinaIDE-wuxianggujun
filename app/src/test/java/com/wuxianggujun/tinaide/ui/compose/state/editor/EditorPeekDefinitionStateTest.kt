package com.wuxianggujun.tinaide.ui.compose.state.editor

import com.google.common.truth.Truth.assertThat
import com.wuxianggujun.tinaide.core.lsp.LocationItem
import org.junit.Test

class EditorPeekDefinitionStateTest {
    private val state = EditorPeekDefinitionState()

    @Test
    fun showLoading_shouldExposeLoadingPanel() {
        state.showLoading(
            ownerTabId = "tab-1",
            title = "Definition"
        )

        val panelState = state.panelState
        assertThat(panelState?.ownerTabId).isEqualTo("tab-1")
        assertThat(panelState?.title).isEqualTo("Definition")
        assertThat(panelState?.locations).isEmpty()
        assertThat(panelState?.isLoading).isTrue()
    }

    @Test
    fun showResults_shouldReplaceLoadingPanel() {
        val location = locationItem()

        state.showLoading(
            ownerTabId = "tab-1",
            title = "Definition"
        )
        state.showResults(
            ownerTabId = "tab-1",
            title = "Definition",
            locations = listOf(location)
        )

        val panelState = state.panelState
        assertThat(panelState?.ownerTabId).isEqualTo("tab-1")
        assertThat(panelState?.locations).containsExactly(location)
        assertThat(panelState?.isLoading).isFalse()
    }

    @Test
    fun dismiss_shouldOnlyDismissMatchingOwner() {
        state.showLoading(
            ownerTabId = "tab-1",
            title = "Definition"
        )

        state.dismiss(ownerTabId = "tab-2")
        assertThat(state.panelState).isNotNull()

        state.dismiss(ownerTabId = "tab-1")
        assertThat(state.panelState).isNull()
    }

    private fun locationItem(): LocationItem = LocationItem(
        uri = "file:///project/main.c",
        filePath = "/project/main.c",
        fileName = "main.c",
        line = 1,
        column = 2,
        endLine = 1,
        endColumn = 6,
        previewText = "int main()"
    )
}
