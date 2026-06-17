package com.wuxianggujun.tinaide.ui.compose.state.editor

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.wuxianggujun.tinaide.core.lsp.LocationItem

internal class EditorPeekDefinitionState {
    var panelState by mutableStateOf<PeekDefinitionPanelState?>(null)
        private set

    fun showLoading(ownerTabId: String, title: String) {
        panelState = PeekDefinitionPanelState(
            ownerTabId = ownerTabId,
            title = title,
            locations = emptyList(),
            isLoading = true
        )
    }

    fun showResults(
        ownerTabId: String,
        title: String,
        locations: List<LocationItem>
    ) {
        panelState = PeekDefinitionPanelState(
            ownerTabId = ownerTabId,
            title = title,
            locations = locations,
            isLoading = false
        )
    }

    fun dismiss(ownerTabId: String? = null) {
        val current = panelState ?: return
        if (ownerTabId == null || current.ownerTabId == ownerTabId) {
            panelState = null
        }
    }
}
