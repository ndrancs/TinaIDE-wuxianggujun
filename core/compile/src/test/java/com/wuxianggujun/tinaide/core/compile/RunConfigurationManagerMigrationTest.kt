package com.wuxianggujun.tinaide.core.compile

import com.google.common.truth.Truth.assertThat
import com.wuxianggujun.tinaide.project.ProjectApkExportType
import com.wuxianggujun.tinaide.project.ProjectMetadataStore
import java.io.File
import java.nio.file.Files
import org.junit.Test

class RunConfigurationManagerMigrationTest {

    @Test
    fun `load migrates legacy file without schemaVersion and rewrites normalized cpp standard`() {
        val projectRoot = createTempProjectRoot()
        try {
            writeRunConfig(
                projectRoot,
                """
                {
                  "configurations": [
                    {
                      "id": "cfg-1",
                      "name": "Debug",
                      "singleFileCppStandard": "c++20"
                    }
                  ],
                  "selectedId": "cfg-1"
                }
                """.trimIndent()
            )

            val manager = RunConfigurationManager.load(projectRoot.absolutePath)

            assertThat(manager.schemaVersion).isEqualTo(3)
            assertThat(manager.selectedId).isEqualTo("cfg-1")
            assertThat(manager.selectedConfig.buildType).isEqualTo(BuildType.DEBUG)
            assertThat(manager.selectedConfig.singleFileCppStandard).isEqualTo("CPP_20")

            val persisted = readRunConfig(projectRoot)
            assertThat(persisted).contains("\"schemaVersion\": 3")
            assertThat(persisted).contains("\"buildType\": \"DEBUG\"")
            assertThat(persisted).contains("\"singleFileCppStandard\": \"CPP_20\"")
        } finally {
            projectRoot.deleteRecursively()
        }
    }

    @Test
    fun `load migrates schemaVersion 1 and keeps custom cpp standard while fixing selected id`() {
        val projectRoot = createTempProjectRoot()
        try {
            writeRunConfig(
                projectRoot,
                """
                {
                  "schemaVersion": 1,
                  "configurations": [
                    {
                      "id": "cfg-a",
                      "name": "Debug",
                      "singleFileCppStandard": "gnu++2b"
                    }
                  ],
                  "selectedId": "missing-id"
                }
                """.trimIndent()
            )

            val manager = RunConfigurationManager.load(projectRoot.absolutePath)

            assertThat(manager.schemaVersion).isEqualTo(3)
            assertThat(manager.selectedId).isEqualTo("cfg-a")
            assertThat(manager.selectedConfig.buildType).isEqualTo(BuildType.DEBUG)
            assertThat(manager.selectedConfig.singleFileCppStandard).isEqualTo("gnu++2b")

            val persisted = readRunConfig(projectRoot)
            assertThat(persisted).contains("\"schemaVersion\": 3")
            assertThat(persisted).contains("\"buildType\": \"DEBUG\"")
            assertThat(persisted).contains("\"selectedId\": \"cfg-a\"")
        } finally {
            projectRoot.deleteRecursively()
        }
    }

    @Test
    fun `load normalizes blank custom compiler paths in current schema`() {
        val projectRoot = createTempProjectRoot()
        try {
            writeRunConfig(
                projectRoot,
                """
                {
                  "schemaVersion": 3,
                  "configurations": [
                    {
                      "id": "cfg-blank",
                      "name": "Debug",
                      "customCCompiler": "",
                      "customCppCompiler": "   "
                    }
                  ],
                  "selectedId": "cfg-blank"
                }
                """.trimIndent()
            )

            val manager = RunConfigurationManager.load(projectRoot.absolutePath)

            assertThat(manager.schemaVersion).isEqualTo(3)
            assertThat(manager.selectedConfig.buildType).isEqualTo(BuildType.DEBUG)
            assertThat(manager.selectedConfig.customCCompiler).isNull()
            assertThat(manager.selectedConfig.customCppCompiler).isNull()

            val persisted = readRunConfig(projectRoot)
            assertThat(persisted).contains("\"buildType\": \"DEBUG\"")
            assertThat(persisted).contains("\"customCCompiler\": null")
            assertThat(persisted).contains("\"customCppCompiler\": null")
        } finally {
            projectRoot.deleteRecursively()
        }
    }

    @Test
    fun `load current schema defaults missing build type to debug`() {
        val projectRoot = createTempProjectRoot()
        try {
            writeRunConfig(
                projectRoot,
                """
                {
                  "schemaVersion": 3,
                  "configurations": [
                    {
                      "id": "cfg-build-type",
                      "name": "Debug"
                    }
                  ],
                  "selectedId": "cfg-build-type"
                }
                """.trimIndent()
            )

            val manager = RunConfigurationManager.load(projectRoot.absolutePath)

            assertThat(manager.selectedConfig.buildType).isEqualTo(BuildType.DEBUG)
        } finally {
            projectRoot.deleteRecursively()
        }
    }

    @Test
    fun `load defaults sdl3 project to sdl output when config file is missing`() {
        val projectRoot = createTempProjectRoot()
        try {
            ProjectMetadataStore.ensure(
                projectRoot = projectRoot,
                displayNameFallback = projectRoot.name,
                apkExportType = ProjectApkExportType.SDL3
            )

            val manager = RunConfigurationManager.load(projectRoot.absolutePath)

            assertThat(manager.selectedConfig.name).isEqualTo("Debug")
            assertThat(manager.selectedConfig.outputMode).isEqualTo(OutputMode.SDL)
        } finally {
            projectRoot.deleteRecursively()
        }
    }

    @Test
    fun `load migrates schemaVersion 2 gui output mode to sdl`() {
        val projectRoot = createTempProjectRoot()
        try {
            writeRunConfig(
                projectRoot,
                """
                {
                  "schemaVersion": 2,
                  "configurations": [
                    {
                      "id": "cfg-sdl",
                      "name": "SDL Debug",
                      "outputMode": "GUI"
                    }
                  ],
                  "selectedId": "cfg-sdl"
                }
                """.trimIndent()
            )

            val manager = RunConfigurationManager.load(projectRoot.absolutePath)

            assertThat(manager.schemaVersion).isEqualTo(3)
            assertThat(manager.selectedConfig.outputMode).isEqualTo(OutputMode.SDL)

            val persisted = readRunConfig(projectRoot)
            assertThat(persisted).contains("\"schemaVersion\": 3")
            assertThat(persisted).contains("\"outputMode\": \"SDL\"")
            assertThat(persisted).doesNotContain("\"outputMode\": \"GUI\"")
        } finally {
            projectRoot.deleteRecursively()
        }
    }

    private fun createTempProjectRoot(): File {
        return Files.createTempDirectory("run-config-migration-test").toFile()
    }

    private fun writeRunConfig(projectRoot: File, content: String) {
        val file = File(projectRoot, ".tinaide/run_configs.json")
        file.parentFile?.mkdirs()
        file.writeText(content)
    }

    private fun readRunConfig(projectRoot: File): String {
        return File(projectRoot, ".tinaide/run_configs.json").readText()
    }
}
