package com.wuxianggujun.tinaide.ui.workspace.model

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class DependencyInstallUiStateTest {

    @Test
    fun completedState_shouldRepresentReadyEnvironment() {
        val state = DependencyInstallUiState(
            installPhase = InstallPhase.COMPLETED,
            progress = 1f,
            statusMessage = "done",
            envReady = true,
            rootfsHealth = DependencyRootfsHealthUiState(
                status = DependencyRootfsHealthStatus.READY,
                statusText = "ready",
                detailText = "rootfs ok"
            )
        )

        assertThat(state.installPhase).isEqualTo(InstallPhase.COMPLETED)
        assertThat(state.progress).isEqualTo(1f)
        assertThat(state.envReady).isTrue()
        assertThat(state.rootfsHealth.status).isEqualTo(DependencyRootfsHealthStatus.READY)
    }

    @Test
    fun failedState_shouldCarryFailureDetails() {
        val state = DependencyInstallUiState(
            installPhase = InstallPhase.FAILED,
            failedMessage = "network timeout",
            isNetworkRelated = true,
            rootfsHealth = DependencyRootfsHealthUiState(
                status = DependencyRootfsHealthStatus.ATTENTION,
                detailText = "rootfs needs repair"
            )
        )

        assertThat(state.installPhase).isEqualTo(InstallPhase.FAILED)
        assertThat(state.failedMessage).isEqualTo("network timeout")
        assertThat(state.isNetworkRelated).isTrue()
        assertThat(state.rootfsHealth.status).isEqualTo(DependencyRootfsHealthStatus.ATTENTION)
    }

    @Test
    fun installEvents_shouldUseStableValueSemantics() {
        assertThat(DependencyInstallEvent.NavigateToProjectManager)
            .isSameInstanceAs(DependencyInstallEvent.NavigateToProjectManager)
        assertThat(DependencyInstallEvent.InstallCompleted)
            .isSameInstanceAs(DependencyInstallEvent.InstallCompleted)
        assertThat(DependencyInstallEvent.ShowToast("done"))
            .isEqualTo(DependencyInstallEvent.ShowToast("done"))
    }
}
