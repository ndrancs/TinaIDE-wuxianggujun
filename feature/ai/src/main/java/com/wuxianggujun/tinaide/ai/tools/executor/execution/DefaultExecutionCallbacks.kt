package com.wuxianggujun.tinaide.ai.tools.executor.execution

import com.wuxianggujun.tinaide.ai.tools.localizedToolText
import com.wuxianggujun.tinaide.core.i18n.Strings
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * 默认的执行回调实现
 * 提供基础的执行功能实现
 */
class DefaultExecutionCallbacks : ExecutionCallbacks {

    private val executionStatusMap = ConcurrentHashMap<String, ExecutionStatus>()
    private val executionResultMap = ConcurrentHashMap<String, ExecutionResult>()

    override fun runProject(request: RunRequest): ExecutionResult = failedResult(
        localizedToolText(
            Strings.ai_tool_error_default_run_callback_unavailable,
            "Project execution is unavailable because no run callback is registered."
        )
    )

    override fun runTests(request: TestRequest): ExecutionResult = failedResult(
        localizedToolText(
            Strings.ai_tool_error_default_test_callback_unavailable,
            "Test execution is unavailable because no test callback is registered."
        )
    )

    override fun buildProject(request: BuildRequest): ExecutionResult = failedResult(
        localizedToolText(
            Strings.ai_tool_error_default_build_callback_unavailable,
            "Build execution is unavailable because no build callback is registered."
        )
    )

    private fun failedResult(errorOutput: String): ExecutionResult {
        val executionId = UUID.randomUUID().toString()

        val result = ExecutionResult(
            executionId = executionId,
            success = false,
            exitCode = -1,
            output = "",
            errorOutput = errorOutput,
            duration = 0,
            status = ExecutionStatus.FAILED
        )
        executionStatusMap[executionId] = result.status
        executionResultMap[executionId] = result
        return result
    }

    override fun stopExecution(executionId: String): Boolean {
        val status = executionStatusMap[executionId]
        if (status == ExecutionStatus.RUNNING || status == ExecutionStatus.PENDING) {
            executionStatusMap[executionId] = ExecutionStatus.CANCELLED
            return true
        }
        return false
    }

    override fun getExecutionStatus(executionId: String): ExecutionStatus? = executionStatusMap[executionId]

    override fun getExecutionOutput(executionId: String): ExecutionOutputResult? {
        val result = executionResultMap[executionId] ?: return null
        return ExecutionOutputResult(
            executionId = result.executionId,
            output = result.output,
            errorOutput = result.errorOutput,
            status = result.status,
            exitCode = result.exitCode
        )
    }

    override fun getBuildErrors(executionId: String?): BuildErrorsResult {
        // 默认实现返回空错误列表
        return BuildErrorsResult(
            hasErrors = false,
            errorCount = 0,
            warningCount = 0,
            errors = emptyList()
        )
    }

    override fun navigateToRunOutput() {
        // 默认实现为空，子类可以覆盖
    }

    override fun navigateToBuildLog() {
        // 默认实现为空，子类可以覆盖
    }

    /**
     * 更新执行状态
     */
    fun updateExecutionStatus(executionId: String, status: ExecutionStatus) {
        executionStatusMap[executionId] = status
    }

    /**
     * 清除执行状态
     */
    fun clearExecutionStatus(executionId: String) {
        executionStatusMap.remove(executionId)
        executionResultMap.remove(executionId)
    }
}
