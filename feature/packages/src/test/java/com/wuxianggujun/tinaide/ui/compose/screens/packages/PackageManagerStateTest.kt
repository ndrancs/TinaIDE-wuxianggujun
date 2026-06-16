package com.wuxianggujun.tinaide.ui.compose.screens.packages

import com.google.common.truth.Truth.assertThat
import com.wuxianggujun.tinaide.core.packages.PackageInstallPlan
import com.wuxianggujun.tinaide.core.packages.PackageInstallPlanItem
import com.wuxianggujun.tinaide.core.packages.model.GUIPackage
import com.wuxianggujun.tinaide.core.packages.model.InstallProgressEvent
import com.wuxianggujun.tinaide.core.packages.model.InstallType
import com.wuxianggujun.tinaide.core.packages.model.Platform
import com.wuxianggujun.tinaide.core.packages.model.PlatformPackage
import org.junit.Test

class PackageManagerStateTest {

    @Test
    fun uiState_shouldExposeIdleDefaults() {
        val state = PackageManagerUiState()

        assertThat(state.isLoading).isFalse()
        assertThat(state.packages).isEmpty()
        assertThat(state.filteredPackages).isEmpty()
        assertThat(state.installStates).isEmpty()
        assertThat(state.selectedPackageIds).isEmpty()
        assertThat(state.isSelectionMode).isFalse()
        assertThat(state.currentDetailPackage).isNull()
    }

    @Test
    fun dialogState_shouldCarryInstallContext() {
        val installing = PackageDialogState.Installing(
            packageId = "sdl3",
            packageName = "SDL3",
            platform = Platform.ANDROID,
            event = InstallProgressEvent.Preparing("start")
        )
        val confirm = PackageDialogState.UninstallConfirm(
            packageId = "sdl3",
            packageInfo = GUIPackage(id = "sdl3", name = "SDL3"),
            platform = Platform.ANDROID,
            dependentPackages = listOf("demo")
        )
        val installConfirm = PackageDialogState.InstallConfirm(
            packageId = "demo",
            packageInfo = GUIPackage(id = "demo", name = "Demo"),
            platform = Platform.ANDROID,
            plan = PackageInstallPlan(
                packageId = "demo",
                packageName = "Demo",
                platform = Platform.ANDROID,
                packages = listOf(
                    PackageInstallPlanItem(
                        packageId = "sdl3",
                        packageName = "SDL3",
                        version = "3.2.0",
                        isRoot = false,
                        isAlreadyInstalled = false
                    ),
                    PackageInstallPlanItem(
                        packageId = "demo",
                        packageName = "Demo",
                        version = "1.0.0",
                        isRoot = true,
                        isAlreadyInstalled = false
                    )
                )
            )
        )

        assertThat(installing.packageName).isEqualTo("SDL3")
        assertThat(confirm.dependentPackages).containsExactly("demo")
        assertThat(installConfirm.plan.packages.filterNot { it.isRoot }.map { it.packageId })
            .containsExactly("sdl3")
    }

    @Test
    fun resolvePreferredInstallPlatform_shouldPreferAndroidWhenBothPlatformsAvailable() {
        val pkg = GUIPackage(
            id = "sdl3",
            name = "SDL3",
            linux = platformPackage(),
            android = platformPackage()
        )

        val platform = PackageInstallUiStateSupport.resolvePreferredInstallPlatform(pkg)

        assertThat(platform).isEqualTo(Platform.ANDROID)
    }

    @Test
    fun resolveAvailableInstallPlatform_shouldFallbackToAvailablePlatformWhenPreferredMissing() {
        val androidOnly = GUIPackage(
            id = "cmake",
            name = "CMake",
            android = platformPackage()
        )
        val linuxOnly = GUIPackage(
            id = "ninja",
            name = "Ninja",
            linux = platformPackage()
        )

        assertThat(
            PackageInstallUiStateSupport.resolveAvailableInstallPlatform(androidOnly, Platform.LINUX)
        ).isEqualTo(Platform.ANDROID)
        assertThat(
            PackageInstallUiStateSupport.resolveAvailableInstallPlatform(linuxOnly, Platform.ANDROID)
        ).isEqualTo(Platform.LINUX)
        assertThat(
            PackageInstallUiStateSupport.resolveAvailableInstallPlatform(
                GUIPackage(id = "empty", name = "Empty"),
                Platform.ANDROID
            )
        ).isNull()
    }

    @Test
    fun progressFromEvent_shouldMapProgressEventsAndIgnoreNonProgressEvents() {
        assertThat(
            PackageInstallUiStateSupport.progressFromEvent(
                InstallProgressEvent.Downloading(downloaded = 25L, total = 100L, speed = 10L)
            )
        ).isEqualTo(0.25f)
        assertThat(
            PackageInstallUiStateSupport.progressFromEvent(InstallProgressEvent.Extracting(0.7f))
        ).isEqualTo(0.7f)
        assertThat(
            PackageInstallUiStateSupport.progressFromEvent(
                InstallProgressEvent.Completed(
                    com.wuxianggujun.tinaide.core.packages.model.InstallResult.Success(
                        packageId = "sdl3",
                        version = "1.0.0",
                        platform = Platform.ANDROID
                    )
                )
            )
        ).isEqualTo(1f)
        assertThat(
            PackageInstallUiStateSupport.progressFromEvent(InstallProgressEvent.Preparing("start"))
        ).isNull()
    }

    private fun platformPackage(): PlatformPackage = PlatformPackage(
        version = "1.0.0",
        installType = InstallType.DOWNLOAD,
        dependencies = emptyList()
    )
}
