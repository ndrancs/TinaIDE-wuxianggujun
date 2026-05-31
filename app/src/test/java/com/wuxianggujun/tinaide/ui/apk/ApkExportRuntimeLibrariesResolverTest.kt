package com.wuxianggujun.tinaide.ui.apk

import com.google.common.truth.Truth.assertThat
import java.io.File
import java.nio.file.Files
import org.junit.Test

class ApkExportRuntimeLibrariesResolverTest {

    @Test
    fun `resolveDependencyClosure collects transitive dependencies and skips system libs`() {
        val tempDir = Files.createTempDirectory("apk-export-runtime-libs-test").toFile()
        val main = File(tempDir, "libmain.so").apply { writeText("main") }
        val foo = File(tempDir, "libfoo.so").apply { writeText("foo") }
        val bar = File(tempDir, "libbar.so").apply { writeText("bar") }

        try {
            val result = ApkExportRuntimeLibrariesResolver.resolveDependencyClosure(
                rootLibraries = listOf(main),
                runtimeIndex = mapOf(
                    "libfoo.so" to foo,
                    "libbar.so" to bar
                ),
                dependencyReader = { file ->
                    when (file.name) {
                        "libmain.so" -> setOf("libfoo.so", "libandroid.so")
                        "libfoo.so" -> setOf("libbar.so")
                        else -> emptySet()
                    }
                },
                systemLibraries = setOf("libandroid.so")
            )

            assertThat(result.libraries.map { it.name }).containsExactly("libfoo.so", "libbar.so").inOrder()
            assertThat(result.missingLibraries).isEmpty()
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun `buildRuntimeLibraryIndex exposes canonical name for versioned library`() {
        val tempDir = Files.createTempDirectory("apk-export-runtime-index-test").toFile()
        val versionedSdl = File(tempDir, "libSDL3.so.0").apply { writeText("sdl") }

        try {
            val index = ApkExportRuntimeLibrariesResolver.buildRuntimeLibraryIndex(
                listOf(versionedSdl)
            )

            assertThat(index["libSDL3.so.0"]?.name).isEqualTo("libSDL3.so.0")
            assertThat(index["libSDL3.so"]?.name).isEqualTo("libSDL3.so.0")
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun `resolveDependencyClosure skips GLES compatibility alias as system library`() {
        val tempDir = Files.createTempDirectory("apk-export-runtime-system-lib-test").toFile()
        val main = File(tempDir, "libmain.so").apply { writeText("main") }

        try {
            val result = ApkExportRuntimeLibrariesResolver.resolveDependencyClosure(
                rootLibraries = listOf(main),
                runtimeIndex = emptyMap(),
                dependencyReader = { file ->
                    when (file.name) {
                        "libmain.so" -> setOf("libGLES_CM.so")
                        else -> emptySet()
                    }
                }
            )

            assertThat(result.libraries).isEmpty()
            assertThat(result.missingLibraries).isEmpty()
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun `resolvePackagedLibraries keeps only root lib and reachable dependencies`() {
        val tempDir = Files.createTempDirectory("apk-export-runtime-packaged-libs-test").toFile()
        val main = File(tempDir, "libmain.so").apply { writeText("main") }
        val linked = File(tempDir, "libfoo.so").apply { writeText("foo") }
        File(tempDir, "libSDL3.so").writeText("sdl")

        try {
            val result = ApkExportRuntimeLibrariesResolver.resolvePackagedLibraries(
                buildLibraries = listOf(main, linked, File(tempDir, "libSDL3.so")),
                runtimeCandidates = tempDir.listFiles()?.toList().orEmpty(),
                dependencyReader = { file ->
                    when (file.name) {
                        "libmain.so" -> setOf("libfoo.so")
                        else -> emptySet()
                    }
                }
            )

            assertThat(result.libraries.map { it.name })
                .containsExactly("libmain.so", "libfoo.so")
                .inOrder()
            assertThat(result.missingLibraries).isEmpty()
        } finally {
            tempDir.deleteRecursively()
        }
    }
}
