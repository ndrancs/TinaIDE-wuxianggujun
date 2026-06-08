package com.wuxianggujun.tinaide.core.packages

import com.google.common.truth.Truth.assertThat
import com.wuxianggujun.tinaide.core.packages.model.GUIPackage
import com.wuxianggujun.tinaide.core.packages.model.InstallType
import com.wuxianggujun.tinaide.core.packages.model.Platform
import com.wuxianggujun.tinaide.core.packages.model.PlatformPackage
import kotlinx.coroutines.runBlocking
import org.junit.Test

class PackageDependencyResolverTest {

    @Test
    fun `resolveInstallPlan installs dependencies first and skips installed dependencies`() = runBlocking {
        val descriptors = listOf(
            descriptor("sdl3"),
            descriptor("sdl3-image", dependencies = listOf("sdl3")),
            descriptor("game-template", dependencies = listOf("sdl3-image"))
        ).associateBy { it.packageId }
        val installed = setOf("sdl3")

        val result = PackageDependencyResolver.resolveInstallPlan(
            rootPackageId = "game-template",
            platform = Platform.ANDROID,
            loadPackage = { Result.success(descriptors.getValue(it)) },
            isInstalled = { packageId, _ -> packageId in installed }
        )

        assertThat(result.isSuccess).isTrue()
        assertThat(result.getOrThrow().map { it.packageId })
            .containsExactly("sdl3-image", "game-template")
            .inOrder()
    }

    @Test
    fun `resolveInstallPlan reports circular dependencies`() = runBlocking {
        val descriptors = listOf(
            descriptor("sdl3", dependencies = listOf("sdl3-image")),
            descriptor("sdl3-image", dependencies = listOf("sdl3"))
        ).associateBy { it.packageId }

        val result = PackageDependencyResolver.resolveInstallPlan(
            rootPackageId = "sdl3",
            platform = Platform.ANDROID,
            loadPackage = { Result.success(descriptors.getValue(it)) },
            isInstalled = { _, _ -> false }
        )

        assertThat(result.isFailure).isTrue()
        assertThat(result.exceptionOrNull()?.message)
            .contains("Circular package dependency: sdl3 -> sdl3-image -> sdl3")
    }

    @Test
    fun `findDirectDependents returns packages that directly depend on package`() = runBlocking {
        val packages = listOf(
            guiPackage("sdl3", "SDL3"),
            guiPackage("sdl3-image", "SDL3 Image", dependencies = listOf("sdl3")),
            guiPackage("sdl3-ttf", "SDL3 TTF", dependencies = listOf("sdl3")),
            guiPackage("box2d", "Box2D")
        )

        val dependents = PackageDependencyResolver.findDirectDependents(
            packageId = "sdl3",
            platform = Platform.ANDROID,
            packages = packages,
            dependenciesForPackage = { _, platformPackage ->
                platformPackage.dependencies.normalizedPackageDependencies()
            }
        )

        assertThat(dependents)
            .containsExactly("SDL3 Image", "SDL3 TTF")
            .inOrder()
    }

    private fun descriptor(
        packageId: String,
        dependencies: List<String> = emptyList()
    ): PackageInstallDescriptor = PackageInstallDescriptor(
        packageId = packageId,
        packageName = packageId,
        platform = Platform.ANDROID,
        platformPackage = platformPackage(dependencies),
        dependencies = dependencies.normalizedPackageDependencies()
    )

    private fun guiPackage(
        packageId: String,
        name: String,
        dependencies: List<String> = emptyList()
    ): GUIPackage = GUIPackage(
        id = packageId,
        name = name,
        android = platformPackage(dependencies)
    )

    private fun platformPackage(dependencies: List<String>): PlatformPackage = PlatformPackage(
        version = "1.0.0",
        installType = InstallType.DOWNLOAD,
        dependencies = dependencies
    )
}
