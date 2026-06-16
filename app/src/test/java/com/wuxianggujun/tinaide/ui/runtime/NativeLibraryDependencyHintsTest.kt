package com.wuxianggujun.tinaide.ui.runtime

import com.google.common.truth.Truth.assertThat
import com.wuxianggujun.tinaide.core.packages.model.GUIPackage
import java.io.File
import java.nio.file.Files
import org.junit.Test

class NativeLibraryDependencyHintsTest {

    @Test
    fun `inferPackageIds maps known SDL extension libraries`() {
        val packageIds = NativeLibraryDependencyHints.inferPackageIds(
            listOf("libSDL3_image.so", "libSDL3_ttf.so.0", "libunknown.so")
        )

        assertThat(packageIds).containsExactly("sdl3-image", "sdl3-ttf").inOrder()
    }

    @Test
    fun `inferPackageIds prefers available package index before fallback hints`() {
        val packageIds = NativeLibraryDependencyHints.inferPackageIds(
            libraryNames = listOf("libSkiaSharp.so", "libSDL3_image.so"),
            availablePackages = listOf(
                GUIPackage(id = "skia-sharp", name = "SkiaSharp"),
                GUIPackage(id = "sdl3-image", name = "SDL3 Image")
            )
        )

        assertThat(packageIds).containsExactly("sdl3-image", "skia-sharp").inOrder()
    }

    @Test
    fun `inferPackageIds uses installed library index before fallback hints`() {
        val packageIds = NativeLibraryDependencyHints.inferPackageIds(
            libraryNames = listOf("libSDL3_image.so"),
            installedLibraryPackageIndex = mapOf("libSDL3_image.so" to "custom-sdl3-image")
        )

        assertThat(packageIds).containsExactly("custom-sdl3-image", "sdl3-image").inOrder()
    }

    @Test
    fun `filterUnresolvedLibraries removes imported matching libraries`() {
        val tempDir = Files.createTempDirectory("native-library-hints-test").toFile()
        try {
            val imported = File(tempDir, "libSDL3_image.so").apply { writeText("image") }

            val unresolved = NativeLibraryDependencyHints.filterUnresolvedLibraries(
                missingLibraries = listOf("libSDL3_image.so.0", "libSDL3_ttf.so"),
                providedLibraries = listOf(imported)
            )

            assertThat(unresolved).containsExactly("libSDL3_ttf.so")
        } finally {
            tempDir.deleteRecursively()
        }
    }
}
