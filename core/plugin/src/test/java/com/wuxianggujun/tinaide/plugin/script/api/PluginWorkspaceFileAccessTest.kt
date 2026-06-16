package com.wuxianggujun.tinaide.plugin.script.api

import com.google.common.truth.Truth.assertThat
import java.io.File
import java.nio.file.Files
import org.junit.After
import org.junit.Before
import org.junit.Test

class PluginWorkspaceFileAccessTest {

    private lateinit var rootDir: File
    private lateinit var fileAccess: PluginWorkspaceFileAccess

    @Before
    fun setUp() {
        rootDir = Files.createTempDirectory("tina-plugin-workspace").toFile()
        fileAccess = PluginWorkspaceFileAccess { rootDir.absolutePath }
    }

    @After
    fun tearDown() {
        rootDir.deleteRecursively()
    }

    @Test
    fun resolveSafePath_shouldBlockEscapesAndAllowSafeDotNames() {
        val safeFile = File(rootDir, "docs/a..b.txt").apply {
            parentFile?.mkdirs()
            writeText("safe")
        }

        assertThat(fileAccess.resolveSafePath("docs/a..b.txt")?.canonicalFile)
            .isEqualTo(safeFile.canonicalFile)
        assertThat(fileAccess.resolveSafePath("../outside.txt")).isNull()
        assertThat(fileAccess.resolveSafePath("docs/../outside.txt")).isNull()
    }

    @Test
    fun findFiles_shouldSupportRecursiveGlobAndSkipHeavyDirectories() {
        File(rootDir, "src/Main.kt").apply {
            parentFile?.mkdirs()
            writeText("main")
        }
        File(rootDir, "src/Main.java").writeText("main")
        File(rootDir, "build/Generated.kt").apply {
            parentFile?.mkdirs()
            writeText("generated")
        }

        val files = fileAccess.findFiles(pattern = "*.kt", maxResults = 10)

        assertThat(files).containsExactly("src/Main.kt")
    }

    @Test
    fun findFiles_shouldCapRequestedResults() {
        File(rootDir, "a.txt").writeText("a")
        File(rootDir, "b.txt").writeText("b")
        File(rootDir, "c.txt").writeText("c")

        val files = fileAccess.findFiles(pattern = "*.txt", maxResults = 2)

        assertThat(files).hasSize(2)
        assertThat(files).containsExactly("a.txt", "b.txt")
    }

    @Test
    fun findFiles_shouldRejectUnsafePatterns() {
        File(rootDir, "safe.txt").writeText("safe")

        assertThat(fileAccess.findFiles(pattern = "../*.txt", maxResults = 10)).isEmpty()
    }
}
