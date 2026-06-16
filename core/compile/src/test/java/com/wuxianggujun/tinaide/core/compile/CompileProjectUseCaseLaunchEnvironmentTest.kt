package com.wuxianggujun.tinaide.core.compile

import android.app.Application
import com.google.common.truth.Truth.assertThat
import com.wuxianggujun.tinaide.core.compile.artifact.Artifact
import com.wuxianggujun.tinaide.core.compile.artifact.ArtifactId
import com.wuxianggujun.tinaide.core.compile.artifact.ArtifactKind
import com.wuxianggujun.tinaide.core.compile.artifact.ArtifactStore
import com.wuxianggujun.tinaide.core.compile.artifact.BuildFingerprint
import com.wuxianggujun.tinaide.core.compile.event.BuildReport
import com.wuxianggujun.tinaide.core.compile.event.SharedFlowBuildEventEmitter
import com.wuxianggujun.tinaide.core.compile.launcher.LaunchDescriptor
import com.wuxianggujun.tinaide.core.compile.pipeline.BuildContextFactory
import com.wuxianggujun.tinaide.core.compile.pipeline.BuildExecutor
import com.wuxianggujun.tinaide.core.compile.pipeline.BuildOrchestrator
import com.wuxianggujun.tinaide.core.compile.pipeline.BuildPlan
import com.wuxianggujun.tinaide.core.compile.pipeline.BuildPlanner
import com.wuxianggujun.tinaide.core.compile.pipeline.EnvironmentValidator
import com.wuxianggujun.tinaide.core.compile.pipeline.LaunchDispatcher
import com.wuxianggujun.tinaide.core.compile.strategy.BuildStrategy
import com.wuxianggujun.tinaide.core.compile.strategy.BuildStrategyRegistry
import com.wuxianggujun.tinaide.core.packages.store.LocalInstallStateStore
import com.wuxianggujun.tinaide.file.IProjectContext
import com.wuxianggujun.tinaide.file.Project
import com.wuxianggujun.tinaide.output.IOutputManager
import com.wuxianggujun.tinaide.project.ProjectBuildSystem
import com.wuxianggujun.tinaide.project.ProjectMetadataStore
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
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
class CompileProjectUseCaseLaunchEnvironmentTest {

    private lateinit var context: Application
    private lateinit var tempRoot: File
    private lateinit var projectRoot: File
    private lateinit var buildDir: File
    private lateinit var runtimeDir: File

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        File(context.filesDir, "installed-packages").deleteRecursively()
        LocalInstallStateStore(context).clear()

        tempRoot = Files.createTempDirectory("compile-launch-env-").toFile()
        projectRoot = File(tempRoot, "project").apply { mkdirs() }
        buildDir = File(projectRoot, "build")
        runtimeDir = File(projectRoot, "runtime-libs").apply { mkdirs() }
        File(projectRoot, "main.cpp").writeText("int main() { return 0; }\n")
        ProjectMetadataStore.ensure(
            projectRoot = projectRoot,
            displayNameFallback = "Launch Env",
            buildSystem = ProjectBuildSystem.SINGLE_FILE,
        )
        ProjectMetadataStore.updateNativeDependencyPaths(
            projectRoot = projectRoot,
            includeDirs = emptyList(),
            libraryDirs = emptyList(),
            runtimeDirs = listOf("runtime-libs"),
        )
    }

    @After
    fun tearDown() {
        tempRoot.deleteRecursively()
        File(context.filesDir, "installed-packages").deleteRecursively()
        LocalInstallStateStore(context).clear()
    }

    @Test
    fun `debug launch environment includes project runtime library dirs`() = runTest {
        val artifact = newArtifact(File(buildDir, "demo"))
        val planner = mockk<BuildPlanner>()
        val dispatcher = mockk<LaunchDispatcher>()
        coEvery { planner.plan(any(), any()) } returns BuildPlan.Skip(artifact, "cached for launch environment test")
        coEvery { dispatcher.dispatch(any(), artifact, true, any(), any()) } returns BuildReport.Success(
            artifact = artifact,
            descriptor = LaunchDescriptor.Debug(
                artifact = artifact,
                programPath = artifact.absolutePath,
                workingDir = buildDir.absolutePath,
            ),
            summary = "debug ready",
        )

        val useCase = CompileProjectUseCase(
            appContext = context,
            projectContext = projectContext(),
            outputManager = mockk<IOutputManager>(relaxed = true),
            orchestratorProvider = {
                BuildOrchestrator(
                    validator = EnvironmentValidator(),
                    planner = planner,
                    executor = mockk<BuildExecutor>(relaxed = true),
                    dispatcher = dispatcher,
                    artifactStore = mockk<ArtifactStore>(relaxed = true),
                    events = SharedFlowBuildEventEmitter(),
                )
            },
            strategyRegistry = singleFileStrategyRegistry(),
            buildContextFactory = BuildContextFactory(),
            terminalCommandBuilder = TerminalCommandBuilder(context),
            eventBus = SharedFlowBuildEventEmitter(),
        )

        val result = useCase.execute(
            operation = CompileProjectUseCase.Operation.forDebug(),
            onProgress = {},
            launchEnvironment = mapOf("LD_LIBRARY_PATH" to "/manual/lib"),
        )

        val launch = (result as CompileProjectUseCase.Result.Success).report.launch
            as CompileProjectUseCase.LaunchSpec.Debug
        val ldLibraryPath = launch.environment["LD_LIBRARY_PATH"].orEmpty()
        val runtimePath = runtimeDir.canonicalFile.absolutePath
        assertThat(ldLibraryPath).contains(runtimePath)
        assertThat(ldLibraryPath).contains("/manual/lib")
        assertThat(ldLibraryPath.indexOf(runtimePath)).isLessThan(ldLibraryPath.indexOf("/manual/lib"))
    }

    private fun projectContext(): IProjectContext {
        val project = Project(
            id = "launch-env",
            name = "Launch Env",
            rootPath = projectRoot.absolutePath,
            workspaceRootPath = projectRoot.absolutePath,
            files = emptyList(),
            buildDirPath = buildDir.absolutePath,
        )
        return mockk {
            every { getCurrentProject() } returns project
            every { currentProjectFlow } returns MutableStateFlow(project)
        }
    }

    private fun singleFileStrategyRegistry(): BuildStrategyRegistry {
        val strategy = mockk<BuildStrategy>()
        every { strategy.buildSystem } returns BuildSystem.SINGLE_FILE
        return BuildStrategyRegistry(listOf(strategy))
    }

    private fun newArtifact(file: File): Artifact {
        file.parentFile?.mkdirs()
        file.writeText("artifact")
        return Artifact(
            id = ArtifactId(projectId = "launch-env", targetName = file.nameWithoutExtension),
            absolutePath = file.absolutePath,
            kind = ArtifactKind.EXECUTABLE,
            contentHash = "hash",
            fingerprint = BuildFingerprint(
                compilerType = "clang",
                compilerPath = "clang",
                toolchainId = null,
                sysrootApiLevel = 28,
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
                preferSharedLibraryForRun = false,
                parallelJobs = 1,
                resolvedRunMode = "NATIVE",
                artifactKind = ArtifactKind.EXECUTABLE.name,
                expectedOutputPath = file.absolutePath,
                trackedInputsHash = "inputs",
            ),
            sources = emptyList(),
            compiledAt = 1L,
            buildTimeMs = 1L,
        )
    }
}
