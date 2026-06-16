package com.wuxianggujun.tinaide.core.compile

import com.google.common.truth.Truth.assertThat
import com.wuxianggujun.tinaide.project.ProjectMetadata
import com.wuxianggujun.tinaide.project.ProjectMetadataStore
import java.io.File
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ProjectSysrootApiLevelResolverTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun `run config value takes precedence over metadata`() {
        val projectRoot = tempFolder.newFolder("run-config-priority")
        writeMetadata(projectRoot, nativeApiLevel = 33)

        val resolution = ProjectSysrootApiLevelResolver.resolve(projectRoot, runConfigApiLevel = 29)

        assertThat(resolution.apiLevel).isEqualTo(29)
        assertThat(resolution.source).isEqualTo(ProjectSysrootApiLevelResolver.Source.RUN_CONFIG)
        assertThat(resolution.invalidRunConfigApiLevel).isNull()
    }

    @Test
    fun `metadata nativeApiLevel is used when run config is empty`() {
        val projectRoot = tempFolder.newFolder("metadata-native")
        writeMetadata(projectRoot, nativeApiLevel = 31)

        val resolution = ProjectSysrootApiLevelResolver.resolve(projectRoot, runConfigApiLevel = null)

        assertThat(resolution.apiLevel).isEqualTo(31)
        assertThat(resolution.source).isEqualTo(ProjectSysrootApiLevelResolver.Source.METADATA)
        assertThat(resolution.invalidRunConfigApiLevel).isNull()
    }

    @Test
    fun `invalid run config falls back to metadata`() {
        val projectRoot = tempFolder.newFolder("invalid-run-config")
        writeMetadata(projectRoot, nativeApiLevel = 34)

        val resolution = ProjectSysrootApiLevelResolver.resolve(projectRoot, runConfigApiLevel = 99)

        assertThat(resolution.apiLevel).isEqualTo(34)
        assertThat(resolution.source).isEqualTo(ProjectSysrootApiLevelResolver.Source.METADATA)
        assertThat(resolution.invalidRunConfigApiLevel).isEqualTo(99)
    }

    @Test
    fun `defaults to API 28 when no valid config exists`() {
        val projectRoot = tempFolder.newFolder("default-fallback")
        writeMetadata(projectRoot, nativeApiLevel = 99)

        val resolution = ProjectSysrootApiLevelResolver.resolve(projectRoot, runConfigApiLevel = 20)

        assertThat(resolution.apiLevel).isEqualTo(MakeCommandOverrides.DEFAULT_SYSROOT_API_LEVEL)
        assertThat(resolution.source).isEqualTo(ProjectSysrootApiLevelResolver.Source.DEFAULT)
        assertThat(resolution.invalidRunConfigApiLevel).isEqualTo(20)
    }

    private fun writeMetadata(
        projectRoot: File,
        nativeApiLevel: Int?
    ) {
        val metadata = ProjectMetadata(
            id = "test-${projectRoot.name}",
            displayName = projectRoot.name,
            createdAt = System.currentTimeMillis(),
            nativeApiLevel = nativeApiLevel
        )
        check(ProjectMetadataStore.write(projectRoot, metadata)) {
            "Failed to write metadata for ${projectRoot.absolutePath}"
        }
    }
}
