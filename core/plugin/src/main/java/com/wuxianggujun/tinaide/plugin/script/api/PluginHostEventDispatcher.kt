package com.wuxianggujun.tinaide.plugin.script.api

import com.wuxianggujun.tinaide.core.lsp.Diagnostic
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * 宿主到插件事件总线的轻量分发器。
 *
 * app 层在合适的生命周期节点调用这里，避免到处直接依赖
 * `PluginEventBus.emit()` 的挂起接口。
 */
object PluginHostEventDispatcher {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    fun emit(eventId: String, data: Map<String, Any?> = emptyMap()) {
        scope.launch {
            PluginEventBus.emit(eventId, data)
        }
    }

    fun emitToPlugin(
        pluginId: String,
        eventId: String,
        data: Map<String, Any?> = emptyMap(),
    ) {
        scope.launch {
            PluginEventBus.emit(
                eventId = eventId,
                data = data,
                targetPluginId = pluginId,
            )
        }
    }

    fun emitProjectOpened(rootPath: String) {
        emit(
            eventId = PluginEvent.PROJECT_OPENED.id,
            data = mapOf(
                "rootPath" to rootPath,
                "projectName" to File(rootPath).name
            )
        )
    }

    fun emitProjectClosed(rootPath: String) {
        emit(
            eventId = PluginEvent.PROJECT_CLOSED.id,
            data = mapOf(
                "rootPath" to rootPath,
                "projectName" to File(rootPath).name
            )
        )
    }

    fun emitEditorSaved(tabId: String, file: File) {
        emit(
            eventId = PluginEvent.EDITOR_SAVED.id,
            data = mapOf(
                "tabId" to tabId,
                "filePath" to file.absolutePath,
                "fileName" to file.name
            )
        )
    }

    fun emitEditorOpened(tabId: String, file: File, contentType: String) {
        emit(
            eventId = PluginEvent.EDITOR_OPENED.id,
            data = mapOf(
                "tabId" to tabId,
                "filePath" to file.absolutePath,
                "fileName" to file.name,
                "contentType" to contentType
            )
        )
    }

    fun emitEditorClosed(tabId: String, file: File, contentType: String) {
        emit(
            eventId = PluginEvent.EDITOR_CLOSED.id,
            data = mapOf(
                "tabId" to tabId,
                "filePath" to file.absolutePath,
                "fileName" to file.name,
                "contentType" to contentType
            )
        )
    }

    fun emitEditorActiveChanged(tabId: String, file: File, contentType: String, isDirty: Boolean) {
        emit(
            eventId = PluginEvent.EDITOR_ACTIVE_CHANGED.id,
            data = mapOf(
                "tabId" to tabId,
                "filePath" to file.absolutePath,
                "fileName" to file.name,
                "contentType" to contentType,
                "isDirty" to isDirty
            )
        )
    }

    fun emitEditorSelectionChanged(
        tabId: String,
        file: File,
        selection: EditorSelectionPayload?
    ) {
        emit(
            eventId = PluginEvent.EDITOR_SELECTION_CHANGED.id,
            data = mapOf(
                "tabId" to tabId,
                "filePath" to file.absolutePath,
                "fileName" to file.name,
                "hasSelection" to (selection != null),
                "selection" to selection?.toMap()
            )
        )
    }

    fun emitEditorDirtyChanged(tabId: String, file: File, isDirty: Boolean) {
        emit(
            eventId = PluginEvent.EDITOR_DIRTY_CHANGED.id,
            data = mapOf(
                "tabId" to tabId,
                "filePath" to file.absolutePath,
                "fileName" to file.name,
                "isDirty" to isDirty
            )
        )
    }

    fun emitDiagnosticsChanged(fileUri: String, diagnostics: List<Diagnostic>) {
        val errors = diagnostics.count { it.severity == Diagnostic.Severity.ERROR }
        val warnings = diagnostics.count { it.severity == Diagnostic.Severity.WARNING }
        val infos = diagnostics.count { it.severity == Diagnostic.Severity.INFO }
        val hints = diagnostics.count { it.severity == Diagnostic.Severity.HINT }
        emit(
            eventId = PluginEvent.DIAGNOSTICS_CHANGED.id,
            data = mapOf(
                "fileUri" to fileUri,
                "fileName" to diagnostics.firstOrNull()?.fileName,
                "totalCount" to diagnostics.size,
                "errorCount" to errors,
                "warningCount" to warnings,
                "infoCount" to infos,
                "hintCount" to hints,
                "diagnostics" to diagnostics.map { it.toEventMap() }
            )
        )
    }

    fun emitFileCreated(file: File) {
        emit(
            eventId = PluginEvent.FILE_CREATED.id,
            data = mapOf(
                "filePath" to file.absolutePath,
                "fileName" to file.name,
                "isDirectory" to file.isDirectory
            )
        )
    }

    fun emitFileDeleted(file: File, wasDirectory: Boolean) {
        emit(
            eventId = PluginEvent.FILE_DELETED.id,
            data = mapOf(
                "filePath" to file.absolutePath,
                "fileName" to file.name,
                "isDirectory" to wasDirectory
            )
        )
    }

    fun emitFileRenamed(oldFile: File, newFile: File, isDirectory: Boolean) {
        emit(
            eventId = PluginEvent.FILE_RENAMED.id,
            data = mapOf(
                "oldPath" to oldFile.absolutePath,
                "oldName" to oldFile.name,
                "newPath" to newFile.absolutePath,
                "newName" to newFile.name,
                "isDirectory" to isDirectory
            )
        )
    }

    fun emitBuildStarted(rootPath: String?) {
        emit(
            eventId = PluginEvent.BUILD_STARTED.id,
            data = mapOf("rootPath" to rootPath)
        )
    }

    fun emitBuildFinished(rootPath: String?) {
        emit(
            eventId = PluginEvent.BUILD_FINISHED.id,
            data = mapOf("rootPath" to rootPath)
        )
    }

    fun emitConfigChanged(
        pluginId: String,
        key: String,
        value: Any?,
        previousValue: Any?,
    ) {
        emitToPlugin(
            pluginId = pluginId,
            eventId = PluginEvent.CONFIG_CHANGED.id,
            data = mapOf(
                "pluginId" to pluginId,
                "key" to key,
                "value" to value,
                "previousValue" to previousValue,
            )
        )
    }
}

data class EditorSelectionPayload(
    val text: String,
    val startLine: Int,
    val startColumn: Int,
    val endLine: Int,
    val endColumn: Int
) {
    fun toMap(): Map<String, Any?> = mapOf(
        "text" to text,
        "startLine" to startLine,
        "startColumn" to startColumn,
        "endLine" to endLine,
        "endColumn" to endColumn
    )
}

private fun Diagnostic.toEventMap(): Map<String, Any?> = mapOf(
    "fileUri" to fileUri,
    "fileName" to fileName,
    "line" to line,
    "column" to column,
    "endLine" to endLine,
    "endColumn" to endColumn,
    "message" to message,
    "severity" to severity.name.lowercase(),
    "source" to source,
    "code" to code
)
