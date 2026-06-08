package com.wuxianggujun.tinaide.ai.integration

import android.app.Application
import com.google.common.truth.Truth.assertThat
import com.wuxianggujun.tinaide.ai.tools.executor.execution.BuildRequest
import com.wuxianggujun.tinaide.ai.tools.executor.execution.ExecutionResult
import com.wuxianggujun.tinaide.ai.tools.executor.execution.ExecutionStatus
import com.wuxianggujun.tinaide.ai.tools.executor.execution.RunRequest
import com.wuxianggujun.tinaide.ai.tools.executor.execution.TestRequest
import com.wuxianggujun.tinaide.core.compile.CompileProjectUseCase
import com.wuxianggujun.tinaide.core.compile.ProcessManager
import com.wuxianggujun.tinaide.core.compile.RunConfigurationManager
import com.wuxianggujun.tinaide.core.i18n.AppStrings
import com.wuxianggujun.tinaide.editor.IEditorManager
import com.wuxianggujun.tinaide.editor.session.SaveReason
import com.wuxianggujun.tinaide.editor.session.SaveResult
import com.wuxianggujun.tinaide.output.IOutputManager
import com.wuxianggujun.tinaide.ui.BottomPanelController
import com.wuxianggujun.tinaide.ui.BottomPanelViewModel
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestWatcher
import org.junit.runner.Description
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(
    sdk = [34],
    manifest = Config.NONE,
    application = Application::class
)
@OptIn(ExperimentalCoroutinesApi::class)
class ExecutionCallbacksImplTest {

    @get:Rule
    val mainDispatcherRule = ExecutionCallbacksMainDispatcherRule()

    @Before
    fun setUp() {
        resetAppStrings()
        AppStrings.initialize(RuntimeEnvironment.getApplication())
    }

    @After
    fun tearDown() {
        resetAppStrings()
    }

    @Test
    fun `stopExecution returns false for unknown execution id without stopping process`() {
        val processManager = mockk<ProcessManager>(relaxed = true)
        val callbacks = newCallbacks(processManager)

        val stopped = callbacks.stopExecution("missing-execution")

        assertThat(stopped).isFalse()
        verify(exactly = 0) { processManager.stopCurrentProcess() }
    }

    @Test
    fun `stopExecution returns false for terminal execution without stopping process`() {
        val processManager = mockk<ProcessManager>(relaxed = true)
        val callbacks = newCallbacks(processManager)
        callbacks.putExecutionState("failed-execution", ExecutionStatus.FAILED)

        val stopped = callbacks.stopExecution("failed-execution")

        assertThat(stopped).isFalse()
        assertThat(callbacks.getExecutionStatus("failed-execution")).isEqualTo(ExecutionStatus.FAILED)
        verify(exactly = 0) { processManager.stopCurrentProcess() }
    }

    @Test
    fun `stopExecution cancels running and pending executions through process manager`() {
        val processManager = mockk<ProcessManager>()
        every { processManager.stopCurrentProcess() } returns true
        val callbacks = newCallbacks(processManager)
        callbacks.putExecutionState("running-execution", ExecutionStatus.RUNNING)
        callbacks.setActiveProcessExecutionId("running-execution")
        callbacks.putExecutionResult(
            ExecutionResult(
                executionId = "running-execution",
                success = true,
                exitCode = 0,
                output = "started",
                errorOutput = "",
                duration = 0,
                status = ExecutionStatus.RUNNING
            )
        )
        callbacks.putExecutionState("pending-execution", ExecutionStatus.PENDING)

        val stoppedRunning = callbacks.stopExecution("running-execution")
        callbacks.setActiveProcessExecutionId("pending-execution")
        val stoppedPending = callbacks.stopExecution("pending-execution")

        assertThat(stoppedRunning).isTrue()
        assertThat(stoppedPending).isTrue()
        assertThat(callbacks.getExecutionStatus("running-execution")).isEqualTo(ExecutionStatus.CANCELLED)
        assertThat(callbacks.getExecutionStatus("pending-execution")).isEqualTo(ExecutionStatus.CANCELLED)
        assertThat(callbacks.getExecutionOutput("running-execution")?.status)
            .isEqualTo(ExecutionStatus.CANCELLED)
        assertThat(callbacks.getExecutionOutput("running-execution")?.errorOutput)
            .isEqualTo("Execution cancelled by user")
        verify(exactly = 2) { processManager.stopCurrentProcess() }
    }

    @Test
    fun `stopExecution does not stop process for stale active state`() {
        val processManager = mockk<ProcessManager>(relaxed = true)
        val callbacks = newCallbacks(processManager)
        callbacks.putExecutionState("old-execution", ExecutionStatus.RUNNING)
        callbacks.putExecutionState("new-execution", ExecutionStatus.RUNNING)
        callbacks.setActiveProcessExecutionId("new-execution")

        val stopped = callbacks.stopExecution("old-execution")

        assertThat(stopped).isFalse()
        assertThat(callbacks.getExecutionStatus("old-execution")).isEqualTo(ExecutionStatus.RUNNING)
        assertThat(callbacks.getExecutionStatus("new-execution")).isEqualTo(ExecutionStatus.RUNNING)
        verify(exactly = 0) { processManager.stopCurrentProcess() }
    }

    @Test
    fun `stopExecution returns false when process manager cannot stop active process`() {
        val processManager = mockk<ProcessManager>()
        every { processManager.stopCurrentProcess() } returns false
        val callbacks = newCallbacks(processManager)
        callbacks.putExecutionState("running-execution", ExecutionStatus.RUNNING)
        callbacks.setActiveProcessExecutionId("running-execution")
        callbacks.putExecutionResult(
            ExecutionResult(
                executionId = "running-execution",
                success = true,
                exitCode = 0,
                output = "started",
                errorOutput = "",
                duration = 0,
                status = ExecutionStatus.RUNNING
            )
        )

        val stopped = callbacks.stopExecution("running-execution")

        assertThat(stopped).isFalse()
        assertThat(callbacks.getExecutionStatus("running-execution")).isEqualTo(ExecutionStatus.RUNNING)
        assertThat(callbacks.getExecutionOutput("running-execution")?.status)
            .isEqualTo(ExecutionStatus.RUNNING)
        verify(exactly = 1) { processManager.stopCurrentProcess() }
    }

    @Test
    fun `runProject fails and skips compile when editor save fails`() = runTest {
        val editorManager = mockk<IEditorManager>(relaxed = true)
        val outputManager = mockk<IOutputManager>(relaxed = true)
        val compileProjectUseCase = mockk<CompileProjectUseCase>(relaxed = true)
        coEvery { editorManager.saveAll(SaveReason.MANUAL) } returns listOf(
            SaveResult.Failure("disk full")
        )
        val callbacks = newCallbacks(
            processManager = mockk(relaxed = true),
            editorManager = editorManager,
            outputManager = outputManager,
            compileProjectUseCase = compileProjectUseCase,
            scope = this
        )

        val started = callbacks.runProject(RunRequest())
        val result = callbacks.awaitTerminalResult(started.executionId)

        assertThat(started.status).isEqualTo(ExecutionStatus.RUNNING)
        assertThat(result.status).isEqualTo(ExecutionStatus.FAILED)
        assertThat(result.success).isFalse()
        assertThat(result.exitCode).isEqualTo(-1)
        assertThat(result.errorOutput).contains("disk full")
        coVerify(exactly = 1) { editorManager.saveAll(SaveReason.MANUAL) }
        coVerify(exactly = 0) { compileProjectUseCase.execute(any(), any(), any(), any(), any()) }
        verify {
            outputManager.appendOutput(
                match { it.contains("disk full") },
                IOutputManager.OutputChannel.RUN
            )
        }
    }

    @Test
    fun `runTests fails and skips target resolution when editor save fails`() = runTest {
        val editorManager = mockk<IEditorManager>(relaxed = true)
        val outputManager = mockk<IOutputManager>(relaxed = true)
        val compileProjectUseCase = mockk<CompileProjectUseCase>(relaxed = true)
        coEvery { editorManager.saveAll(SaveReason.MANUAL) } returns listOf(
            SaveResult.Failure("permission denied")
        )
        val callbacks = newCallbacks(
            processManager = mockk(relaxed = true),
            editorManager = editorManager,
            outputManager = outputManager,
            compileProjectUseCase = compileProjectUseCase,
            scope = this
        )

        val started = callbacks.runTests(TestRequest())
        val result = callbacks.awaitTerminalResult(started.executionId)

        assertThat(started.status).isEqualTo(ExecutionStatus.RUNNING)
        assertThat(result.status).isEqualTo(ExecutionStatus.FAILED)
        assertThat(result.success).isFalse()
        assertThat(result.errorOutput).contains("permission denied")
        coVerify(exactly = 1) { editorManager.saveAll(SaveReason.MANUAL) }
        coVerify(exactly = 0) { compileProjectUseCase.getAvailableTargets() }
        coVerify(exactly = 0) { compileProjectUseCase.execute(any(), any(), any(), any(), any()) }
        verify {
            outputManager.appendOutput(
                match { it.contains("permission denied") },
                IOutputManager.OutputChannel.RUN
            )
        }
    }

    @Test
    fun `buildProject fails and skips compile when editor save fails`() = runTest {
        val editorManager = mockk<IEditorManager>(relaxed = true)
        val outputManager = mockk<IOutputManager>(relaxed = true)
        val compileProjectUseCase = mockk<CompileProjectUseCase>(relaxed = true)
        coEvery { editorManager.saveAll(SaveReason.MANUAL) } returns listOf(
            SaveResult.Failure("read only file")
        )
        val callbacks = newCallbacks(
            processManager = mockk(relaxed = true),
            editorManager = editorManager,
            outputManager = outputManager,
            compileProjectUseCase = compileProjectUseCase,
            scope = this
        )

        val started = callbacks.buildProject(BuildRequest())
        val result = callbacks.awaitTerminalResult(started.executionId)

        assertThat(started.status).isEqualTo(ExecutionStatus.RUNNING)
        assertThat(result.status).isEqualTo(ExecutionStatus.FAILED)
        assertThat(result.success).isFalse()
        assertThat(result.errorOutput).contains("read only file")
        coVerify(exactly = 1) { editorManager.saveAll(SaveReason.MANUAL) }
        coVerify(exactly = 0) { compileProjectUseCase.execute(any(), any(), any(), any(), any()) }
        coVerify(exactly = 0) { compileProjectUseCase.executeCMakeMaintenance(any()) }
        verify {
            outputManager.appendOutput(
                match { it.contains("read only file") },
                IOutputManager.OutputChannel.BUILD
            )
        }
    }

    @Test
    fun `terminal completion does not overwrite cancelled execution`() {
        val callbacks = newCallbacks(mockk<ProcessManager>(relaxed = true))
        callbacks.putExecutionState("cancelled-execution", ExecutionStatus.CANCELLED)
        callbacks.putExecutionResult(
            ExecutionResult(
                executionId = "cancelled-execution",
                success = false,
                exitCode = 0,
                output = "started",
                errorOutput = "Execution cancelled by user",
                duration = 0,
                status = ExecutionStatus.CANCELLED
            )
        )

        val completed = callbacks.completeExecutionIfActiveForTest(
            ExecutionResult(
                executionId = "cancelled-execution",
                success = true,
                exitCode = 0,
                output = "finished",
                errorOutput = "",
                duration = 10,
                status = ExecutionStatus.SUCCESS
            )
        )

        assertThat(completed).isFalse()
        assertThat(callbacks.getExecutionStatus("cancelled-execution")).isEqualTo(ExecutionStatus.CANCELLED)
        assertThat(callbacks.getExecutionOutput("cancelled-execution")?.status)
            .isEqualTo(ExecutionStatus.CANCELLED)
        assertThat(callbacks.getExecutionOutput("cancelled-execution")?.output)
            .isEqualTo("started")
    }

    private fun newCallbacks(
        processManager: ProcessManager,
        editorManager: IEditorManager = mockk(relaxed = true),
        outputManager: IOutputManager = mockk(relaxed = true),
        compileProjectUseCase: CompileProjectUseCase = mockk(relaxed = true),
        scope: CoroutineScope = CoroutineScope(SupervisorJob())
    ): ExecutionCallbacksImpl = ExecutionCallbacksImpl(
        projectRoot = ".",
        processManager = processManager,
        runConfigManager = RunConfigurationManager(),
        editorManager = editorManager,
        outputManager = outputManager,
        compileProjectUseCase = compileProjectUseCase,
        scope = scope,
        bottomPanelViewModel = mockk<BottomPanelViewModel>(relaxed = true),
        bottomPanelController = mockk<BottomPanelController>(relaxed = true)
    )

    private suspend fun ExecutionCallbacksImpl.awaitTerminalResult(executionId: String): ExecutionResult = withTimeout(2_000) {
        while (true) {
            val result = getExecutionResult(executionId)
            if (result != null && result.status.isTerminalForTest()) {
                return@withTimeout result
            }
            delay(10)
        }
        error("Timed out waiting for execution result: $executionId")
    }

    private fun ExecutionStatus.isTerminalForTest(): Boolean = this in setOf(
        ExecutionStatus.SUCCESS,
        ExecutionStatus.FAILED,
        ExecutionStatus.CANCELLED,
        ExecutionStatus.TIMEOUT
    )

    private fun resetAppStrings() {
        val field = AppStrings::class.java.getDeclaredField("appContext")
        field.isAccessible = true
        field.set(AppStrings, null)
    }

    private fun ExecutionCallbacksImpl.putExecutionState(
        executionId: String,
        status: ExecutionStatus
    ) {
        executionStatesForTest()[executionId] = status
    }

    private fun ExecutionCallbacksImpl.putExecutionResult(result: ExecutionResult) {
        executionResultsForTest()[result.executionId] = result
    }

    private fun ExecutionCallbacksImpl.setActiveProcessExecutionId(executionId: String) {
        activeProcessExecutionIdForTest().set(executionId)
    }

    private fun ExecutionCallbacksImpl.completeExecutionIfActiveForTest(result: ExecutionResult): Boolean {
        val method = ExecutionCallbacksImpl::class.java.getDeclaredMethod(
            "completeExecutionIfActive",
            ExecutionResult::class.java
        )
        method.isAccessible = true
        return method.invoke(this, result) as Boolean
    }

    @Suppress("UNCHECKED_CAST")
    private fun ExecutionCallbacksImpl.executionStatesForTest(): ConcurrentHashMap<String, ExecutionStatus> = privateField("executionStates") as ConcurrentHashMap<String, ExecutionStatus>

    @Suppress("UNCHECKED_CAST")
    private fun ExecutionCallbacksImpl.executionResultsForTest(): ConcurrentHashMap<String, ExecutionResult> = privateField("executionResults") as ConcurrentHashMap<String, ExecutionResult>

    @Suppress("UNCHECKED_CAST")
    private fun ExecutionCallbacksImpl.activeProcessExecutionIdForTest(): AtomicReference<String?> = privateField("activeProcessExecutionId") as AtomicReference<String?>

    private fun ExecutionCallbacksImpl.privateField(name: String): Any {
        val field = ExecutionCallbacksImpl::class.java.getDeclaredField(name)
        field.isAccessible = true
        return field.get(this) ?: error("Expected non-null private field: $name")
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class ExecutionCallbacksMainDispatcherRule(
    private val dispatcher: TestDispatcher = UnconfinedTestDispatcher()
) : TestWatcher() {
    override fun starting(description: Description) {
        Dispatchers.setMain(dispatcher)
    }

    override fun finished(description: Description) {
        Dispatchers.resetMain()
    }
}
