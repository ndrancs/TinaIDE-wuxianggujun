package com.wuxianggujun.tinaide.core.compile

import com.google.common.truth.Truth.assertThat
import com.wuxianggujun.tinaide.project.ProjectApkExportType
import com.wuxianggujun.tinaide.project.ProjectMetadataStore
import java.io.File
import java.nio.file.Files
import org.junit.Test

class ProjectRunConfigBootstrapperTest {

    @Test
    fun `initializeIfMissing writes explicit sdl config for sdl3 project`() {
        val projectRoot = createTempProjectRoot()
        try {
            ProjectMetadataStore.ensure(
                projectRoot = projectRoot,
                displayNameFallback = projectRoot.name,
                apkExportType = ProjectApkExportType.SDL3
            )

            val initialized = ProjectRunConfigBootstrapper.initializeIfMissing(projectRoot)

            assertThat(initialized).isTrue()
            assertThat(runConfigFile(projectRoot).exists()).isTrue()

            val manager = RunConfigurationManager.load(projectRoot.absolutePath)
            assertThat(manager.selectedConfig.name).isEqualTo("Debug")
            assertThat(manager.selectedConfig.outputMode).isEqualTo(OutputMode.SDL)
            assertThat(runConfigFile(projectRoot).readText()).contains("\"outputMode\": \"SDL\"")
        } finally {
            projectRoot.deleteRecursively()
        }
    }

    @Test
    fun `initializeIfMissing does not overwrite existing config`() {
        val projectRoot = createTempProjectRoot()
        try {
            ProjectMetadataStore.ensure(
                projectRoot = projectRoot,
                displayNameFallback = projectRoot.name,
                apkExportType = ProjectApkExportType.SDL3
            )
            val existingManager = RunConfigurationManager(
                configurations = listOf(RunConfiguration(id = "cfg-existing", outputMode = OutputMode.TERMINAL)),
                selectedId = "cfg-existing"
            )
            assertThat(RunConfigurationManager.save(projectRoot.absolutePath, existingManager)).isTrue()

            val initialized = ProjectRunConfigBootstrapper.initializeIfMissing(projectRoot)

            assertThat(initialized).isFalse()
            val manager = RunConfigurationManager.load(projectRoot.absolutePath)
            assertThat(manager.selectedId).isEqualTo("cfg-existing")
            assertThat(manager.selectedConfig.outputMode).isEqualTo(OutputMode.TERMINAL)
        } finally {
            projectRoot.deleteRecursively()
        }
    }

    @Test
    fun `initializeIfMissing skips non sdl3 project`() {
        val projectRoot = createTempProjectRoot()
        try {
            ProjectMetadataStore.ensure(
                projectRoot = projectRoot,
                displayNameFallback = projectRoot.name,
                apkExportType = ProjectApkExportType.DISABLED
            )

            val initialized = ProjectRunConfigBootstrapper.initializeIfMissing(projectRoot)

            assertThat(initialized).isFalse()
            assertThat(runConfigFile(projectRoot).exists()).isFalse()
        } finally {
            projectRoot.deleteRecursively()
        }
    }

    private fun createTempProjectRoot(): File = Files.createTempDirectory("project-run-config-bootstrapper-test").toFile()

    private fun runConfigFile(projectRoot: File): File = File(projectRoot, ".tinaide/run_configs.json")
}
