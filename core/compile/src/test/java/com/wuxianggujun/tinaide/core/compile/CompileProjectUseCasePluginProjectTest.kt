package com.wuxianggujun.tinaide.core.compile

import android.app.Application
import com.google.common.truth.Truth.assertThat
import com.wuxianggujun.tinaide.core.compile.event.SharedFlowBuildEventEmitter
import com.wuxianggujun.tinaide.core.compile.pipeline.BuildContextFactory
import com.wuxianggujun.tinaide.core.compile.strategy.BuildStrategyRegistry
import com.wuxianggujun.tinaide.file.IProjectContext
import com.wuxianggujun.tinaide.file.Project
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
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(
    sdk = [34],
    manifest = Config.NONE,
    application = Application::class,
)
class CompileProjectUseCasePluginProjectTest {

    private lateinit var context: Application
    private lateinit var tempRoot: File
    private lateinit var projectRoot: File
    private lateinit var buildDir: File
    private lateinit var projectContext: IProjectContext
    private lateinit var pluginActions: RecordingPluginProjectActions
    private lateinit var useCase: CompileProjectUseCase

    @Before
    fun setUp() {
        context = mockk(relaxed = true)
        every { context.applicationContext } returns context
        every { context.getString(any<Int>()) } answers { "string-${firstArg<Int>()}" }
        every { context.getString(any<Int>(), *anyVararg()) } answers {
            "string-${firstArg<Int>()}-formatted"
        }
        tempRoot = Files.createTempDirectory("compile-plugin-project-").toFile()
        projectRoot = File(tempRoot, "demo-plugin").apply { mkdirs() }
        buildDir = File(projectRoot, "build")
        writePluginManifest(projectRoot)

        val project = Project(
            id = "demo-plugin",
            name = "Demo Plugin",
            rootPath = projectRoot.absolutePath,
            workspaceRootPath = projectRoot.absolutePath,
            files = emptyList(),
            buildDirPath = buildDir.absolutePath,
        )
        projectContext = mockk()
        every { projectContext.getCurrentProject() } returns project
        every { projectContext.currentProjectFlow } returns MutableStateFlow(project)

        pluginActions = RecordingPluginProjectActions()
        useCase = CompileProjectUseCase(
            appContext = context,
            projectContext = projectContext,
            outputManager = mockk(relaxed = true),
            orchestratorProvider = { error("Plugin projects must not enter normal build orchestrator") },
            strategyRegistry = BuildStrategyRegistry(emptyList()),
            buildContextFactory = BuildContextFactory(),
            terminalCommandBuilder = TerminalCommandBuilder(context),
            eventBus = SharedFlowBuildEventEmitter(),
            pluginProjectActions = pluginActions,
        )
    }

    @After
    fun tearDown() {
        tempRoot.deleteRecursively()
    }

    @Test
    fun `build should dispatch to plugin package action`() = runTest {
        val result = useCase.execute(
            operation = CompileProjectUseCase.Operation.forBuild(),
            onProgress = {},
        )

        assertThat(pluginActions.buildCalls).isEqualTo(1)
        assertThat(pluginActions.installCalls).isEqualTo(0)
        val report = (result as CompileProjectUseCase.Result.Success).report
        assertThat(report.artifact?.kind).isEqualTo(CompileProjectUseCase.BuildArtifactKind.PLUGIN_PACKAGE)
        assertThat(report.launch).isEqualTo(CompileProjectUseCase.LaunchSpec.None)
    }

    @Test
    fun `run should dispatch to plugin hot install action`() = runTest {
        val result = useCase.execute(
            operation = CompileProjectUseCase.Operation.rebuildRun(),
            onProgress = {},
        )

        assertThat(pluginActions.buildCalls).isEqualTo(0)
        assertThat(pluginActions.installCalls).isEqualTo(1)
        val report = (result as CompileProjectUseCase.Result.Success).report
        assertThat(report.artifact?.kind).isEqualTo(CompileProjectUseCase.BuildArtifactKind.PLUGIN_PACKAGE)
        assertThat(report.launch).isEqualTo(
            CompileProjectUseCase.LaunchSpec.PluginInstalled(
                pluginId = "demo.plugin",
                pluginName = "Demo Plugin",
                pluginVersion = "1.0.0",
                packagePath = File(projectRoot, "dist/demo.plugin-1.0.0.tinaplug").absolutePath,
            )
        )
    }

    private fun writePluginManifest(projectRoot: File) {
        File(projectRoot, "manifest.json").writeText(
            """
            {
              "id": "demo.plugin",
              "name": "Demo Plugin",
              "version": "1.0.0",
              "type": "config"
            }
            """.trimIndent(),
            Charsets.UTF_8,
        )
    }

    private inner class RecordingPluginProjectActions : PluginProjectActions {
        var buildCalls = 0
        var installCalls = 0

        override suspend fun build(projectRoot: File, buildDir: File): kotlin.Result<PluginProjectActionResult> {
            buildCalls += 1
            return kotlin.Result.success(actionResult(installed = false))
        }

        override suspend fun install(projectRoot: File, buildDir: File): kotlin.Result<PluginProjectActionResult> {
            installCalls += 1
            return kotlin.Result.success(actionResult(installed = true))
        }

        private fun actionResult(installed: Boolean): PluginProjectActionResult {
            val packageFile = File(projectRoot, "dist/demo.plugin-1.0.0.tinaplug").apply {
                parentFile?.mkdirs()
                writeText("plugin package", Charsets.UTF_8)
            }
            return PluginProjectActionResult(
                packageFile = packageFile,
                pluginId = "demo.plugin",
                pluginName = "Demo Plugin",
                pluginVersion = "1.0.0",
                installed = installed,
            )
        }
    }
}
