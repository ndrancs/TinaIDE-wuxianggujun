package com.wuxianggujun.tinaide.core.compile

import com.google.common.truth.Truth.assertThat
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipFile
import org.junit.Test

class MakeTemplateRegressionTest {

    @Test
    fun `make executable template keeps project-root include flow`() {
        val zipPath = locateRepoRoot()
            .resolve("app/src/main/assets/bundled_plugins/tinaide.project.templates/templates/make_executable.zip")
        val makefile = ZipFile(zipPath.toFile()).use { zip ->
            val entry = requireNotNull(zip.getEntry("Makefile")) {
                "make_executable.zip 中缺少 Makefile"
            }
            zip.getInputStream(entry).use { input ->
                String(input.readBytes(), StandardCharsets.UTF_8)
            }
        }

        assertThat(makefile).contains("PROJECT_ROOT := \\$(abspath \\$(dir \\$(lastword \\$(MAKEFILE_LIST))))".replace("\\", ""))
        assertThat(makefile).contains("BUILD_TYPE ?= debug")
        assertThat(makefile).contains("BUILD_ROOT := \\$(PROJECT_ROOT)/build/\\$(BUILD_TYPE)".replace("\\", ""))
        assertThat(makefile).contains("CPPFLAGS += -I\\$(PROJECT_ROOT)/include".replace("\\", ""))
        assertThat(makefile).contains("BIN_DIR   := \\$(BUILD_ROOT)".replace("\\", ""))
        assertThat(makefile).contains("TARGET   := \\$(BIN_DIR)/{{PROJECT_NAME}}".replace("\\", ""))
        assertThat(makefile).contains("OBJ_DIR   := \\$(BUILD_ROOT)/obj".replace("\\", ""))
        assertThat(makefile).contains("\\$(CXX) \\$(CPPFLAGS) \\$(CXXFLAGS) -c \\$< -o \\$@".replace("\\", ""))
        assertThat(makefile).contains("\\$(CXX) \\$(LDFLAGS) -o \\$@ \\$^ \\$(LDLIBS)".replace("\\", ""))
    }

    private fun locateRepoRoot(): Path {
        var current = Path.of("").toAbsolutePath().normalize()
        while (true) {
            if (Files.exists(current.resolve("settings.gradle.kts"))) {
                return current
            }
            current = current.parent ?: error("未找到仓库根目录 settings.gradle.kts")
        }
    }
}
