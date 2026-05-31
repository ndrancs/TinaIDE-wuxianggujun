package com.wuxianggujun.tinaide.ui.workspace.model

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class DependencyInstallModelsTest {

    @Test
    fun dependencyInstallUiState_shouldExposeSafeDefaults() {
        val state = DependencyInstallUiState()

        assertThat(state.installPhase).isEqualTo(InstallPhase.INSTALLING)
        assertThat(state.progress).isEqualTo(0f)
        assertThat(state.packageList).isEmpty()
        assertThat(state.currentPackage).isNull()
        assertThat(state.envReady).isFalse()
        assertThat(state.rootfsHealth.status).isEqualTo(DependencyRootfsHealthStatus.UNKNOWN)
    }
}
