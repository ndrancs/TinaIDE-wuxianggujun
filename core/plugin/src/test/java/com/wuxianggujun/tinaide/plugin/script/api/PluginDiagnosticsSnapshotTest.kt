package com.wuxianggujun.tinaide.plugin.script.api

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class PluginDiagnosticsSnapshotTest {

    @Test
    fun counts_shouldGroupDiagnosticsBySeverity() {
        val snapshot = PluginDiagnosticsSnapshot(
            diagnostics = listOf(
                diagnostic("error"),
                diagnostic("warning"),
                diagnostic("warning"),
                diagnostic("info"),
                diagnostic("hint")
            )
        )

        assertThat(snapshot.totalCount).isEqualTo(5)
        assertThat(snapshot.errorCount).isEqualTo(1)
        assertThat(snapshot.warningCount).isEqualTo(2)
        assertThat(snapshot.infoCount).isEqualTo(1)
        assertThat(snapshot.hintCount).isEqualTo(1)
    }

    @Test
    fun unavailable_shouldExposeEmptySnapshotWithError() {
        val snapshot = PluginDiagnosticsSnapshot.unavailable("src/Main.kt")

        assertThat(snapshot.available).isFalse()
        assertThat(snapshot.requestedFilePath).isEqualTo("src/Main.kt")
        assertThat(snapshot.diagnostics).isEmpty()
        assertThat(snapshot.error).isEqualTo("Diagnostics provider unavailable")
    }

    private fun diagnostic(severity: String): PluginDiagnosticItem = PluginDiagnosticItem(
        fileUri = "file:///workspace/src/Main.kt",
        filePath = "src/Main.kt",
        fileName = "Main.kt",
        line = 1,
        column = 2,
        endLine = 1,
        endColumn = 3,
        message = "message",
        severity = severity
    )
}
