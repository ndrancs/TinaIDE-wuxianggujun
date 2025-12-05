package com.wuxianggujun.tinaide.core.lsp

import android.content.Context
import android.util.Log
import com.wuxianggujun.tinaide.lsp.NativeLspService
import java.io.File

/**
 * Native LSP 客户端链路自检
 * 用于验证 Hover / Completion / Definition / References 是否打通
 */
object NativeLspClientSelfTest {

    private const val TAG = "NativeLspSelfTest"
    private const val DEFAULT_CLANGD_PATH = "/data/data/com.wuxianggujun.tinaide/clangd"
    private const val DEFAULT_WORK_DIR = "/"

    data class CaseResult(
        val name: String,
        val passed: Boolean,
        val message: String = ""
    )

    data class TestResult(
        val cases: List<CaseResult>
    ) {
        val allPassed: Boolean get() = cases.all { it.passed }
        val passRate: Double get() = if (cases.isEmpty()) 0.0 else cases.count { it.passed }.toDouble() / cases.size

        fun printSummary() {
            Log.i(TAG, "========= Native LSP 自检报告 =========")
            cases.forEach { case ->
                Log.i(TAG, "${if (case.passed) "✅" else "❌"} ${case.name}: ${case.message}")
            }
            Log.i(TAG, "通过率: ${(passRate * 100).toInt()}% (${cases.count { it.passed }} / ${cases.size})")
            Log.i(TAG, "========================================")
        }
    }

    private fun <T> waitForResult(timeoutMs: Long = 3_000, fetch: () -> T?): T? {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val value = fetch()
            if (value != null) {
                return value
            }
            Thread.sleep(10)
        }
        return null
    }

    fun run(
        context: Context? = null,
        clangdPath: String? = null,
        workDir: String = DEFAULT_WORK_DIR
    ): TestResult {
        val cases = mutableListOf<CaseResult>()

        val resolvedClangdPath = when {
            !clangdPath.isNullOrBlank() -> clangdPath
            context != null -> NativeLspBinaryResolver.resolveClangdBinary(context)
            else -> null
        } ?: DEFAULT_CLANGD_PATH

        val clangdFile = File(resolvedClangdPath)
        if (!clangdFile.exists()) {
            cases += CaseResult(
                "Clangd binary",
                false,
                "未找到 $resolvedClangdPath，请先安装 sysroot 或在 run() 传入路径"
            )
            return TestResult(cases)
        }
        NativeLspService.setDefaultClangdBinary(clangdFile.absolutePath)
        cases += CaseResult("Clangd binary", true, resolvedClangdPath)

        var initialized = false
        try {
            val initSuccess = NativeLspService.initialize(resolvedClangdPath, workDir)
            initialized = initSuccess
            cases += CaseResult("Initialize", initSuccess, if (initSuccess) "" else "Native 初始化失败")
            if (!initSuccess) {
                return TestResult(cases)
            }

            val fileUri = "file:///test/project/main.cpp"
            val content = """
                int add(int a, int b) { return a + b; }
                int main() { return add(40, 2); }
            """.trimIndent()
            NativeLspService.nativeDidOpenTextDocument(fileUri, content)

            val hoverId = NativeLspService.nativeRequestHover(fileUri, 0, 5)
            val hover = waitForResult { NativeLspService.nativeGetHoverResult(hoverId) }
            cases += CaseResult("Hover", hover != null, hover?.content ?: "无结果")

            val completionId = NativeLspService.nativeRequestCompletion(fileUri, 0, 8)
            val completion = waitForResult { NativeLspService.nativeGetCompletionResult(completionId) }
            cases += CaseResult(
                "Completion",
                completion != null,
                if (completion != null) "${completion.items.size} items" else "无结果"
            )

            val definitionId = NativeLspService.nativeRequestDefinition(fileUri, 0, 3)
            val definition = waitForResult { NativeLspService.nativeGetDefinitionResult(definitionId)?.toList() }
            cases += CaseResult(
                "Definition",
                definition != null,
                definition?.firstOrNull()?.filePath ?: "无结果"
            )

            val referencesId = NativeLspService.nativeRequestReferences(fileUri, 0, 3, true)
            val references = waitForResult { NativeLspService.nativeGetReferencesResult(referencesId)?.toList() }
            cases += CaseResult(
                "References",
                references != null,
                if (references != null) "${references.size} entries" else "无结果"
            )

            NativeLspService.nativeDidCloseTextDocument(fileUri)
        } catch (t: Throwable) {
            cases += CaseResult("Exception", false, t.message ?: "unknown")
            Log.e(TAG, "Native self test failed", t)
        } finally {
            if (initialized) {
                NativeLspService.nativeShutdown()
            }
        }

        return TestResult(cases)
    }
}
