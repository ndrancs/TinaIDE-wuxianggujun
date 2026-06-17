package com.wuxianggujun.tinaide.core.compile.launcher

import android.app.Application
import com.google.common.truth.Truth.assertThat
import com.wuxianggujun.tinaide.core.compile.BuildOptions
import com.wuxianggujun.tinaide.core.compile.BuildSystem
import com.wuxianggujun.tinaide.core.compile.artifact.Artifact
import com.wuxianggujun.tinaide.core.compile.artifact.ArtifactId
import com.wuxianggujun.tinaide.core.compile.artifact.ArtifactKind
import com.wuxianggujun.tinaide.core.compile.artifact.BuildFingerprint
import com.wuxianggujun.tinaide.core.compile.event.SharedFlowBuildEventEmitter
import com.wuxianggujun.tinaide.core.compile.strategy.BuildContext
import java.io.File
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(
    sdk = [34],
    manifest = Config.NONE,
    application = Application::class,
)
class TerminalLauncherTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var context: Application
    private lateinit var projectRoot: File
    private lateinit var buildDir: File
    private lateinit var buildContext: BuildContext
    private lateinit var emitter: SharedFlowBuildEventEmitter

    private val launcher = TerminalLauncher()

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        projectRoot = tempFolder.newFolder("project")
        buildDir = tempFolder.newFolder("build")
        buildContext = BuildContext(
            appContext = context,
            projectRoot = projectRoot,
            buildDir = buildDir,
            buildSystem = BuildSystem.SINGLE_FILE,
            options = BuildOptions(),
            projectId = "terminal-launcher-test",
        )
        emitter = SharedFlowBuildEventEmitter(replay = 0, extraBufferCapacity = 16)
    }

    @Test
    fun `terminal launch prepares executable artifacts`() = runTest {
        val artifact = newArtifact(fileName = "demo", kind = ArtifactKind.EXECUTABLE)

        val outcome = launcher.launch(artifact, buildContext, emitter)

        assertThat(outcome).isInstanceOf(LaunchOutcome.Prepared::class.java)
        val descriptor = (outcome as LaunchOutcome.Prepared).descriptor as LaunchDescriptor.Terminal
        assertThat(descriptor.runnablePath).isEqualTo(artifact.absolutePath)
        assertThat(descriptor.workingDir).isEqualTo(buildDir)
    }

    @Test
    fun `terminal launch rejects shared library artifacts`() = runTest {
        val artifact = newArtifact(fileName = "libdemo.so", kind = ArtifactKind.SHARED_LIBRARY)

        val outcome = launcher.launch(artifact, buildContext, emitter)

        assertThat(outcome).isInstanceOf(LaunchOutcome.Failed::class.java)
        val reason = (outcome as LaunchOutcome.Failed).reason
        assertThat(reason).contains(artifact.absolutePath)
        assertThat(reason).contains(ArtifactKind.SHARED_LIBRARY.name)
    }

    private fun newArtifact(fileName: String, kind: ArtifactKind): Artifact {
        val file = File(buildDir, fileName).apply {
            parentFile?.mkdirs()
            writeBytes(byteArrayOf(0x7f, 0x45, 0x4c, 0x46))
        }
        return Artifact(
            id = ArtifactId("terminal-launcher-test", file.nameWithoutExtension, "default"),
            absolutePath = file.absolutePath,
            kind = kind,
            contentHash = "hash-${kind.name}",
            fingerprint = fingerprintFor(file, kind),
            sources = emptyList(),
            compiledAt = 1L,
            buildTimeMs = 1L,
        )
    }

    private fun fingerprintFor(file: File, kind: ArtifactKind): BuildFingerprint = BuildFingerprint(
        compilerType = "CLANG",
        compilerPath = "clang",
        toolchainId = null,
        sysrootApiLevel = 35,
        buildType = "DEBUG",
        cmakeBuildType = null,
        cmakeGenerator = null,
        cFlags = "",
        cppFlags = "",
        ldFlags = "",
        ldLibs = "",
        cmakeExtraArgs = "",
        cppStandard = null,
        optimizationLevel = "O0",
        generateDebugInfo = true,
        preferSharedLibraryForRun = kind == ArtifactKind.SHARED_LIBRARY,
        parallelJobs = 1,
        resolvedRunMode = "NATIVE",
        artifactKind = kind.name,
        expectedOutputPath = file.absolutePath,
        trackedInputsHash = "inputs",
    )
}
