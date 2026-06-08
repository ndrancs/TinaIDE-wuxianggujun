package com.wuxianggujun.tinaide.core.compile

import com.google.common.truth.Truth.assertThat
import com.wuxianggujun.tinaide.storage.ProjectDirStructure
import java.io.File
import java.nio.file.Files
import org.junit.Test

class BuildArtifactExporterTest {

    @Test
    fun `export copies artifact into tinaide artifacts directory`() {
        val projectRoot = Files.createTempDirectory("artifact-project").toFile()
        val workspaceDir = File(projectRoot.parentFile, "workspace-build").apply { mkdirs() }
        val sourceArtifact = File(workspaceDir, "libimgui.a").apply {
            writeText("archive")
        }

        val result = BuildArtifactExporter.export(projectRoot, sourceArtifact.absolutePath).getOrThrow()
        val artifactsDir = ProjectDirStructure.getArtifactsDir(projectRoot.absolutePath)

        assertThat(result.sourceArtifact.absolutePath).isEqualTo(sourceArtifact.absolutePath)
        assertThat(result.exportedArtifact.absolutePath)
            .isEqualTo(File(artifactsDir, "libimgui.a").absolutePath)
        assertThat(result.exportedArtifact.readText()).isEqualTo("archive")
    }

    @Test
    fun `export writes artifact under build variant directory and overwrites previous copy`() {
        val projectRoot = Files.createTempDirectory("artifact-project").toFile()
        val workspaceDir = File(projectRoot.parentFile, "workspace-build").apply { mkdirs() }
        val sourceArtifact = File(workspaceDir, "demo").apply {
            writeText("new-binary")
        }
        val exportedArtifact = File(
            File(ProjectDirStructure.getArtifactsDir(projectRoot.absolutePath), "Debug").apply { mkdirs() },
            "demo"
        ).apply {
            writeText("old-binary")
        }

        val result = BuildArtifactExporter.export(
            projectRoot = projectRoot,
            artifactPath = sourceArtifact.absolutePath,
            variantName = "Debug"
        ).getOrThrow()

        assertThat(result.exportedArtifact.absolutePath).isEqualTo(exportedArtifact.absolutePath)
        assertThat(exportedArtifact.readText()).isEqualTo("new-binary")
    }
}
