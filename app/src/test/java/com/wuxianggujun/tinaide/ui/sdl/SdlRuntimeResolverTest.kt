package com.wuxianggujun.tinaide.ui.sdl

import android.app.Application
import android.content.Context
import com.google.common.truth.Truth.assertThat
import java.io.File
import java.nio.file.Files
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(
    sdk = [34],
    manifest = Config.NONE,
    application = Application::class,
)
class SdlRuntimeResolverTest {

    @Test
    fun `resolve finds SDL library from extra runtime dirs`() {
        val tempDir = Files.createTempDirectory("sdl-runtime-extra-dir-test").toFile()
        try {
            val artifactDir = File(tempDir, "outside-build").apply { mkdirs() }
            val runtimeDir = File(tempDir, "runtime-libs").apply { mkdirs() }
            val main = File(artifactDir, "libmain.so").apply {
                writeText("NEEDED libSDL3.so")
            }
            val sdl = File(runtimeDir, "libSDL3.so").apply { writeText("sdl") }

            val result = SdlRuntimeResolver.resolve(
                context = appContext(),
                mainLibraryPath = main.absolutePath,
                extraRuntimeLibDirs = listOf(runtimeDir),
            )

            assertThat(result).isInstanceOf(SdlRuntimeResolver.ResolveResult.Sdl::class.java)
            val spec = (result as SdlRuntimeResolver.ResolveResult.Sdl).spec
            assertThat(File(spec.sdlLibraryPath).canonicalPath).isEqualTo(sdl.canonicalPath)
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun `resolvePreloadLibraries reports missing non system libraries`() {
        val tempDir = Files.createTempDirectory("sdl-runtime-resolver-test").toFile()
        try {
            val main = File(tempDir, "libmain.so").apply { writeText("main") }
            val sdl = File(tempDir, "libSDL3.so").apply { writeText("sdl") }

            val result = SdlRuntimeResolver.resolvePreloadLibraries(
                runtimeIndex = emptyMap(),
                neededLibraries = setOf("libSDL3.so", "libSDL3_image.so", "libandroid.so"),
                mainLibrary = main,
                sdlLibrary = sdl
            )

            assertThat(result.libraryPaths).isEmpty()
            assertThat(result.missingLibraries).containsExactly("libSDL3_image.so")
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun `resolvePreloadLibraries skips OS provided NDK system libraries`() {
        val tempDir = Files.createTempDirectory("sdl-runtime-ndk-system-test").toFile()
        try {
            val main = File(tempDir, "libmain.so").apply { writeText("main") }
            val sdl = File(tempDir, "libSDL3.so").apply { writeText("sdl") }

            // libmediandk.so 等由 OS 提供，绝不能进 preload(会被复制进私有目录后 dlopen 失败)，
            // 也绝不能进 missing(误报“缺少运行库”)。
            val result = SdlRuntimeResolver.resolvePreloadLibraries(
                runtimeIndex = emptyMap(),
                neededLibraries = setOf(
                    "libmediandk.so",
                    "libnativewindow.so",
                    "libvulkan.so",
                    "libaaudio.so",
                    "libcamera2ndk.so",
                ),
                mainLibrary = main,
                sdlLibrary = sdl
            )

            assertThat(result.libraryPaths).isEmpty()
            assertThat(result.missingLibraries).isEmpty()
        } finally {
            tempDir.deleteRecursively()
        }
    }

    private fun appContext(): Context = RuntimeEnvironment.getApplication().applicationContext
}
