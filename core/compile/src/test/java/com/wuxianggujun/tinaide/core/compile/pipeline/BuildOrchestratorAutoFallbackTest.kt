package com.wuxianggujun.tinaide.core.compile.pipeline

import com.google.common.truth.Truth.assertThat
import com.wuxianggujun.tinaide.core.compile.BuildOptions
import com.wuxianggujun.tinaide.core.compile.BuildSystem
import com.wuxianggujun.tinaide.core.compile.OutputMode
import com.wuxianggujun.tinaide.core.compile.action.BuildIntent
import com.wuxianggujun.tinaide.core.compile.action.CompileRequest
import com.wuxianggujun.tinaide.core.compile.action.LaunchIntent
import com.wuxianggujun.tinaide.core.compile.artifact.Artifact
import com.wuxianggujun.tinaide.core.compile.artifact.ArtifactId
import com.wuxianggujun.tinaide.core.compile.artifact.ArtifactKind
import com.wuxianggujun.tinaide.core.compile.artifact.ArtifactSpec
import com.wuxianggujun.tinaide.core.compile.artifact.ArtifactStore
import com.wuxianggujun.tinaide.core.compile.artifact.BuildFingerprint
import com.wuxianggujun.tinaide.core.compile.event.BuildEvent
import com.wuxianggujun.tinaide.core.compile.event.BuildReport
import com.wuxianggujun.tinaide.core.compile.event.SharedFlowBuildEventEmitter
import com.wuxianggujun.tinaide.core.compile.launcher.LaunchDescriptor
import com.wuxianggujun.tinaide.core.compile.strategy.BuildContext
import com.wuxianggujun.tinaide.core.compile.strategy.BuildStrategy
import com.wuxianggujun.tinaide.core.compile.strategy.ExecutionOutcome
import io.mockk.coEvery
import io.mockk.mockk
import java.io.File
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Auto-Fallback 场景测试(设计文档 §4.6)。
 *
 * 验证:
 * - 缓存产物启动失败 + IfNeeded + 非 None launch + artifactWasCached=true → 触发 fallback
 * - fallback 重试强制使用 Force,不会再次 fallback(死循环防护)
 * - BuildEvent.AutoFallback 事件发射
 * - 不满足条件时不 fallback(Force 路径失败、None launch、Build 阶段失败)
 */
@OptIn(ExperimentalCoroutinesApi::class)
class BuildOrchestratorAutoFallbackTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var ctx: BuildContext
    private lateinit var planner: BuildPlanner
    private lateinit var executor: BuildExecutor
    private lateinit var dispatcher: LaunchDispatcher
    private lateinit var store: ArtifactStore
    private lateinit var events: SharedFlowBuildEventEmitter

    @Before
    fun setUp() {
        val projectRoot = tempFolder.newFolder("project")
        val buildDir = tempFolder.newFolder("build")
        ctx = BuildContext(
            appContext = mockk(relaxed = true),
            projectRoot = projectRoot,
            buildDir = buildDir,
            buildSystem = BuildSystem.SINGLE_FILE,
            options = BuildOptions(),
            projectId = "test-project",
        )
        planner = mockk()
        executor = mockk()
        dispatcher = mockk()
        store = mockk(relaxed = true)
        events = SharedFlowBuildEventEmitter(replay = 0, extraBufferCapacity = 256)
    }

    @Test
    fun `fallback triggers when cached artifact fails to launch`() = runTest(UnconfinedTestDispatcher()) {
        val cached = newArtifact()
        val rebuilt = newArtifact(hash = "cafebabe")

        val strategy: BuildStrategy = mockk()
        val spec = newSpec()
        val fp = cached.fingerprint

        // First runOnce: Skip (cache hit) → dispatch fails with wasCached=true
        // Second runOnce (fallback): Build → execute success → dispatch succeeds with wasCached=false
        coEvery { planner.plan(any(), any()) } returnsMany listOf(
            BuildPlan.Skip(cached, "cache hit"),
            BuildPlan.Build(strategy, spec, fp, "force rebuild requested"),
        )
        coEvery { executor.execute(any(), any(), any()) } returns ExecutionOutcome.Success(rebuilt)
        coEvery { dispatcher.dispatch(any(), cached, true, any(), any()) } returns
            BuildReport.LaunchFailed("artifact corrupted", cached, artifactWasCached = true)
        coEvery { dispatcher.dispatch(any(), rebuilt, false, any(), any()) } returns
            BuildReport.Success(
                rebuilt,
                LaunchDescriptor.Terminal(
                    artifact = rebuilt,
                    runnablePath = rebuilt.absolutePath,
                    workingDir = File(rebuilt.absolutePath).parentFile ?: ctx.buildDir,
                ),
                "launched freshly built artifact"
            )

        val captured = captureEvents()
        val orchestrator = newOrchestrator()

        val report = orchestrator.run(
            CompileRequest(BuildIntent.IfNeeded, LaunchIntent.Run(OutputMode.TERMINAL)),
            ctx,
        )

        assertThat(report).isInstanceOf(BuildReport.Success::class.java)
        assertThat((report as BuildReport.Success).artifact.contentHash).isEqualTo("cafebabe")
        // AutoFallback 事件必发
        assertThat(captured.any { it is BuildEvent.AutoFallback }).isTrue()
    }

    @Test
    fun `force intent does not fallback on launch failure`() = runTest(UnconfinedTestDispatcher()) {
        val builtArtifact = newArtifact()
        val strategy: BuildStrategy = mockk()
        val spec = newSpec()

        coEvery { planner.plan(any(), any()) } returns BuildPlan.Build(strategy, spec, builtArtifact.fingerprint, "force")
        coEvery { executor.execute(any(), any(), any()) } returns ExecutionOutcome.Success(builtArtifact)
        coEvery { dispatcher.dispatch(any(), builtArtifact, false, any(), any()) } returns
            BuildReport.LaunchFailed("some runtime error", builtArtifact, artifactWasCached = false)

        val captured = captureEvents()
        val orchestrator = newOrchestrator()

        val report = orchestrator.run(
            CompileRequest(BuildIntent.Force, LaunchIntent.Run(OutputMode.TERMINAL)),
            ctx,
        )

        assertThat(report).isInstanceOf(BuildReport.LaunchFailed::class.java)
        assertThat(captured.none { it is BuildEvent.AutoFallback }).isTrue()
    }

    @Test
    fun `fallbackOnLaunchFailure=false disables fallback`() = runTest(UnconfinedTestDispatcher()) {
        val cached = newArtifact()
        coEvery { planner.plan(any(), any()) } returns BuildPlan.Skip(cached, "cache hit")
        coEvery { dispatcher.dispatch(any(), cached, true, any(), any()) } returns
            BuildReport.LaunchFailed("fail", cached, artifactWasCached = true)

        val captured = captureEvents()
        val orchestrator = newOrchestrator()

        val report = orchestrator.run(
            CompileRequest(
                build = BuildIntent.IfNeeded,
                launch = LaunchIntent.Run(OutputMode.TERMINAL),
                fallbackOnLaunchFailure = false,
            ),
            ctx,
        )

        assertThat(report).isInstanceOf(BuildReport.LaunchFailed::class.java)
        assertThat(captured.none { it is BuildEvent.AutoFallback }).isTrue()
    }

    @Test
    fun `build failure does not trigger fallback`() = runTest(UnconfinedTestDispatcher()) {
        val strategy: BuildStrategy = mockk()
        val spec = newSpec()
        val fp = newArtifact().fingerprint

        coEvery { planner.plan(any(), any()) } returns BuildPlan.Build(strategy, spec, fp, "no cached artifact")
        coEvery { executor.execute(any(), any(), any()) } returns
            ExecutionOutcome.Failure("compile error", emptyList())

        val captured = captureEvents()
        val orchestrator = newOrchestrator()

        val report = orchestrator.run(
            CompileRequest(BuildIntent.IfNeeded, LaunchIntent.Run(OutputMode.TERMINAL)),
            ctx,
        )

        assertThat(report).isInstanceOf(BuildReport.BuildFailed::class.java)
        assertThat(captured.none { it is BuildEvent.AutoFallback }).isTrue()
    }

    @Test
    fun `fallback is capped at one retry`() = runTest(UnconfinedTestDispatcher()) {
        val cached = newArtifact()
        val rebuilt = newArtifact(hash = "second")
        val strategy: BuildStrategy = mockk()
        val spec = newSpec()

        coEvery { planner.plan(any(), any()) } returnsMany listOf(
            BuildPlan.Skip(cached, "cache hit"),
            BuildPlan.Build(strategy, spec, rebuilt.fingerprint, "fallback force"),
        )
        coEvery { executor.execute(any(), any(), any()) } returns ExecutionOutcome.Success(rebuilt)
        // 第二次 launch 也失败,但 fallbackOnLaunchFailure 已被强制关掉,不应再 fallback
        coEvery { dispatcher.dispatch(any(), cached, true, any(), any()) } returns
            BuildReport.LaunchFailed("first fail", cached, artifactWasCached = true)
        coEvery { dispatcher.dispatch(any(), rebuilt, false, any(), any()) } returns
            BuildReport.LaunchFailed("second fail", rebuilt, artifactWasCached = false)

        val orchestrator = newOrchestrator()
        val report = orchestrator.run(
            CompileRequest(BuildIntent.IfNeeded, LaunchIntent.Run(OutputMode.TERMINAL)),
            ctx,
        )

        assertThat(report).isInstanceOf(BuildReport.LaunchFailed::class.java)
        assertThat((report as BuildReport.LaunchFailed).artifact.contentHash).isEqualTo("second")
    }

    // ---------- helpers ----------

    private fun newOrchestrator(): BuildOrchestrator = BuildOrchestrator(
        validator = EnvironmentValidator(),
        planner = planner,
        executor = executor,
        dispatcher = dispatcher,
        artifactStore = store,
        events = events,
    )

    private fun captureEvents(): MutableList<BuildEvent> {
        val captured = mutableListOf<BuildEvent>()
        TestScope(UnconfinedTestDispatcher()).launch {
            events.events.collect { captured += it }
        }
        return captured
    }

    private fun newSpec(): ArtifactSpec = ArtifactSpec(
        id = ArtifactId("test-project", "hello", "default"),
        expectedPath = File(ctx.buildDir, "hello"),
        kind = ArtifactKind.EXECUTABLE,
        sources = listOf(File(ctx.projectRoot, "main.c")),
    )

    private fun newArtifact(hash: String = "deadbeef"): Artifact {
        val file = File(ctx.buildDir, "hello").apply { writeBytes(byteArrayOf(0x7f, 0x45, 0x4c, 0x46)) }
        return Artifact(
            id = ArtifactId("test-project", "hello", "default"),
            absolutePath = file.absolutePath,
            kind = ArtifactKind.EXECUTABLE,
            contentHash = hash,
            fingerprint = BuildFingerprint(
                compilerType = "CLANG",
                compilerPath = "clang:default",
                toolchainId = null,
                sysrootApiLevel = 28,
                buildType = "DEBUG",
                cmakeBuildType = "Debug",
                cmakeGenerator = "Ninja",
                cFlags = "",
                cppFlags = "",
                ldFlags = "",
                ldLibs = "",
                cmakeExtraArgs = "",
                cppStandard = null,
                optimizationLevel = "O2",
                generateDebugInfo = true,
                preferSharedLibraryForRun = false,
                parallelJobs = 4,
                resolvedRunMode = "NATIVE",
                artifactKind = "EXECUTABLE",
                expectedOutputPath = "hello",
                trackedInputsHash = "tracked-hello",
                extraEnvHash = null,
            ),
            sources = emptyList(),
            compiledAt = System.currentTimeMillis(),
            buildTimeMs = 100L,
        )
    }
}
