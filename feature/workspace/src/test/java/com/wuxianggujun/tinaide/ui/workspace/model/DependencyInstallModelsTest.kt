package com.wuxianggujun.tinaide.ui.workspace.model

import com.google.common.truth.Truth.assertThat
import com.wuxianggujun.tinaide.core.proot.PRootBootstrap
import org.junit.Test

class DependencyInstallModelsTest {

    @Test
    fun dependencyInstallUiState_shouldExposeSafeDefaults() {
        val state = DependencyInstallUiState()

        assertThat(state.installPhase).isEqualTo(InstallPhase.INSTALLING)
        assertThat(state.progress).isEqualTo(0f)
        assertThat(state.statusMessage).isEmpty()
        assertThat(state.installStage).isEqualTo(PRootBootstrap.InstallStage.INSTALLING_DISTRO)
        assertThat(state.packageList).isEmpty()
        assertThat(state.currentPackage).isNull()
        assertThat(state.failedMessage).isEmpty()
        assertThat(state.isNetworkRelated).isFalse()
        assertThat(state.isPaused).isFalse()
        assertThat(state.envReady).isFalse()
        assertThat(state.rootfsHealth.status).isEqualTo(DependencyRootfsHealthStatus.UNKNOWN)
        assertThat(state.rootfsHealth.statusText).isEmpty()
        assertThat(state.rootfsHealth.detailText).isEmpty()
    }

    @Test
    fun dependencyRootfsHealthUiState_shouldPreserveAttentionAndUnavailableDetails() {
        val attention = DependencyRootfsHealthUiState(
            status = DependencyRootfsHealthStatus.ATTENTION,
            statusText = "needs attention",
            detailText = "optional bootstrap command is missing"
        )
        val unavailable = DependencyRootfsHealthUiState(
            status = DependencyRootfsHealthStatus.UNAVAILABLE,
            statusText = "unavailable",
            detailText = "required bootstrap command is missing"
        )

        assertThat(attention.status).isEqualTo(DependencyRootfsHealthStatus.ATTENTION)
        assertThat(attention.detailText).contains("optional")
        assertThat(unavailable.status).isEqualTo(DependencyRootfsHealthStatus.UNAVAILABLE)
        assertThat(unavailable.detailText).contains("required")
    }
}
