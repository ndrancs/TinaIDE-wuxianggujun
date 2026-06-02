package com.wuxianggujun.tinaide.core.proot

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class PRootBootstrapModelsTest {

    @Test
    fun packageInfo_shouldMatchOnlyExactPackageName() {
        val packageInfo = PRootBootstrap.PackageInfo(
            name = "clang-format",
            displayName = "Clang Format",
            status = PRootBootstrap.PackageStatus.INSTALLING
        )

        assertThat(packageInfo.matchesPackageName("clang-format")).isTrue()
        assertThat(packageInfo.matchesPackageName("clang")).isFalse()
        assertThat(packageInfo.matchesPackageName("Clang-Format")).isFalse()
        assertThat(packageInfo.matchesPackageName(" clang-format ")).isFalse()
    }

    @Test
    fun packageInfo_shouldRejectBlankPackageName() {
        val packageInfo = PRootBootstrap.PackageInfo(
            name = "cmake",
            displayName = "CMake"
        )

        assertThat(packageInfo.matchesPackageName(null)).isFalse()
        assertThat(packageInfo.matchesPackageName("")).isFalse()
        assertThat(packageInfo.matchesPackageName("   ")).isFalse()
    }

    @Test
    fun packageInfo_shouldDefaultToPendingStatus() {
        val packageInfo = PRootBootstrap.PackageInfo(
            name = "ninja-build",
            displayName = "Ninja"
        )

        assertThat(packageInfo.status).isEqualTo(PRootBootstrap.PackageStatus.PENDING)
    }
}
