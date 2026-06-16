package com.wuxianggujun.tinaide.ai.tools.filesystem

import com.google.common.truth.Truth.assertThat
import com.wuxianggujun.tinaide.ai.tools.ConfirmationSeverity
import com.wuxianggujun.tinaide.ai.tools.ToolExecutionContext
import com.wuxianggujun.tinaide.ai.tools.ToolExecutionResult
import com.wuxianggujun.tinaide.ai.tools.executor.filesystem.CopyFileRequest
import com.wuxianggujun.tinaide.ai.tools.executor.filesystem.CreateDirectoryRequest
import com.wuxianggujun.tinaide.ai.tools.executor.filesystem.DeleteFileRequest
import com.wuxianggujun.tinaide.ai.tools.executor.filesystem.DeleteLinesRequest
import com.wuxianggujun.tinaide.ai.tools.executor.filesystem.FileOperationResult
import com.wuxianggujun.tinaide.ai.tools.executor.filesystem.FileWriteRequest
import com.wuxianggujun.tinaide.ai.tools.executor.filesystem.InsertLineRequest
import com.wuxianggujun.tinaide.ai.tools.executor.filesystem.InsertPosition
import com.wuxianggujun.tinaide.ai.tools.executor.filesystem.ListFilesRequest
import com.wuxianggujun.tinaide.ai.tools.executor.filesystem.MoveFileRequest
import com.wuxianggujun.tinaide.ai.tools.executor.filesystem.ReplaceLineRequest
import com.wuxianggujun.tinaide.ai.tools.executor.filesystem.ReplaceTextRequest
import com.wuxianggujun.tinaide.ai.tools.installToolTestAppStrings
import com.wuxianggujun.tinaide.ai.tools.resetToolTestAppStrings
import com.wuxianggujun.tinaide.ai.tools.success
import com.wuxianggujun.tinaide.ai.tools.toolCall
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test

class FileSystemToolsTest {

    @Before
    fun setUpStrings() {
        installToolTestAppStrings()
    }

    @After
    fun tearDownStrings() {
        resetToolTestAppStrings()
    }

    @Test
    fun `read file returns content metadata and forwards path`(): Unit = runBlocking {
        val callbacks = RecordingFileSystemCallbacks()

        val result = ReadFileTool.execute(
            toolCall(ReadFileTool.name, """{"path":"src/main.cpp"}"""),
            fileSystemContext(callbacks)
        )

        val success = result.success()
        assertThat(success.content).isEqualTo("hello\nworld")
        assertThat(success.metadata).containsExactly(
            "path",
            "src/main.cpp",
            "size",
            11L,
            "lines",
            2,
            "encoding",
            "UTF-8"
        )
        assertThat(callbacks.lastReadPath).isEqualTo("src/main.cpp")
    }

    @Test
    fun `write and list files forward request options`(): Unit = runBlocking {
        val callbacks = RecordingFileSystemCallbacks()

        assertThat(ReadFileTool.isDangerous).isFalse()
        assertThat(WriteFileTool.isDangerous).isTrue()
        assertThat(ListFilesTool.isDangerous).isFalse()

        val writeResult = WriteFileTool.execute(
            toolCall(WriteFileTool.name, """{"path":"src/main.cpp","content":"hello","create_dirs":false}"""),
            fileSystemContext(callbacks)
        )
        val listResult = ListFilesTool.execute(
            toolCall(
                ListFilesTool.name,
                """{"path":"src","recursive":true,"pattern":"*.cpp","include_hidden":true}"""
            ),
            fileSystemContext(callbacks)
        )

        assertThat(writeResult.success().content).contains("File created successfully")
        assertThat(callbacks.lastWriteRequest).isEqualTo(
            FileWriteRequest(path = "src/main.cpp", content = "hello", createDirs = false)
        )
        assertThat(listResult.success().content).contains("src/main.cpp")
        assertThat(callbacks.lastListFilesRequest).isEqualTo(
            ListFilesRequest(path = "src", recursive = true, pattern = "*.cpp", includeHidden = true)
        )
    }

    @Test
    fun `file operation tools forward source destination and path requests`(): Unit = runBlocking {
        val callbacks = RecordingFileSystemCallbacks()

        DeleteFileTool.execute(
            toolCall(DeleteFileTool.name, """{"path":"build/tmp","recursive":true}"""),
            fileSystemContext(callbacks)
        )
        CreateDirectoryTool.execute(
            toolCall(CreateDirectoryTool.name, """{"path":"src/generated","create_parents":false}"""),
            fileSystemContext(callbacks)
        )
        MoveFileTool.execute(
            toolCall(MoveFileTool.name, """{"source":"a.cpp","destination":"b.cpp","overwrite":true}"""),
            fileSystemContext(callbacks)
        )
        CopyFileTool.execute(
            toolCall(CopyFileTool.name, """{"source":"b.cpp","destination":"c.cpp","overwrite":true}"""),
            fileSystemContext(callbacks)
        )
        val infoResult = GetFileInfoTool.execute(
            toolCall(GetFileInfoTool.name, """{"path":"src/main.cpp"}"""),
            fileSystemContext(callbacks)
        )

        assertThat(DeleteFileTool.isDangerous).isTrue()
        assertThat(CreateDirectoryTool.isDangerous).isTrue()
        assertThat(MoveFileTool.isDangerous).isTrue()
        assertThat(CopyFileTool.isDangerous).isTrue()
        assertThat(GetFileInfoTool.isDangerous).isFalse()
        assertThat(callbacks.lastDeleteFileRequest).isEqualTo(DeleteFileRequest("build/tmp", recursive = true))
        assertThat(callbacks.lastCreateDirectoryRequest).isEqualTo(CreateDirectoryRequest("src/generated", createParents = false))
        assertThat(callbacks.lastMoveFileRequest).isEqualTo(MoveFileRequest("a.cpp", "b.cpp", overwrite = true))
        assertThat(callbacks.lastCopyFileRequest).isEqualTo(CopyFileRequest("b.cpp", "c.cpp", overwrite = true))
        assertThat(callbacks.lastFileInfoPath).isEqualTo("src/main.cpp")
        assertThat(infoResult.success().content).contains("main.cpp")
    }

    @Test
    fun `text and line operation tools forward edit requests`(): Unit = runBlocking {
        val callbacks = RecordingFileSystemCallbacks()

        ReplaceTextTool.execute(
            toolCall(ReplaceTextTool.name, """{"path":"a.cpp","old_text":"foo","new_text":"bar","replace_all":true}"""),
            fileSystemContext(callbacks)
        )
        ReplaceLineTool.execute(
            toolCall(ReplaceLineTool.name, """{"path":"a.cpp","line_number":3,"new_content":"return 0;"}"""),
            fileSystemContext(callbacks)
        )
        InsertLineTool.execute(
            toolCall(InsertLineTool.name, """{"path":"a.cpp","line_number":4,"content":"// note","position":"before"}"""),
            fileSystemContext(callbacks)
        )
        DeleteLinesTool.execute(
            toolCall(DeleteLinesTool.name, """{"path":"a.cpp","start_line":8,"end_line":10}"""),
            fileSystemContext(callbacks)
        )

        assertThat(callbacks.lastReplaceTextRequest).isEqualTo(
            ReplaceTextRequest(path = "a.cpp", oldText = "foo", newText = "bar", replaceAll = true)
        )
        assertThat(callbacks.lastReplaceLineRequest).isEqualTo(
            ReplaceLineRequest(path = "a.cpp", lineNumber = 3, newContent = "return 0;")
        )
        assertThat(callbacks.lastInsertLineRequest).isEqualTo(
            InsertLineRequest(path = "a.cpp", lineNumber = 4, content = "// note", position = InsertPosition.BEFORE)
        )
        assertThat(callbacks.lastDeleteLinesRequest).isEqualTo(
            DeleteLinesRequest(path = "a.cpp", startLine = 8, endLine = 10)
        )
        assertThat(ReplaceTextTool.isDangerous).isTrue()
        assertThat(ReplaceLineTool.isDangerous).isTrue()
        assertThat(InsertLineTool.isDangerous).isTrue()
        assertThat(DeleteLinesTool.isDangerous).isTrue()
    }

    @Test
    fun `dangerous confirmations reflect destructive delete scope`() {
        val recursiveDelete = DeleteFileTool.getDangerousConfirmation(
            toolCall(DeleteFileTool.name, """{"path":"build/tmp","recursive":true}""")
        )
        val singleDelete = DeleteFileTool.getDangerousConfirmation(
            toolCall(DeleteFileTool.name, """{"path":"src/main.cpp","recursive":false}""")
        )
        val singleLineDelete = DeleteLinesTool.getDangerousConfirmation(
            toolCall(DeleteLinesTool.name, """{"path":"a.cpp","start_line":4}""")
        )
        val rangeDelete = DeleteLinesTool.getDangerousConfirmation(
            toolCall(DeleteLinesTool.name, """{"path":"a.cpp","start_line":4,"end_line":6}""")
        )

        assertThat(recursiveDelete.severity).isEqualTo(ConfirmationSeverity.CRITICAL)
        assertThat(singleDelete.severity).isEqualTo(ConfirmationSeverity.DANGER)
        assertThat(singleLineDelete.severity).isEqualTo(ConfirmationSeverity.DANGER)
        assertThat(rangeDelete.details).isNotEmpty()
    }

    @Test
    fun `file system tools validate required parameters before callbacks`(): Unit = runBlocking {
        val callbacks = RecordingFileSystemCallbacks()

        assertThat(ReadFileTool.execute(toolCall(ReadFileTool.name, """{"path":" "}"""), fileSystemContext(callbacks)))
            .isEqualTo(ToolExecutionResult.Error("File path is required"))
        assertThat(WriteFileTool.execute(toolCall(WriteFileTool.name, """{"path":" ","content":"x"}"""), fileSystemContext(callbacks)))
            .isEqualTo(ToolExecutionResult.Error("File path is required"))
        assertThat(CreateDirectoryTool.execute(toolCall(CreateDirectoryTool.name, """{"path":" "}"""), fileSystemContext(callbacks)))
            .isEqualTo(ToolExecutionResult.Error("Directory path is required"))
        assertThat(MoveFileTool.execute(toolCall(MoveFileTool.name, """{"source":"","destination":"b"}"""), fileSystemContext(callbacks)))
            .isEqualTo(ToolExecutionResult.Error("Source and destination paths are required"))
        assertThat(ReplaceTextTool.execute(toolCall(ReplaceTextTool.name, """{"path":"a.cpp","old_text":" ","new_text":"bar"}"""), fileSystemContext(callbacks)))
            .isEqualTo(ToolExecutionResult.Error("Old text is required"))
        assertThat(ReplaceLineTool.execute(toolCall(ReplaceLineTool.name, """{"path":"a.cpp","line_number":0,"new_content":"x"}"""), fileSystemContext(callbacks)))
            .isEqualTo(ToolExecutionResult.Error("Line number must be >= 1"))
        assertThat(DeleteLinesTool.execute(toolCall(DeleteLinesTool.name, """{"path":"a.cpp","start_line":5,"end_line":3}"""), fileSystemContext(callbacks)))
            .isEqualTo(ToolExecutionResult.Error("End line must be >= start line"))
    }

    @Test
    fun `file system tools surface callback failures as errors`(): Unit = runBlocking {
        val callbacks = RecordingFileSystemCallbacks(
            operationResult = FileOperationResult(
                success = false,
                message = "operation failed",
                path = "a.cpp"
            )
        )

        val results = listOf(
            DeleteFileTool.execute(
                toolCall(DeleteFileTool.name, """{"path":"a.cpp"}"""),
                fileSystemContext(callbacks)
            ),
            CreateDirectoryTool.execute(
                toolCall(CreateDirectoryTool.name, """{"path":"generated"}"""),
                fileSystemContext(callbacks)
            ),
            MoveFileTool.execute(
                toolCall(MoveFileTool.name, """{"source":"a.cpp","destination":"b.cpp"}"""),
                fileSystemContext(callbacks)
            ),
            CopyFileTool.execute(
                toolCall(CopyFileTool.name, """{"source":"a.cpp","destination":"b.cpp"}"""),
                fileSystemContext(callbacks)
            ),
            ReplaceTextTool.execute(
                toolCall(ReplaceTextTool.name, """{"path":"a.cpp","old_text":"a","new_text":"b"}"""),
                fileSystemContext(callbacks)
            ),
            ReplaceLineTool.execute(
                toolCall(ReplaceLineTool.name, """{"path":"a.cpp","line_number":1,"new_content":"b"}"""),
                fileSystemContext(callbacks)
            ),
            InsertLineTool.execute(
                toolCall(InsertLineTool.name, """{"path":"a.cpp","line_number":1,"content":"b"}"""),
                fileSystemContext(callbacks)
            ),
            DeleteLinesTool.execute(
                toolCall(DeleteLinesTool.name, """{"path":"a.cpp","start_line":1}"""),
                fileSystemContext(callbacks)
            )
        )

        assertThat(results).containsExactlyElementsIn(
            List(results.size) { ToolExecutionResult.Error("operation failed") }
        )
    }

    @Test
    fun `delete and insert line tools surface callback exceptions as errors`(): Unit = runBlocking {
        val callbacks = RecordingFileSystemCallbacks(
            operationError = IllegalStateException("disk locked")
        )

        val results = listOf(
            DeleteFileTool.execute(
                toolCall(DeleteFileTool.name, """{"path":"a.cpp"}"""),
                fileSystemContext(callbacks)
            ),
            InsertLineTool.execute(
                toolCall(InsertLineTool.name, """{"path":"a.cpp","line_number":1,"content":"x"}"""),
                fileSystemContext(callbacks)
            ),
            DeleteLinesTool.execute(
                toolCall(DeleteLinesTool.name, """{"path":"a.cpp","start_line":1}"""),
                fileSystemContext(callbacks)
            )
        )

        results.forEach { result ->
            assertThat(result).isInstanceOf(ToolExecutionResult.Error::class.java)
            assertThat((result as ToolExecutionResult.Error).message).isNotEmpty()
        }
    }

    @Test
    fun `file system tools report missing callbacks consistently`(): Unit = runBlocking {
        val context = ToolExecutionContext()

        val results = listOf(
            ReadFileTool.execute(toolCall(ReadFileTool.name, """{"path":"a.cpp"}"""), context),
            WriteFileTool.execute(toolCall(WriteFileTool.name, """{"path":"a.cpp","content":"x"}"""), context),
            ListFilesTool.execute(toolCall(ListFilesTool.name, "{}"), context),
            DeleteFileTool.execute(toolCall(DeleteFileTool.name, """{"path":"a.cpp"}"""), context),
            CreateDirectoryTool.execute(toolCall(CreateDirectoryTool.name, """{"path":"generated"}"""), context),
            MoveFileTool.execute(toolCall(MoveFileTool.name, """{"source":"a.cpp","destination":"b.cpp"}"""), context),
            CopyFileTool.execute(toolCall(CopyFileTool.name, """{"source":"a.cpp","destination":"b.cpp"}"""), context),
            GetFileInfoTool.execute(toolCall(GetFileInfoTool.name, """{"path":"a.cpp"}"""), context),
            ReplaceTextTool.execute(toolCall(ReplaceTextTool.name, """{"path":"a.cpp","old_text":"a","new_text":"b"}"""), context),
            ReplaceLineTool.execute(toolCall(ReplaceLineTool.name, """{"path":"a.cpp","line_number":1,"new_content":"b"}"""), context),
            InsertLineTool.execute(toolCall(InsertLineTool.name, """{"path":"a.cpp","line_number":1,"content":"b"}"""), context),
            DeleteLinesTool.execute(toolCall(DeleteLinesTool.name, """{"path":"a.cpp","start_line":1}"""), context)
        )

        assertThat(results).containsExactlyElementsIn(
            List(results.size) { ToolExecutionResult.Error("File system callbacks not available") }
        )
    }

    @Test
    fun `file system tools validate additional invalid parameters`(): Unit = runBlocking {
        val callbacks = RecordingFileSystemCallbacks()

        assertThat(DeleteFileTool.execute(toolCall(DeleteFileTool.name, """{"path":" "}"""), fileSystemContext(callbacks)))
            .isEqualTo(ToolExecutionResult.Error("File path is required"))
        assertThat(CopyFileTool.execute(toolCall(CopyFileTool.name, """{"source":"a.cpp","destination":" "}"""), fileSystemContext(callbacks)))
            .isEqualTo(ToolExecutionResult.Error("Source and destination paths are required"))
        assertThat(GetFileInfoTool.execute(toolCall(GetFileInfoTool.name, """{"path":" "}"""), fileSystemContext(callbacks)))
            .isEqualTo(ToolExecutionResult.Error("File path is required"))
        assertThat(InsertLineTool.execute(toolCall(InsertLineTool.name, """{"path":"a.cpp","line_number":0,"content":"x"}"""), fileSystemContext(callbacks)))
            .isEqualTo(ToolExecutionResult.Error("Line number must be >= 1"))
        assertThat(DeleteLinesTool.execute(toolCall(DeleteLinesTool.name, """{"path":"a.cpp","start_line":0}"""), fileSystemContext(callbacks)))
            .isEqualTo(ToolExecutionResult.Error("Start line must be >= 1"))
    }
    private fun fileSystemContext(callbacks: RecordingFileSystemCallbacks): ToolExecutionContext = ToolExecutionContext(additionalData = mapOf("fileSystemCallbacks" to callbacks))
}
