package com.wuxianggujun.tinaide.ai.integration

import com.google.common.truth.Truth.assertThat
import com.wuxianggujun.tinaide.ai.tools.executor.execution.TestRequest
import com.wuxianggujun.tinaide.core.compile.CompileProjectUseCase
import com.wuxianggujun.tinaide.core.compile.OutputMode
import com.wuxianggujun.tinaide.core.compile.RunConfiguration
import com.wuxianggujun.tinaide.core.compile.TargetInfo
import org.junit.Test

class AiTestExecutionPlannerTest {

    @Test
    fun `resolve uses phony test target as build operation`() {
        val plan = AiTestExecutionPlanner.resolve(
            request = TestRequest(),
            selectedConfig = RunConfiguration(outputMode = OutputMode.SDL, targetName = "app"),
            targets = listOf(TargetInfo(name = "test", type = TargetInfo.Type.OTHER, sources = emptyList()))
        )

        assertThat(plan.operation.action).isEqualTo(CompileProjectUseCase.Action.BUILD)
        assertThat(plan.targetName).isEqualTo("test")
        assertThat(plan.runConfig.outputMode).isEqualTo(OutputMode.TERMINAL)
    }

    @Test
    fun `resolve runs executable test target with arguments`() {
        val plan = AiTestExecutionPlanner.resolve(
            request = TestRequest(testMethod = "--gtest_filter=Foo.*", arguments = listOf("--verbose")),
            selectedConfig = RunConfiguration(),
            targets = listOf(TargetInfo(name = "unit_tests", type = TargetInfo.Type.EXECUTABLE, sources = emptyList()))
        )

        assertThat(plan.operation.action).isEqualTo(CompileProjectUseCase.Action.RUN)
        assertThat(plan.targetName).isEqualTo("unit_tests")
        assertThat(plan.runConfig.args).isEqualTo("--verbose --gtest_filter=Foo.*")
    }
}
