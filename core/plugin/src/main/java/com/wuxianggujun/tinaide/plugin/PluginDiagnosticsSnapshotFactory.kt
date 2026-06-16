package com.wuxianggujun.tinaide.plugin

import com.wuxianggujun.tinaide.plugin.script.ScriptPluginInfo
import com.wuxianggujun.tinaide.plugin.script.ScriptPluginState

object PluginDiagnosticsSnapshotFactory {
    private const val MAX_PERMISSION_RUNTIME_ENTRIES_PER_PLUGIN = 3

    fun create(
        installedPlugins: List<InstalledPlugin>,
        loadReports: List<PluginDiagnosticsReport>,
        healthReports: Map<String, PluginHealthReport>,
        scriptPluginStates: Map<String, ScriptPluginInfo>,
        runtimeFixHint: String,
        pluginLogs: List<PluginLogEntry>,
        permissionRuntimeFixHint: String,
        lspRuntimeEntriesByPluginId: Map<String, List<PluginDiagnosticEntry>> = emptyMap(),
        commandRuntimeEntriesByPluginId: Map<String, List<PluginDiagnosticEntry>> = emptyMap(),
    ): PluginDiagnosticsSnapshot {
        val permissionRuntimeEntriesByPluginId = buildPermissionRuntimeEntriesByPluginId(
            pluginLogs = pluginLogs,
            permissionRuntimeFixHint = permissionRuntimeFixHint,
        )
        val installedReports = installedPlugins.associate { plugin ->
            plugin.manifest.id to buildInstalledReport(
                plugin = plugin,
                healthReport = healthReports[plugin.manifest.id],
                scriptPluginInfo = scriptPluginStates[plugin.manifest.id],
                runtimeFixHint = runtimeFixHint,
                permissionRuntimeEntries = permissionRuntimeEntriesByPluginId[plugin.manifest.id].orEmpty(),
                lspRuntimeEntries = lspRuntimeEntriesByPluginId[plugin.manifest.id].orEmpty(),
                commandRuntimeEntries = commandRuntimeEntriesByPluginId[plugin.manifest.id].orEmpty(),
            )
        }

        return PluginDiagnosticsSnapshot(
            installedReports = installedReports,
            loadReports = loadReports.sortedWith(
                compareByDescending<PluginDiagnosticsReport> { it.highestSeverity?.priority ?: -1 }
                    .thenBy { it.pluginName.lowercase() }
                    .thenBy { it.directoryName.orEmpty().lowercase() }
            ),
        )
    }

    private fun buildInstalledReport(
        plugin: InstalledPlugin,
        healthReport: PluginHealthReport?,
        scriptPluginInfo: ScriptPluginInfo?,
        runtimeFixHint: String,
        permissionRuntimeEntries: List<PluginDiagnosticEntry>,
        lspRuntimeEntries: List<PluginDiagnosticEntry>,
        commandRuntimeEntries: List<PluginDiagnosticEntry>,
    ): PluginDiagnosticsReport {
        val entries = buildList {
            addAll(
                healthReport?.issues.orEmpty().map { issue ->
                    PluginDiagnosticEntry(
                        source = PluginDiagnosticSource.HEALTH,
                        issue = issue,
                    )
                }
            )
            addAll(permissionRuntimeEntries)
            addAll(commandRuntimeEntries)
            addAll(lspRuntimeEntries)
            buildRuntimeEntry(
                scriptPluginInfo = scriptPluginInfo,
                runtimeFixHint = runtimeFixHint,
            )?.let(::add)
        }.sortedWith(
            compareByDescending<PluginDiagnosticEntry> { it.issue.severity.priority }
                .thenBy { it.issue.category.ordinal }
                .thenBy { it.issue.message }
                .thenBy { it.source.ordinal }
        )

        return PluginDiagnosticsReport(
            pluginId = plugin.manifest.id,
            pluginName = plugin.manifest.name,
            directoryName = plugin.directory.name,
            isInstalled = true,
            entries = entries,
        )
    }

    private fun buildRuntimeEntry(
        scriptPluginInfo: ScriptPluginInfo?,
        runtimeFixHint: String,
    ): PluginDiagnosticEntry? {
        val error = scriptPluginInfo?.error?.takeIf { it.isNotBlank() } ?: return null
        if (scriptPluginInfo.state != ScriptPluginState.ERROR) return null
        return PluginDiagnosticEntry(
            source = PluginDiagnosticSource.RUNTIME,
            issue = PluginDiagnosticIssue(
                severity = PluginDiagnosticSeverity.ERROR,
                category = PluginDiagnosticCategory.RUNTIME,
                message = error,
                fixHint = runtimeFixHint,
            ),
        )
    }

    private fun buildPermissionRuntimeEntriesByPluginId(
        pluginLogs: List<PluginLogEntry>,
        permissionRuntimeFixHint: String,
    ): Map<String, List<PluginDiagnosticEntry>> = pluginLogs
        .asSequence()
        .filter { log -> log.eventCode == PluginLogEventCodes.PERMISSION_DENIED }
        .groupBy { log -> log.pluginId }
        .mapValues { (_, logs) ->
            logs.asSequence()
                .sortedWith(compareByDescending<PluginLogEntry> { it.timestamp }.thenByDescending { it.id })
                .distinctBy { log -> resolvePermissionRuntimeDedupKey(log) }
                .take(MAX_PERMISSION_RUNTIME_ENTRIES_PER_PLUGIN)
                .map { log ->
                    PluginDiagnosticEntry(
                        source = PluginDiagnosticSource.RUNTIME,
                        issue = PluginDiagnosticIssue(
                            severity = PluginDiagnosticSeverity.WARNING,
                            category = PluginDiagnosticCategory.PERMISSIONS,
                            message = log.message,
                            fixHint = permissionRuntimeFixHint,
                        ),
                    )
                }
                .toList()
        }
        .filterValues { entries -> entries.isNotEmpty() }

    private fun resolvePermissionRuntimeDedupKey(log: PluginLogEntry): String {
        val attributes = log.attributes
        val apiNamespace = attributes[PluginLogEventKeys.API_NAMESPACE].orEmpty()
        val apiMethod = attributes[PluginLogEventKeys.API_METHOD].orEmpty()
        val permissionId = attributes[PluginLogEventKeys.PERMISSION_ID].orEmpty()
        val denialReason = attributes[PluginLogEventKeys.DENIAL_REASON].orEmpty()
        return if (
            apiNamespace.isNotBlank() ||
            apiMethod.isNotBlank() ||
            permissionId.isNotBlank() ||
            denialReason.isNotBlank()
        ) {
            listOf(apiNamespace, apiMethod, permissionId, denialReason).joinToString("|")
        } else {
            log.message
        }
    }
}
