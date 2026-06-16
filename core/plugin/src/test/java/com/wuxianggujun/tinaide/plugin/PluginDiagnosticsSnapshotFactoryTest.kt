package com.wuxianggujun.tinaide.plugin

import com.google.common.truth.Truth.assertThat
import com.wuxianggujun.tinaide.plugin.script.ScriptPluginInfo
import com.wuxianggujun.tinaide.plugin.script.ScriptPluginState
import java.io.File
import org.junit.Test

class PluginDiagnosticsSnapshotFactoryTest {

    @Test
    fun `create should merge health and runtime diagnostics for installed plugin`() {
        val plugin = installedPlugin(id = "demo.plugin", name = "Demo Plugin")

        val snapshot = PluginDiagnosticsSnapshotFactory.create(
            installedPlugins = listOf(plugin),
            loadReports = emptyList(),
            healthReports = mapOf(
                plugin.manifest.id to PluginHealthReport(
                    pluginId = plugin.manifest.id,
                    pluginName = plugin.manifest.name,
                    issues = listOf(
                        PluginDiagnosticIssue(
                            severity = PluginDiagnosticSeverity.WARNING,
                            category = PluginDiagnosticCategory.COMPATIBILITY,
                            message = "Compatibility warning"
                        )
                    )
                )
            ),
            scriptPluginStates = mapOf(
                plugin.manifest.id to ScriptPluginInfo(
                    pluginId = plugin.manifest.id,
                    state = ScriptPluginState.ERROR,
                    error = "Runtime crash"
                )
            ),
            runtimeFixHint = "Reload after fix",
            pluginLogs = emptyList(),
            permissionRuntimeFixHint = "Grant permission and retry",
        )

        val report = snapshot.getInstalledReport(plugin.manifest.id)

        assertThat(report).isNotNull()
        assertThat(report?.isInstalled).isTrue()
        assertThat(report?.entries).hasSize(2)
        assertThat(report?.entries?.first()?.source).isEqualTo(PluginDiagnosticSource.RUNTIME)
        assertThat(report?.issues?.first()?.message).isEqualTo("Runtime crash")
        assertThat(report?.highestSeverity).isEqualTo(PluginDiagnosticSeverity.ERROR)
    }

    @Test
    fun `create should preserve load reports separately`() {
        val loadReport = PluginDiagnosticsReport(
            pluginId = null,
            pluginName = "Broken Plugin",
            directoryName = "broken-plugin",
            isInstalled = false,
            entries = listOf(
                PluginDiagnosticEntry(
                    source = PluginDiagnosticSource.LOAD,
                    issue = PluginDiagnosticIssue(
                        severity = PluginDiagnosticSeverity.ERROR,
                        category = PluginDiagnosticCategory.MANIFEST,
                        message = "manifest missing"
                    )
                )
            )
        )

        val snapshot = PluginDiagnosticsSnapshotFactory.create(
            installedPlugins = emptyList(),
            loadReports = listOf(loadReport),
            healthReports = emptyMap(),
            scriptPluginStates = emptyMap(),
            runtimeFixHint = "Reload after fix",
            pluginLogs = emptyList(),
            permissionRuntimeFixHint = "Grant permission and retry",
        )

        assertThat(snapshot.loadReports).containsExactly(loadReport)
        assertThat(snapshot.installedReports).isEmpty()
    }

    @Test
    fun `create should create empty installed report when plugin has no issues`() {
        val plugin = installedPlugin(id = "clean.plugin", name = "Clean Plugin")

        val snapshot = PluginDiagnosticsSnapshotFactory.create(
            installedPlugins = listOf(plugin),
            loadReports = emptyList(),
            healthReports = emptyMap(),
            scriptPluginStates = mapOf(
                plugin.manifest.id to ScriptPluginInfo(
                    pluginId = plugin.manifest.id,
                    state = ScriptPluginState.ACTIVE,
                    error = null
                )
            ),
            runtimeFixHint = "Reload after fix",
            pluginLogs = emptyList(),
            permissionRuntimeFixHint = "Grant permission and retry",
        )

        val report = snapshot.getInstalledReport(plugin.manifest.id)

        assertThat(report).isNotNull()
        assertThat(report?.issues).isEmpty()
        assertThat(report?.hasIssues).isFalse()
    }

    @Test
    fun `create should merge recent runtime permission diagnostics from logs`() {
        val plugin = installedPlugin(id = "demo.plugin", name = "Demo Plugin")

        val snapshot = PluginDiagnosticsSnapshotFactory.create(
            installedPlugins = listOf(plugin),
            loadReports = emptyList(),
            healthReports = emptyMap(),
            scriptPluginStates = mapOf(
                plugin.manifest.id to ScriptPluginInfo(
                    pluginId = plugin.manifest.id,
                    state = ScriptPluginState.ACTIVE,
                    error = null
                )
            ),
            runtimeFixHint = "Reload after fix",
            pluginLogs = listOf(
                PluginLogEntry(
                    id = 1L,
                    timestamp = 1L,
                    pluginId = plugin.manifest.id,
                    pluginName = plugin.manifest.name,
                    level = PluginLogLevel.WARN,
                    message = "Permission denied for tina.fs.readFile: file.read is not granted",
                    eventCode = PluginLogEventCodes.PERMISSION_DENIED,
                    attributes = mapOf(
                        PluginLogEventKeys.API_NAMESPACE to "fs",
                        PluginLogEventKeys.API_METHOD to "readFile",
                        PluginLogEventKeys.PERMISSION_ID to "file.read",
                        PluginLogEventKeys.DENIAL_REASON to "NOT_GRANTED",
                    ),
                ),
                PluginLogEntry(
                    id = 2L,
                    timestamp = 2L,
                    pluginId = plugin.manifest.id,
                    pluginName = plugin.manifest.name,
                    level = PluginLogLevel.WARN,
                    message = "Permission denied for tina.fs.readFile: file.read is not granted",
                    eventCode = PluginLogEventCodes.PERMISSION_DENIED,
                    attributes = mapOf(
                        PluginLogEventKeys.API_NAMESPACE to "fs",
                        PluginLogEventKeys.API_METHOD to "readFile",
                        PluginLogEventKeys.PERMISSION_ID to "file.read",
                        PluginLogEventKeys.DENIAL_REASON to "NOT_GRANTED",
                    ),
                ),
                PluginLogEntry(
                    id = 3L,
                    timestamp = 3L,
                    pluginId = plugin.manifest.id,
                    pluginName = plugin.manifest.name,
                    level = PluginLogLevel.WARN,
                    message = "Permission denied for tina.commands.execute: command.execute is not declared in manifest",
                    eventCode = PluginLogEventCodes.PERMISSION_DENIED,
                    attributes = mapOf(
                        PluginLogEventKeys.API_NAMESPACE to "commands",
                        PluginLogEventKeys.API_METHOD to "execute",
                        PluginLogEventKeys.PERMISSION_ID to "command.execute",
                        PluginLogEventKeys.DENIAL_REASON to "NOT_DECLARED",
                    ),
                ),
            ),
            permissionRuntimeFixHint = "Grant permission and retry",
        )

        val report = snapshot.getInstalledReport(plugin.manifest.id)

        assertThat(report).isNotNull()
        assertThat(report?.entries).hasSize(2)
        assertThat(report?.entries?.map { it.source })
            .containsExactly(PluginDiagnosticSource.RUNTIME, PluginDiagnosticSource.RUNTIME)
        assertThat(report?.entries?.map { it.issue.category })
            .containsExactly(
                PluginDiagnosticCategory.PERMISSIONS,
                PluginDiagnosticCategory.PERMISSIONS,
            )
        assertThat(report?.entries?.map { it.issue.message })
            .containsExactly(
                "Permission denied for tina.commands.execute: command.execute is not declared in manifest",
                "Permission denied for tina.fs.readFile: file.read is not granted",
            )
        assertThat(report?.entries?.all { it.issue.fixHint == "Grant permission and retry" }).isTrue()
    }

    @Test
    fun `create should merge supplied lsp runtime diagnostics`() {
        val plugin = installedPlugin(id = "lsp.plugin", name = "LSP Plugin")
        val lspEntry = PluginDiagnosticEntry(
            source = PluginDiagnosticSource.RUNTIME,
            issue = PluginDiagnosticIssue(
                severity = PluginDiagnosticSeverity.ERROR,
                category = PluginDiagnosticCategory.LSP,
                message = "LSP dependency failed",
                fixHint = "Repair dependencies",
            ),
        )

        val snapshot = PluginDiagnosticsSnapshotFactory.create(
            installedPlugins = listOf(plugin),
            loadReports = emptyList(),
            healthReports = emptyMap(),
            scriptPluginStates = emptyMap(),
            runtimeFixHint = "Reload after fix",
            pluginLogs = emptyList(),
            permissionRuntimeFixHint = "Grant permission and retry",
            lspRuntimeEntriesByPluginId = mapOf(plugin.manifest.id to listOf(lspEntry)),
        )

        assertThat(snapshot.getInstalledReport(plugin.manifest.id)?.entries).containsExactly(lspEntry)
        assertThat(snapshot.getInstalledReport(plugin.manifest.id)?.highestSeverity)
            .isEqualTo(PluginDiagnosticSeverity.ERROR)
    }

    @Test
    fun `create should merge supplied command runtime diagnostics`() {
        val plugin = installedPlugin(id = "command.plugin", name = "Command Plugin")
        val commandEntry = PluginDiagnosticEntry(
            source = PluginDiagnosticSource.RUNTIME,
            issue = PluginDiagnosticIssue(
                severity = PluginDiagnosticSeverity.WARNING,
                category = PluginDiagnosticCategory.PERMISSIONS,
                message = "Command plugin.run is unavailable: command.execute is not granted",
                fixHint = "Grant permission",
            ),
        )

        val snapshot = PluginDiagnosticsSnapshotFactory.create(
            installedPlugins = listOf(plugin),
            loadReports = emptyList(),
            healthReports = emptyMap(),
            scriptPluginStates = emptyMap(),
            runtimeFixHint = "Reload after fix",
            pluginLogs = emptyList(),
            permissionRuntimeFixHint = "Grant permission and retry",
            commandRuntimeEntriesByPluginId = mapOf(plugin.manifest.id to listOf(commandEntry)),
        )

        assertThat(snapshot.getInstalledReport(plugin.manifest.id)?.entries).containsExactly(commandEntry)
        assertThat(snapshot.getInstalledReport(plugin.manifest.id)?.highestSeverity)
            .isEqualTo(PluginDiagnosticSeverity.WARNING)
    }

    private fun installedPlugin(
        id: String,
        name: String,
    ): InstalledPlugin = InstalledPlugin(
        manifest = PluginManifest(
            id = id,
            name = name,
            version = "1.0.0",
        ),
        directory = File("build/$id"),
        enabled = true,
    )
}
