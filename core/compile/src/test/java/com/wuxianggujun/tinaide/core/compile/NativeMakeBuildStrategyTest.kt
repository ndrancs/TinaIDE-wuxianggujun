package com.wuxianggujun.tinaide.core.compile

import com.google.common.truth.Truth.assertThat
import java.io.File
import java.nio.file.Files
import org.junit.Test

class NativeMakeBuildStrategyTest {

    @Test
    fun `buildRecipeShellAssignment points make recipes to android system shell`() {
        val assignment = NativeMakeBuildStrategy.buildRecipeShellAssignment()

        assertThat(assignment).isEqualTo("SHELL=/system/bin/sh")
        assertThat(assignment).isNotEqualTo("SHELL=/bin/sh")
    }

    @Test
    fun `make output locator finds elf in build dir without execute bit`() {
        val projectRoot = Files.createTempDirectory("make-output-root").toFile()
        val buildDir = File(projectRoot, "build").apply { mkdirs() }
        File(projectRoot, "Makefile").writeText("all:\n\t@true\n")
        val executable = File(buildDir, "demo").apply {
            writeBytes(byteArrayOf(0x7F, 'E'.code.toByte(), 'L'.code.toByte(), 'F'.code.toByte(), 0x02))
        }

        val resolved = MakeBuildOutputLocator.findExecutable(
            projectRoot = projectRoot,
            buildDir = buildDir,
            target = null,
            makefile = File(projectRoot, "Makefile")
        )

        assertThat(resolved).isEqualTo(executable.absolutePath)
    }

    @Test
    fun `make output locator falls back to project root executable for third party makefiles`() {
        val projectRoot = Files.createTempDirectory("make-output-root").toFile()
        val buildDir = File(projectRoot, "build").apply { mkdirs() }
        File(projectRoot, "Makefile").writeText("all:\n\t@true\n")
        File(projectRoot, "libdemo.so").writeBytes(
            byteArrayOf(0x7F, 'E'.code.toByte(), 'L'.code.toByte(), 'F'.code.toByte(), 0x02)
        )
        val executable = File(projectRoot, "demo").apply {
            writeBytes(byteArrayOf(0x7F, 'E'.code.toByte(), 'L'.code.toByte(), 'F'.code.toByte(), 0x02))
        }

        val resolved = MakeBuildOutputLocator.findExecutable(
            projectRoot = projectRoot,
            buildDir = buildDir,
            target = null,
            makefile = File(projectRoot, "Makefile")
        )

        assertThat(resolved).isEqualTo(executable.absolutePath)
    }
}
