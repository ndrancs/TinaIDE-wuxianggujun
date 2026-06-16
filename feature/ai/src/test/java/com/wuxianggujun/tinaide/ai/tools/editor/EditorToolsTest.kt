package com.wuxianggujun.tinaide.ai.tools.editor

import com.google.common.truth.Truth.assertThat
import com.wuxianggujun.tinaide.ai.tools.ToolExecutionContext
import com.wuxianggujun.tinaide.ai.tools.ToolExecutionResult
import com.wuxianggujun.tinaide.ai.tools.assertCancellationRethrown
import com.wuxianggujun.tinaide.ai.tools.executor.editor.CurrentFileInfo
import com.wuxianggujun.tinaide.ai.tools.executor.editor.EditorToolCallbacks
import com.wuxianggujun.tinaide.ai.tools.executor.editor.SelectedCodeInfo
import com.wuxianggujun.tinaide.ai.tools.installToolTestAppStrings
import com.wuxianggujun.tinaide.ai.tools.resetToolTestAppStrings
import com.wuxianggujun.tinaide.ai.tools.success
import com.wuxianggujun.tinaide.ai.tools.toolCall
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test

class EditorToolsTest {
    @Before
    fun setUpStrings() {
        installToolTestAppStrings()
    }

    @After
    fun tearDownStrings() {
        resetToolTestAppStrings()
    }

    @Test
    fun `get current file returns content and metadata`(): Unit = runBlocking {
        val callbacks = RecordingEditorToolCallbacks(
            currentFile = CurrentFileInfo(
                fileName = "Main.kt",
                language = "kotlin",
                content = "fun main() {\n    println(1)\n}"
            )
        )

        assertThat(GetCurrentFileTool.isDangerous).isFalse()
        val result = GetCurrentFileTool.execute(toolCall(GetCurrentFileTool.name), editorContext(callbacks))

        val success = result.success()
        assertThat(success.content).contains("Main.kt")
        assertThat(success.content).contains("fun main")
        assertThat(success.metadata).containsExactly(
            "fileName",
            "Main.kt",
            "language",
            "kotlin",
            "lines",
            3
        )
    }

    @Test
    fun `get selected code returns range metadata`(): Unit = runBlocking {
        val callbacks = RecordingEditorToolCallbacks(
            selectedCode = SelectedCodeInfo(
                fileName = "Main.kt",
                language = "kotlin",
                startLine = 10,
                endLine = 12,
                content = "val answer = 42"
            )
        )

        assertThat(GetSelectedCodeTool.isDangerous).isFalse()
        val result = GetSelectedCodeTool.execute(toolCall(GetSelectedCodeTool.name), editorContext(callbacks))

        val success = result.success()
        assertThat(success.content).contains("val answer")
        assertThat(success.metadata).containsExactly(
            "fileName", "Main.kt",
            "language", "kotlin",
            "startLine", 10,
            "endLine", 12,
            "lines", 1
        )
    }

    @Test
    fun `insert and replace tools forward code to callbacks`(): Unit = runBlocking {
        val callbacks = RecordingEditorToolCallbacks()

        assertThat(InsertCodeTool.isDangerous).isTrue()
        assertThat(ReplaceSelectedCodeTool.isDangerous).isTrue()

        val insertResult = InsertCodeTool.execute(
            toolCall(InsertCodeTool.name, """{"code":"val inserted = true"}"""),
            editorContext(callbacks)
        )
        val replaceResult = ReplaceSelectedCodeTool.execute(
            toolCall(ReplaceSelectedCodeTool.name, """{"code":"val replaced = true"}"""),
            editorContext(callbacks)
        )

        assertThat(insertResult).isEqualTo(ToolExecutionResult.Success("Code inserted successfully"))
        assertThat(replaceResult).isEqualTo(ToolExecutionResult.Success("Code replaced successfully"))
        assertThat(callbacks.insertedCode).isEqualTo("val inserted = true")
        assertThat(callbacks.replacedCode).isEqualTo("val replaced = true")
    }

    @Test
    fun `editor tools return explicit errors for unavailable editor state`(): Unit = runBlocking {
        val callbacks = RecordingEditorToolCallbacks()

        assertThat(GetCurrentFileTool.execute(toolCall(GetCurrentFileTool.name), editorContext(callbacks)))
            .isEqualTo(ToolExecutionResult.Error("No current file open"))
        assertThat(GetSelectedCodeTool.execute(toolCall(GetSelectedCodeTool.name), editorContext(callbacks)))
            .isEqualTo(ToolExecutionResult.Error("No code selected"))
        assertThat(InsertCodeTool.execute(toolCall(InsertCodeTool.name, """{"code":" "}"""), editorContext(callbacks)))
            .isEqualTo(ToolExecutionResult.Error("Code parameter is required"))
        assertThat(ReplaceSelectedCodeTool.execute(toolCall(ReplaceSelectedCodeTool.name, """{"code":" "}"""), editorContext(callbacks)))
            .isEqualTo(ToolExecutionResult.Error("Code parameter is required"))
    }

    @Test
    fun `editor mutation tools rethrow cancellation exception`(): Unit = runBlocking {
        val context = editorContext(cancellingEditorCallbacks())

        assertCancellationRethrown {
            InsertCodeTool.execute(toolCall(InsertCodeTool.name, """{"code":"val x = 1"}"""), context)
        }
        assertCancellationRethrown {
            ReplaceSelectedCodeTool.execute(toolCall(ReplaceSelectedCodeTool.name, """{"code":"val x = 1"}"""), context)
        }
    }

    private fun editorContext(callbacks: EditorToolCallbacks): ToolExecutionContext = ToolExecutionContext(additionalData = mapOf("editorCallbacks" to callbacks))

    private fun cancellingEditorCallbacks(): EditorToolCallbacks = object : EditorToolCallbacks {
        override fun getCurrentFile(): CurrentFileInfo? = null

        override fun getSelectedCode(): SelectedCodeInfo? = null

        override fun insertCode(code: String): Unit = throw CancellationException("cancelled")

        override fun replaceSelectedCode(code: String): Unit = throw CancellationException("cancelled")

        override suspend fun formatCode(filePath: String): Boolean = throw CancellationException("cancelled")

        override suspend fun formatCodeRange(filePath: String, startLine: Int, endLine: Int): Boolean = throw CancellationException("cancelled")
    }
}
