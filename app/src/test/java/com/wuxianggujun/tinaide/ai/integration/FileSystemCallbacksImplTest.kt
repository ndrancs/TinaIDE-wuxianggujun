package com.wuxianggujun.tinaide.ai.integration

import android.content.Context
import com.google.common.truth.Truth.assertThat
import com.wuxianggujun.tinaide.ai.tools.executor.filesystem.DeleteFileRequest
import com.wuxianggujun.tinaide.ai.tools.executor.filesystem.MoveFileRequest
import com.wuxianggujun.tinaide.file.IFileOperations
import com.wuxianggujun.tinaide.ui.compose.state.editor.EditorContainerState
import io.mockk.mockk
import io.mockk.verify
import java.io.File
import java.nio.file.Files
import org.junit.After
import org.junit.Before
import org.junit.Test

class FileSystemCallbacksImplTest {

    private lateinit var projectRoot: File
    private lateinit var editorState: EditorContainerState
    private lateinit var fileOperations: RecordingFileOperations
    private lateinit var callbacks: FileSystemCallbacksImpl

    @Before
    fun setUp() {
        projectRoot = Files.createTempDirectory("tina-ai-fs").toFile()
        editorState = mockk(relaxed = true)
        fileOperations = RecordingFileOperations()
        callbacks = FileSystemCallbacksImpl(
            context = mockk<Context>(relaxed = true),
            projectRoot = projectRoot.absolutePath,
            editorState = editorState,
            fileOperations = fileOperations
        )
    }

    @After
    fun tearDown() {
        projectRoot.deleteRecursively()
    }

    @Test
    fun deleteFile_shouldDelegateToFileOperationsAndSyncEditor() {
        val file = File(projectRoot, "main.cpp").apply { writeText("int main() {}") }

        val result = callbacks.deleteFile(DeleteFileRequest(path = "main.cpp", recursive = false))

        assertThat(result.success).isTrue()
        assertThat(fileOperations.deletedFile.normalizedPath()).isEqualTo(file.normalizedPath())
        assertThat(fileOperations.deletedRecursive).isFalse()
        verify {
            editorState.closeTabsForDeletedPath(
                match { it.normalizedPath() == file.normalizedPath() }
            )
        }
    }

    @Test
    fun deleteFile_shouldRejectDirectoryWithoutRecursiveFlagBeforeDelegating() {
        File(projectRoot, "src").mkdir()

        val result = callbacks.deleteFile(DeleteFileRequest(path = "src", recursive = false))

        assertThat(result.success).isFalse()
        assertThat(fileOperations.deletedFile).isNull()
        verify(exactly = 0) {
            editorState.closeTabsForDeletedPath(any())
        }
    }

    @Test
    fun moveFile_shouldDelegateToFileOperationsAndSyncEditor() {
        val source = File(projectRoot, "old.cpp").apply { writeText("old") }
        val destination = File(projectRoot, "new.cpp")

        val result = callbacks.moveFile(
            MoveFileRequest(
                source = "old.cpp",
                destination = "new.cpp",
                overwrite = true
            )
        )

        assertThat(result.success).isTrue()
        assertThat(fileOperations.movedSource.normalizedPath()).isEqualTo(source.normalizedPath())
        assertThat(fileOperations.movedDestination.normalizedPath()).isEqualTo(destination.normalizedPath())
        assertThat(fileOperations.movedOverwrite).isTrue()
        verify {
            editorState.syncTabsForMovedPath(
                oldPath = match { it.normalizedPath() == source.normalizedPath() },
                newPath = match { it.normalizedPath() == destination.normalizedPath() }
            )
        }
    }

    private fun File?.normalizedPath(): String? = this?.canonicalFile?.absolutePath

    private class RecordingFileOperations : IFileOperations {
        var deletedFile: File? = null
            private set
        var deletedRecursive: Boolean? = null
            private set
        var movedSource: File? = null
            private set
        var movedDestination: File? = null
            private set
        var movedOverwrite: Boolean? = null
            private set

        override fun createFile(parent: File, name: String): File = File(parent, name)

        override fun createDirectory(parent: File, name: String): File = File(parent, name)

        override fun deleteFile(file: File): Boolean {
            deletedFile = file
            return true
        }

        override fun deleteFile(file: File, recursive: Boolean): Boolean {
            deletedFile = file
            deletedRecursive = recursive
            return true
        }

        override fun renameFile(file: File, newName: String): Boolean = true

        override fun copyFile(source: File, destination: File): Boolean = true

        override fun moveFile(source: File, destination: File): Boolean {
            movedSource = source
            movedDestination = destination
            return true
        }

        override fun moveFile(source: File, destination: File, overwrite: Boolean): Boolean {
            movedSource = source
            movedDestination = destination
            movedOverwrite = overwrite
            return true
        }

        override fun searchFiles(query: String): List<File> = emptyList()
    }
}
