package com.wuxianggujun.tinaide.ui.compose.state.editor

import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.snapshotFlow
import com.wuxianggujun.tinaide.core.lsp.Diagnostic
import com.wuxianggujun.tinaide.plugin.script.api.PluginHostEventDispatcher
import java.io.File
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged

internal class EditorDiagnosticsState(
    private val filePathNormalizer: (File) -> String,
    private val fileUriNormalizer: (String) -> String?,
) {
    private val diagnosticsByFilePath = mutableStateMapOf<String, List<Diagnostic>>()

    var onDiagnosticsChanged: ((fileUri: String, diagnostics: List<Diagnostic>) -> Unit)? = null

    fun handleDiagnosticsChanged(fileUri: String, diagnostics: List<Diagnostic>) {
        fileUriNormalizer(fileUri)?.let { normalizedPath ->
            diagnosticsByFilePath[normalizedPath] = diagnostics
        }
        PluginHostEventDispatcher.emitDiagnosticsChanged(fileUri, diagnostics)
        onDiagnosticsChanged?.invoke(fileUri, diagnostics)
    }

    fun getDiagnosticsFlow(file: File): Flow<List<Diagnostic>> =
        snapshotFlow { readDiagnosticsForFile(file) }
            .distinctUntilChanged()

    fun removeForFile(file: File) {
        diagnosticsByFilePath.remove(filePathNormalizer(file))
    }

    fun clear() {
        diagnosticsByFilePath.clear()
        onDiagnosticsChanged = null
    }

    private fun readDiagnosticsForFile(file: File): List<Diagnostic> {
        val normalizedPath = filePathNormalizer(file)
        return diagnosticsByFilePath[normalizedPath].orEmpty()
    }
}
