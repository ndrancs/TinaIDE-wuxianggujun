package com.wuxianggujun.tinaide.ui.runtime

import com.google.common.truth.Truth.assertThat
import java.io.File
import java.nio.file.Files
import org.junit.Test

class SdlRuntimeLibraryStagerTest {

    @Test
    fun `stage copies public main library and sibling project libraries into private dir`() {
        val tempRoot = Files.createTempDirectory("sdl-runtime-stage-test").toFile()
        try {
            val publicDir = File(tempRoot, "public-build").apply { mkdirs() }
            val stageRoot = File(tempRoot, "private-stage")
            val mainLibrary = File(publicDir, "libmain.so").apply { writeText("main") }
            val siblingLibrary = File(publicDir, "libhelper.so").apply { writeText("helper") }
            val privateRuntime = File(tempRoot, "app-data/runtime/libSDL3.so").apply {
                parentFile?.mkdirs()
                writeText("sdl")
            }

            val result = SdlRuntimeLibraryStager.stage(
                mainLibrary = mainLibrary,
                preloadLibraryPaths = listOf(privateRuntime.absolutePath),
                stageRootDir = stageRoot,
                privatePathPrefixes = listOf(File(tempRoot, "app-data").absolutePath)
            )

            assertThat(result).isInstanceOf(SdlRuntimeLibraryStager.StageResult.Success::class.java)
            val success = result as SdlRuntimeLibraryStager.StageResult.Success
            val stagedMain = File(success.runtime.mainLibraryPath)
            val stagedHelper = File(stagedMain.parentFile, siblingLibrary.name)

            assertThat(stagedMain.isFile).isTrue()
            assertThat(stagedMain.readText()).isEqualTo("main")
            assertThat(stagedMain.absolutePath).doesNotContain(publicDir.absolutePath)

            assertThat(stagedHelper.isFile).isTrue()
            assertThat(stagedHelper.readText()).isEqualTo("helper")

            assertThat(success.runtime.preloadLibraryPaths).contains(privateRuntime.absolutePath)
            assertThat(success.runtime.preloadLibraryPaths).contains(stagedHelper.absolutePath)
        } finally {
            tempRoot.deleteRecursively()
        }
    }
}
