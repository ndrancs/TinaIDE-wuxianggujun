package com.wuxianggujun.tinaide.plugin.script.api

import java.util.concurrent.atomic.AtomicReference

data class PluginDiagnosticItem(
    val fileUri: String,
    val filePath: String,
    val fileName: String,
    val line: Int,
    val column: Int,
    val endLine: Int,
    val endColumn: Int,
    val message: String,
    val severity: String,
    val source: String? = null,
    val code: String? = null
)

data class PluginDiagnosticsSnapshot(
    val diagnostics: List<PluginDiagnosticItem>,
    val requestedFilePath: String? = null,
    val available: Boolean = true,
    val error: String? = null
) {
    val totalCount: Int get() = diagnostics.size
    val errorCount: Int get() = diagnostics.count { it.severity == SEVERITY_ERROR }
    val warningCount: Int get() = diagnostics.count { it.severity == SEVERITY_WARNING }
    val infoCount: Int get() = diagnostics.count { it.severity == SEVERITY_INFO }
    val hintCount: Int get() = diagnostics.count { it.severity == SEVERITY_HINT }

    companion object {
        const val SEVERITY_ERROR = "error"
        const val SEVERITY_WARNING = "warning"
        const val SEVERITY_INFO = "info"
        const val SEVERITY_HINT = "hint"

        fun unavailable(requestedFilePath: String? = null): PluginDiagnosticsSnapshot = PluginDiagnosticsSnapshot(
            diagnostics = emptyList(),
            requestedFilePath = requestedFilePath,
            available = false,
            error = "Diagnostics provider unavailable"
        )
    }
}

interface PluginDiagnosticsProvider {
    fun getDiagnostics(filePath: String? = null): PluginDiagnosticsSnapshot
}

object PluginDiagnosticsProviderHolder {
    private val providerRef = AtomicReference<PluginDiagnosticsProvider?>(null)

    fun set(provider: PluginDiagnosticsProvider) {
        providerRef.set(provider)
    }

    fun get(): PluginDiagnosticsProvider? = providerRef.get()

    fun clear() {
        providerRef.set(null)
    }
}
