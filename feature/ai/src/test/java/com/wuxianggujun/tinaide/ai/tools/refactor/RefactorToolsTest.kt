package com.wuxianggujun.tinaide.ai.tools.refactor

import com.google.common.truth.Truth.assertThat
import com.wuxianggujun.tinaide.ai.tools.ToolExecutionContext
import com.wuxianggujun.tinaide.ai.tools.ToolExecutionResult
import com.wuxianggujun.tinaide.ai.tools.assertCancellationRethrown
import com.wuxianggujun.tinaide.ai.tools.editor.RecordingEditorToolCallbacks
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

class RefactorToolsTest {

    @Before
    fun setUpStrings() {
        installToolTestAppStrings()
    }

    @After
    fun tearDownStrings() {
        resetToolTestAppStrings()
    }

    @Test
    fun `format code forwards whole file request`(): Unit = runBlocking {
        val callbacks = RecordingEditorToolCallbacks()

        assertThat(FormatCodeTool.isDangerous).isTrue()
        val result = FormatCodeTool.execute(
            toolCall(FormatCodeTool.name, """{"file_path":"src/main.cpp"}"""),
            editorContext(callbacks)
        )

        assertThat(result.success().content).contains("Code formatted successfully")
        assertThat(callbacks.formattedFilePath).isEqualTo("src/main.cpp")
        assertThat(callbacks.formattedRange).isNull()
    }

    @Test
    fun `format code forwards range request`(): Unit = runBlocking {
        val callbacks = RecordingEditorToolCallbacks()

        FormatCodeTool.execute(
            toolCall(FormatCodeTool.name, """{"file_path":"src/main.cpp","start_line":2,"end_line":5}"""),
            editorContext(callbacks)
        )

        assertThat(callbacks.formattedRange).isEqualTo(Triple("src/main.cpp", 2, 5))
    }

    @Test
    fun `format code validates path range and callback failure`(): Unit = runBlocking {
        val callbacks = RecordingEditorToolCallbacks(formatCodeResult = false)

        assertThat(FormatCodeTool.execute(toolCall(FormatCodeTool.name, """{"file_path":" "}"""), editorContext(callbacks)))
            .isEqualTo(ToolExecutionResult.Error("File path is required"))
        assertThat(FormatCodeTool.execute(toolCall(FormatCodeTool.name, """{"file_path":"a.cpp","start_line":9,"end_line":3}"""), editorContext(callbacks)))
            .isEqualTo(ToolExecutionResult.Error("Start line (9) must be less than or equal to end line (3)"))
        assertThat(FormatCodeTool.execute(toolCall(FormatCodeTool.name, """{"file_path":"a.cpp"}"""), editorContext(callbacks)))
            .isEqualTo(ToolExecutionResult.Error("Failed to format code. The file may not support formatting or clang-format is not available."))
    }

    @Test
    fun `extract method generates method body and metadata`(): Unit = runBlocking {
        assertThat(ExtractMethodTool.isDangerous).isFalse()

        val result = ExtractMethodTool.execute(
            toolCall(
                ExtractMethodTool.name,
                """{"method_name":"calculate","code":"val x = 1","visibility":"public"}"""
            ),
            ToolExecutionContext()
        )

        val success = result.success()
        assertThat(success.content).contains("public fun calculate()")
        assertThat(success.content).contains("calculate()")
        assertThat(success.metadata).containsExactly(
            "methodName",
            "calculate",
            "visibility",
            "public",
            "linesExtracted",
            1
        )
    }

    @Test
    fun `add documentation handles function docs and unsupported type`(): Unit = runBlocking {
        assertThat(AddDocumentationTool.isDangerous).isFalse()

        val success = AddDocumentationTool.execute(
            toolCall(
                AddDocumentationTool.name,
                """{"code":"fun sum(a: Int): Int = a","element_type":"function","description":"Adds numbers"}"""
            ),
            ToolExecutionContext()
        ).success()

        assertThat(success.content).contains("Adds numbers")
        assertThat(success.content).contains("@param a")
        assertThat(success.content).contains("@return")
        assertThat(success.metadata).containsEntry("elementType", "function")

        val failure = AddDocumentationTool.execute(
            toolCall(
                AddDocumentationTool.name,
                """{"code":"val x = 1","element_type":"module","description":"Unsupported"}"""
            ),
            ToolExecutionContext()
        )

        assertThat(failure).isEqualTo(ToolExecutionResult.Error("Unsupported element type: module"))
    }

    @Test
    fun `add documentation handles class property required fields and unit function docs`(): Unit = runBlocking {
        val classDoc = AddDocumentationTool.execute(
            toolCall(
                AddDocumentationTool.name,
                """{"code":"data class Person(val name: String, val age: Int)","element_type":"class","description":"Represents a person"}"""
            ),
            ToolExecutionContext()
        ).success()
        val propertyDoc = AddDocumentationTool.execute(
            toolCall(
                AddDocumentationTool.name,
                """{"code":"val count: Int = 0","element_type":"property","description":"Current count"}"""
            ),
            ToolExecutionContext()
        ).success()
        val unitFunctionDoc = AddDocumentationTool.execute(
            toolCall(
                AddDocumentationTool.name,
                """{"code":"fun log(message: String): Unit = println(message)","element_type":"function","description":"Logs a message"}"""
            ),
            ToolExecutionContext()
        ).success()
        val missingDescription = AddDocumentationTool.execute(
            toolCall(
                AddDocumentationTool.name,
                """{"code":"val count = 0","element_type":"property","description":" "}"""
            ),
            ToolExecutionContext()
        )

        assertThat(classDoc.content).contains("Represents a person")
        assertThat(classDoc.content).contains("@property name")
        assertThat(classDoc.content).contains("@property age")
        assertThat(classDoc.metadata).containsEntry("elementType", "class")
        assertThat(propertyDoc.content).contains("Current count")
        assertThat(propertyDoc.metadata).containsEntry("elementType", "property")
        assertThat(unitFunctionDoc.content).contains("@param message")
        assertThat(unitFunctionDoc.content).doesNotContain("@return")
        assertThat(missingDescription).isEqualTo(ToolExecutionResult.Error("Code and description are required"))
    }

    @Test
    fun `format code rethrows cancellation exception`(): Unit = runBlocking {
        val context = editorContext(cancellingEditorCallbacks())

        assertCancellationRethrown {
            FormatCodeTool.execute(toolCall(FormatCodeTool.name, """{"file_path":"src/main.cpp"}"""), context)
        }
        assertCancellationRethrown {
            FormatCodeTool.execute(
                toolCall(FormatCodeTool.name, """{"file_path":"src/main.cpp","start_line":1,"end_line":2}"""),
                context
            )
        }
    }

    private fun editorContext(callbacks: EditorToolCallbacks): ToolExecutionContext = ToolExecutionContext(additionalData = mapOf("editorCallbacks" to callbacks))

    private fun cancellingEditorCallbacks(): EditorToolCallbacks = object : EditorToolCallbacks {
        override fun getCurrentFile(): CurrentFileInfo? = null

        override fun getSelectedCode(): SelectedCodeInfo? = null

        override fun insertCode(code: String) = Unit

        override fun replaceSelectedCode(code: String) = Unit

        override suspend fun formatCode(filePath: String): Boolean = throw CancellationException("cancelled")

        override suspend fun formatCodeRange(filePath: String, startLine: Int, endLine: Int): Boolean = throw CancellationException("cancelled")
    }
}
