package com.wuxianggujun.tinaide.plugin

enum class PluginDiagnosticSource {
    LOAD,
    HEALTH,
    RUNTIME,
}

data class PluginDiagnosticEntry(
    val source: PluginDiagnosticSource,
    val issue: PluginDiagnosticIssue,
)

data class PluginDiagnosticsReport(
    val pluginId: String?,
    val pluginName: String,
    val directoryName: String? = null,
    val isInstalled: Boolean,
    val entries: List<PluginDiagnosticEntry>,
) {
    val issues: List<PluginDiagnosticIssue>
        get() = entries.map(PluginDiagnosticEntry::issue)

    val totalCount: Int
        get() = entries.size

    val highestSeverity: PluginDiagnosticSeverity?
        get() = issues.maxByOrNull { it.severity.priority }?.severity

    val hasIssues: Boolean
        get() = entries.isNotEmpty()
}

data class PluginDiagnosticsSnapshot(
    val installedReports: Map<String, PluginDiagnosticsReport>,
    val loadReports: List<PluginDiagnosticsReport>,
) {
    fun getInstalledReport(pluginId: String): PluginDiagnosticsReport? = installedReports[pluginId]
}
