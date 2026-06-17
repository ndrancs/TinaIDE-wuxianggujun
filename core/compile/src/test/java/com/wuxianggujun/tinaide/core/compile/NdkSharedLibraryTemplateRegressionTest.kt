package com.wuxianggujun.tinaide.core.compile

import com.google.common.truth.Truth.assertThat
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipFile
import org.junit.Test

class NdkSharedLibraryTemplateRegressionTest {

    @Test
    fun `ndk shared library template provides runnable test target`() {
        val zipPath = locateRepoRoot()
            .resolve("app/src/main/assets/bundled_plugins/tinaide.project.templates/templates/ndk_shared_library.zip")

        val cmake = readZipEntry(zipPath, "CMakeLists.txt")
        val testMain = readZipEntry(zipPath, "test/main.cpp")

        assertThat(cmake).contains("add_library({{PROJECT_NAME}} SHARED")
        assertThat(cmake).contains("add_executable({{PROJECT_NAME}}_test")
        assertThat(cmake).contains("target_link_libraries({{PROJECT_NAME}}_test PRIVATE {{PROJECT_NAME}})")
        assertThat(cmake).contains("target_link_libraries({{PROJECT_NAME}}_test PRIVATE \${log-lib})")
        assertThat(testMain).contains("printf(\"Greeting: %s\\n\", greeting);")
        assertThat(testMain).contains("printf(\"All tests passed!\\n\");")
    }

    private fun readZipEntry(zipPath: Path, entryName: String): String = ZipFile(zipPath.toFile()).use { zip ->
        val entry = requireNotNull(zip.getEntry(entryName)) {
            "$entryName missing from ndk_shared_library.zip"
        }
        zip.getInputStream(entry).use { input ->
            String(input.readBytes(), StandardCharsets.UTF_8)
        }
    }

    private fun locateRepoRoot(): Path {
        var current = Path.of("").toAbsolutePath().normalize()
        while (true) {
            if (Files.exists(current.resolve("settings.gradle.kts"))) {
                return current
            }
            current = current.parent ?: error("Repository root with settings.gradle.kts was not found")
        }
    }
}
