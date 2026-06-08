package com.wuxianggujun.tinaide.ui.compose.screens.packages

import android.app.Application
import android.content.Context
import com.google.common.truth.Truth.assertThat
import com.wuxianggujun.tinaide.core.packages.InstalledPackageInfo
import com.wuxianggujun.tinaide.core.packages.PackageInstallPlan
import com.wuxianggujun.tinaide.core.packages.PackageInstallPlanItem
import com.wuxianggujun.tinaide.core.packages.PackageManager
import com.wuxianggujun.tinaide.core.packages.UpdateInfo
import com.wuxianggujun.tinaide.core.packages.model.GUIPackage
import com.wuxianggujun.tinaide.core.packages.model.InstallProgressEvent
import com.wuxianggujun.tinaide.core.packages.model.InstallResult
import com.wuxianggujun.tinaide.core.packages.model.InstallType
import com.wuxianggujun.tinaide.core.packages.model.PackageCategory
import com.wuxianggujun.tinaide.core.packages.model.PackageInstallState
import com.wuxianggujun.tinaide.core.packages.model.Platform
import com.wuxianggujun.tinaide.core.packages.model.PlatformPackage
import com.wuxianggujun.tinaide.core.packages.model.UninstallResult
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestWatcher
import org.junit.runner.Description
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(
    sdk = [34],
    manifest = Config.NONE,
    application = Application::class
)
class PackageManagerViewModelTest {

    @get:Rule
    val mainDispatcherRule = PackageManagerMainDispatcherRule()

    @Test
    fun `batchInstall previews dependencies before installing selected packages`() = runTest {
        val packageManager = FakePackageManager(
            availablePackages = listOf(
                guiPackage("demo"),
                guiPackage("sdl3")
            ),
            previewPlans = mapOf(
                "demo" to installPlan("demo", dependencyId = "sdl3")
            )
        )
        val viewModel = PackageManagerViewModel(testStringContext(), packageManager)

        viewModel.toggleSelectionMode()
        viewModel.togglePackageSelection("demo")
        viewModel.batchInstall(Platform.ANDROID)

        val dialog = viewModel.dialogState.value as PackageDialogState.BatchInstallConfirm
        assertThat(dialog.packageIds).containsExactly("demo")
        assertThat(dialog.plans.single().packages.map { it.packageId })
            .containsExactly("sdl3", "demo")
            .inOrder()
        assertThat(packageManager.installCalls).isEmpty()

        viewModel.confirmBatchInstall()

        assertThat(packageManager.installCalls).containsExactly("demo" to Platform.ANDROID)
    }

    @Test
    fun `updateAllPackages previews dependencies before installing updates`() = runTest {
        val update = UpdateInfo(
            packageId = "demo",
            packageName = "Demo",
            platform = Platform.ANDROID,
            currentVersion = "1.0.0",
            newVersion = "1.1.0"
        )
        val packageManager = FakePackageManager(
            availablePackages = listOf(
                guiPackage("demo"),
                guiPackage("sdl3")
            ),
            updates = mapOf("demo" to update),
            previewPlans = mapOf(
                "demo" to installPlan("demo", dependencyId = "sdl3")
            )
        )
        val viewModel = PackageManagerViewModel(testStringContext(), packageManager)

        viewModel.checkForUpdates()
        viewModel.updateAllPackages()

        val dialog = viewModel.dialogState.value as PackageDialogState.BatchUpdateConfirm
        assertThat(dialog.updates).containsExactly(update)
        assertThat(dialog.plans.single().packages.map { it.packageId })
            .containsExactly("sdl3", "demo")
            .inOrder()
        assertThat(packageManager.installCalls).isEmpty()

        viewModel.confirmBatchUpdate()

        assertThat(packageManager.installCalls).containsExactly("demo" to Platform.ANDROID)
    }

    private fun guiPackage(packageId: String): GUIPackage = GUIPackage(
        id = packageId,
        name = packageId.replaceFirstChar { it.uppercase() },
        android = PlatformPackage(
            version = "1.0.0",
            installType = InstallType.DOWNLOAD,
            dependencies = emptyList()
        )
    )

    private fun installPlan(
        packageId: String,
        dependencyId: String
    ): PackageInstallPlan = PackageInstallPlan(
        packageId = packageId,
        packageName = packageId,
        platform = Platform.ANDROID,
        packages = listOf(
            PackageInstallPlanItem(
                packageId = dependencyId,
                packageName = dependencyId,
                version = "1.0.0",
                isRoot = false,
                isAlreadyInstalled = false
            ),
            PackageInstallPlanItem(
                packageId = packageId,
                packageName = packageId,
                version = "1.0.0",
                isRoot = true,
                isAlreadyInstalled = false
            )
        )
    )

    private fun testStringContext(): Context {
        val filesDir = RuntimeEnvironment.getApplication().filesDir
        val context = mockk<Context>(relaxed = true)
        every { context.applicationContext } returns context
        every { context.filesDir } returns filesDir
        every { context.getString(any<Int>()) } answers { "res:${firstArg<Int>()}" }
        every { context.getString(any<Int>(), *anyVararg()) } answers {
            "res:${firstArg<Int>()}"
        }
        return context
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class PackageManagerMainDispatcherRule(
    private val dispatcher: TestDispatcher = UnconfinedTestDispatcher()
) : TestWatcher() {
    override fun starting(description: Description) {
        Dispatchers.setMain(dispatcher)
    }

    override fun finished(description: Description) {
        Dispatchers.resetMain()
    }
}

private class FakePackageManager(
    private val availablePackages: List<GUIPackage>,
    private val updates: Map<String, UpdateInfo> = emptyMap(),
    private val previewPlans: Map<String, PackageInstallPlan> = emptyMap()
) : PackageManager {
    val installCalls = mutableListOf<Pair<String, Platform>>()

    override suspend fun getAvailablePackages(
        page: Int,
        pageSize: Int,
        category: String?,
        platform: Platform?,
        search: String?
    ): Result<List<GUIPackage>> = Result.success(availablePackages)

    override suspend fun getCategories(): Result<List<PackageCategory>> = Result.success(emptyList())

    override suspend fun getPackageDetail(packageId: String): Result<GUIPackage> = Result.success(availablePackages.first { it.id == packageId })

    override suspend fun getInstallState(packageId: String): PackageInstallState = PackageInstallState()

    override suspend fun previewInstallPlan(
        packageId: String,
        platform: Platform
    ): Result<PackageInstallPlan> = Result.success(
        previewPlans[packageId] ?: PackageInstallPlan(
            packageId = packageId,
            packageName = packageId,
            platform = platform,
            packages = listOf(
                PackageInstallPlanItem(
                    packageId = packageId,
                    packageName = packageId,
                    version = "1.0.0",
                    isRoot = true,
                    isAlreadyInstalled = false
                )
            )
        )
    )

    override suspend fun install(
        packageId: String,
        platform: Platform,
        progress: (InstallProgressEvent) -> Unit
    ): InstallResult {
        installCalls += packageId to platform
        return InstallResult.Success(
            packageId = packageId,
            version = "1.0.0",
            platform = platform
        )
    }

    override suspend fun uninstall(
        packageId: String,
        platform: Platform
    ): UninstallResult = UninstallResult.Success(packageId, platform)

    override suspend fun getInstalledPackages(): List<InstalledPackageInfo> = emptyList()

    override suspend fun getDependentPackages(packageId: String, platform: Platform): List<String> = emptyList()

    override suspend fun refreshCache() = Unit

    override suspend fun clearCache() = Unit

    override suspend fun checkForUpdates(): Map<String, UpdateInfo> = updates
}
