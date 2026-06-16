package com.wuxianggujun.tinaide.plugin

import android.content.Context
import com.wuxianggujun.tinaide.core.i18n.Strings
import com.wuxianggujun.tinaide.core.i18n.strOr

val PluginLoadIssue.displayName: String
    get() = pluginName ?: directoryName

fun PluginLoadIssue.toDiagnosticIssue(context: Context): PluginDiagnosticIssue = when (type) {
    PluginLoadIssueType.MISSING_MANIFEST -> PluginDiagnosticIssue(
        severity = PluginDiagnosticSeverity.ERROR,
        category = PluginDiagnosticCategory.MANIFEST,
        message = message,
        fixHint = Strings.plugin_diagnostic_load_missing_manifest_fix.strOr(context)
    )

    PluginLoadIssueType.INVALID_MANIFEST -> PluginDiagnosticIssue(
        severity = PluginDiagnosticSeverity.ERROR,
        category = PluginDiagnosticCategory.MANIFEST,
        message = message,
        fixHint = Strings.plugin_diagnostic_load_invalid_manifest_fix.strOr(context)
    )
}

fun PluginLoadIssue.toDiagnosticsReport(context: Context): PluginDiagnosticsReport = PluginDiagnosticsReport(
    pluginId = pluginId,
    pluginName = displayName,
    directoryName = directoryName,
    isInstalled = false,
    entries = listOf(
        PluginDiagnosticEntry(
            source = PluginDiagnosticSource.LOAD,
            issue = toDiagnosticIssue(context),
        )
    ),
)
