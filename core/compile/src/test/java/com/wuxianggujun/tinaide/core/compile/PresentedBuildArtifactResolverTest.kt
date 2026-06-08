package com.wuxianggujun.tinaide.core.compile

import com.google.common.truth.Truth.assertThat
import com.wuxianggujun.tinaide.core.compile.artifact.Artifact
import com.wuxianggujun.tinaide.core.compile.artifact.ArtifactId
import com.wuxianggujun.tinaide.core.compile.artifact.ArtifactKind
import com.wuxianggujun.tinaide.core.compile.artifact.BuildFingerprint
import com.wuxianggujun.tinaide.storage.ProjectDirStructure
import java.io.File
import java.nio.file.Files
import org.junit.Test

class PresentedBuildArtifactResolverTest {

    @Test
    fun `resolve exports default variant artifact into project artifacts root`() {
        val projectRoot = Files.createTempDirectory("presented-artifact-project").toFile()
        val sourceArtifact = createWorkspaceArtifact(projectRoot, "demo")
        val artifact = createArtifact(sourceArtifact, variant = "default", kind = ArtifactKind.EXECUTABLE)

        val result = PresentedBuildArtifactResolver.resolve(
            projectRoot = projectRoot,
            artifact = artifact,
            kind = CompileProjectUseCase.BuildArtifactKind.EXECUTABLE,
        )

        val expectedExport = File(ProjectDirStructure.getArtifactsDir(projectRoot.absolutePath), "demo")
        assertThat(result.path).isEqualTo(sourceArtifact.absolutePath)
        assertThat(result.exportedPath).isEqualTo(expectedExport.absolutePath)
        assertThat(result.kind).isEqualTo(CompileProjectUseCase.BuildArtifactKind.EXECUTABLE)
        assertThat(expectedExport.readText()).isEqualTo("artifact-demo")
    }

    @Test
    fun `resolve exports non default variant artifact under variant directory`() {
        val projectRoot = Files.createTempDirectory("presented-artifact-project").toFile()
        val sourceArtifact = createWorkspaceArtifact(projectRoot, "libdemo.so")
        val artifact = createArtifact(sourceArtifact, variant = "debug-ninja", kind = ArtifactKind.SHARED_LIBRARY)

        val result = PresentedBuildArtifactResolver.resolve(
            projectRoot = projectRoot,
            artifact = artifact,
            kind = CompileProjectUseCase.BuildArtifactKind.SHARED_LIBRARY,
        )

        val expectedExport = File(
            File(ProjectDirStructure.getArtifactsDir(projectRoot.absolutePath), "debug-ninja"),
            "libdemo.so",
        )
        assertThat(result.exportedPath).isEqualTo(expectedExport.absolutePath)
        assertThat(expectedExport.readText()).isEqualTo("artifact-libdemo.so")
    }

    @Test
    fun `resolve falls back to source path when export fails`() {
        val projectRoot = Files.createTempDirectory("presented-artifact-project").toFile()
        val missingArtifact = File(projectRoot.parentFile, "workspace-build/missing-demo")
        val artifact = createArtifact(missingArtifact, variant = "debug", kind = ArtifactKind.EXECUTABLE)

        val result = PresentedBuildArtifactResolver.resolve(
            projectRoot = projectRoot,
            artifact = artifact,
            kind = CompileProjectUseCase.BuildArtifactKind.EXECUTABLE,
        )

        assertThat(result.path).isEqualTo(missingArtifact.absolutePath)
        assertThat(result.exportedPath).isNull()
    }

    private fun createWorkspaceArtifact(projectRoot: File, fileName: String): File = File(projectRoot.parentFile, "workspace-build").apply { mkdirs() }
        .resolve(fileName)
        .apply { writeText("artifact-$fileName") }

    private fun createArtifact(file: File, variant: String, kind: ArtifactKind): Artifact = Artifact(
        id = ArtifactId(
            projectId = "project-id",
            targetName = file.nameWithoutExtension,
            variant = variant,
        ),
        absolutePath = file.absolutePath,
        kind = kind,
        contentHash = "deadbeef",
        fingerprint = testFingerprint(file.absolutePath),
        sources = emptyList(),
        compiledAt = 1L,
        buildTimeMs = 2L,
    )

    private fun testFingerprint(expectedOutputPath: String): BuildFingerprint = BuildFingerprint(
        compilerType = "clang",
        compilerPath = "/toolchains/clang",
        toolchainId = "builtin",
        sysrootApiLevel = 28,
        buildType = "debug",
        cmakeBuildType = null,
        cmakeGenerator = null,
        cFlags = "",
        cppFlags = "",
        ldFlags = "",
        ldLibs = "",
        cmakeExtraArgs = "",
        cppStandard = "c++17",
        optimizationLevel = "O0",
        generateDebugInfo = true,
        preferSharedLibraryForRun = false,
        parallelJobs = 1,
        resolvedRunMode = "native",
        artifactKind = "EXECUTABLE",
        expectedOutputPath = expectedOutputPath,
        trackedInputsHash = "tracked",
        extraEnvHash = null,
    )
}
