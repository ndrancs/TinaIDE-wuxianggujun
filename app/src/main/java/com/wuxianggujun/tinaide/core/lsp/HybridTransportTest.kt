package com.wuxianggujun.tinaide.core.lsp

import android.util.Log

/**
 * 混合传输测试 - 集成控制通道和共享内存
 *
 * 测试场景：
 * 1. 小数据（2KB）通过控制通道直接传输
 * 2. 大数据（50KB）通过共享内存 + FD 传递
 */
object HybridTransportTest {

    private const val TAG = "HybridTransportTest"

    init {
        System.loadLibrary("native-compiler")
    }

    /**
     * 运行混合传输集成测试
     * - 测试控制通道基本功能
     * - 测试共享内存 FD 传递
     * - 测试自动阈值切换
     */
    external fun runIntegrationTest(): Boolean

    /**
     * 运行所有测试并返回结果
     */
    fun runAllTests(): TestResult {
        Log.i(TAG, "========== 开始混合传输测试 ==========")

        val results = mutableListOf<Pair<String, Boolean>>()

        // 测试：混合传输集成
        Log.i(TAG, "--- 混合传输集成测试 ---")
        val integrationResult = try {
            runIntegrationTest()
        } catch (e: Exception) {
            Log.e(TAG, "集成测试异常", e)
            false
        }
        results.add("混合传输集成" to integrationResult)
        Log.i(TAG, "结果: ${if (integrationResult) "✅ 通过" else "❌ 失败"}")

        val passedCount = results.count { it.second }
        val totalCount = results.size

        Log.i(TAG, "========== 测试完成 ==========")
        Log.i(TAG, "通过: $passedCount / $totalCount")

        return TestResult(
            tests = results,
            passedCount = passedCount,
            totalCount = totalCount
        )
    }

    data class TestResult(
        val tests: List<Pair<String, Boolean>>,
        val passedCount: Int,
        val totalCount: Int
    ) {
        val allPassed: Boolean get() = passedCount == totalCount
        val passRate: Double get() = if (totalCount > 0) passedCount.toDouble() / totalCount else 0.0

        fun printSummary() {
            println("========== 混合传输测试报告 ==========")
            tests.forEach { (name, passed) ->
                println("${if (passed) "✅" else "❌"} $name")
            }
            println("通过率: ${(passRate * 100).toInt()}% ($passedCount / $totalCount)")
            println("========================================")
        }
    }
}
