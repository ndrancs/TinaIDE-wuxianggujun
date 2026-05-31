package com.wuxianggujun.tinaide.ui.compose.components

import com.google.common.truth.Truth.assertThat
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.rules.TestWatcher
import org.junit.runner.Description

class FileTreeStateTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun `toggleNode with stale node reference does not duplicate descendants`() = runTest {
        val projectDir = tempFolder.newFolder("project")
        val tinaideDir = File(projectDir, ".tinaide").apply { mkdirs() }
        File(tinaideDir, "project.json").writeText("{}")

        val state = FileTreeState()
        state.loadRoot(projectDir.absolutePath)

        val metadataDirNode = state.visibleNodes.first { it.absolutePath == tinaideDir.absolutePath }

        state.toggleNode(metadataDirNode.absolutePath)
        state.toggleNode(metadataDirNode.absolutePath)

        val duplicates = state.visibleNodes
            .groupingBy { it.absolutePath }
            .eachCount()
            .filterValues { it > 1 }

        assertThat(duplicates).isEmpty()
    }

    @Test
    fun `loadRoot resets visible nodes and selection for new project`() = runTest {
        val firstProject = tempFolder.newFolder("project-a")
        val secondProject = tempFolder.newFolder("project-b")
        val firstFile = File(firstProject, "main.cpp").apply { writeText("int main() {}") }
        val secondFile = File(secondProject, "app.kt").apply { writeText("fun main() = Unit") }

        val state = FileTreeState()
        state.loadRoot(firstProject.absolutePath)
        state.select(firstFile)

        state.loadRoot(secondProject.absolutePath)

        assertThat(state.rootPath).isEqualTo(secondProject.absolutePath)
        assertThat(state.uiState.value.selectedPath).isNull()
        assertThat(state.visibleNodes.map { it.absolutePath }).contains(secondFile.absolutePath)
        assertThat(state.visibleNodes.map { it.absolutePath }).doesNotContain(firstFile.absolutePath)
    }

    @Test
    fun `reveal refreshes expanded directory cache for newly created file`() = runTest {
        val projectDir = tempFolder.newFolder("project-reveal")
        val srcDir = File(projectDir, "src").apply { mkdirs() }

        val state = FileTreeState()
        state.loadRoot(projectDir.absolutePath)
        state.toggleNode(srcDir.absolutePath)

        val newFile = File(srcDir, "new_file.cpp").apply { writeText("int value = 1;") }

        state.reveal(newFile)

        assertThat(state.visibleNodes.map { it.absolutePath }).contains(newFile.absolutePath)
        assertThat(state.uiState.value.selectedPath).isEqualTo(newFile.absolutePath)
    }

    @Test
    fun `handleFileChanges adds created file into expanded directory`() = runTest {
        val projectDir = tempFolder.newFolder("project-watch-create")
        val srcDir = File(projectDir, "src").apply { mkdirs() }

        val state = FileTreeState()
        state.loadRoot(projectDir.absolutePath)
        state.toggleNode(srcDir.absolutePath)

        val createdFile = File(srcDir, "watch.cpp").apply { writeText("int watch = 7;") }

        state.handleFileChanges(listOf(pendingFileChange(createdFile, FileTreeState.FileChangeKind.CREATED)))

        assertThat(state.visibleNodes.map { it.absolutePath }).contains(createdFile.absolutePath)
    }

    @Test
    fun `handleFileChanges removes deleted file and clears selection`() = runTest {
        val projectDir = tempFolder.newFolder("project-watch-delete")
        val srcDir = File(projectDir, "src").apply { mkdirs() }
        val deletedFile = File(srcDir, "gone.cpp").apply { writeText("int gone = 0;") }

        val state = FileTreeState()
        state.loadRoot(projectDir.absolutePath)
        state.toggleNode(srcDir.absolutePath)
        state.select(deletedFile)

        deletedFile.delete()
        state.handleFileChanges(listOf(pendingFileChange(deletedFile, FileTreeState.FileChangeKind.DELETED)))

        assertThat(state.visibleNodes.map { it.absolutePath }).doesNotContain(deletedFile.absolutePath)
        assertThat(state.uiState.value.selectedPath).isNull()
    }

    @Test
    fun `background file change defers refresh until resume`() = runTest {
        val projectDir = tempFolder.newFolder("project-background-watch")
        val srcDir = File(projectDir, "src").apply { mkdirs() }

        val state = FileTreeState()
        state.loadRoot(projectDir.absolutePath)
        state.toggleNode(srcDir.absolutePath)
        state.setAppVisibility(false)

        val createdFile = File(srcDir, "background.cpp").apply { writeText("int background = 1;") }

        state.handleFileChanges(listOf(pendingFileChange(createdFile, FileTreeState.FileChangeKind.CREATED)))

        assertThat(state.visibleNodes.map { it.absolutePath }).doesNotContain(createdFile.absolutePath)
        assertThat(state.consumePendingResumeRefresh()).isTrue()

        state.setAppVisibility(true)
        state.refresh()

        assertThat(state.visibleNodes.map { it.absolutePath }).contains(createdFile.absolutePath)
        assertThat(state.consumePendingResumeRefresh()).isFalse()
    }

    @Test
    fun `reveal shows newly created direct child under root`() = runTest {
        val projectDir = tempFolder.newFolder("project-root-child")
        val srcDir = File(projectDir, "src").apply { mkdirs() }
        val nestedFile = File(srcDir, "keep.cpp").apply { writeText("int keep = 1;") }

        val state = FileTreeState()
        state.loadRoot(projectDir.absolutePath)
        state.toggleNode(srcDir.absolutePath)

        val readmeFile = File(projectDir, "README.md").apply { writeText("# title") }

        state.reveal(readmeFile)

        val visiblePaths = state.visibleNodes.map { it.absolutePath }
        assertThat(visiblePaths).contains(readmeFile.absolutePath)
        assertThat(visiblePaths).contains(nestedFile.absolutePath)
        assertThat(state.uiState.value.selectedPath).isEqualTo(readmeFile.absolutePath)
    }

    @Test
    fun `reveal incrementally expands newly visible directory chain`() = runTest {
        val projectDir = tempFolder.newFolder("project-reveal-chain")
        val srcDir = File(projectDir, "src").apply { mkdirs() }

        val state = FileTreeState()
        state.loadRoot(projectDir.absolutePath)
        state.toggleNode(srcDir.absolutePath)

        val generatedDir = File(srcDir, "generated").apply { mkdirs() }
        val generatedFile = File(generatedDir, "main.cpp").apply { writeText("int main() { return 0; }") }

        state.reveal(generatedFile)

        val visiblePaths = state.visibleNodes.map { it.absolutePath }
        assertThat(visiblePaths).contains(generatedDir.absolutePath)
        assertThat(visiblePaths).contains(generatedFile.absolutePath)
        assertThat(state.uiState.value.selectedPath).isEqualTo(generatedFile.absolutePath)
    }

    @Test
    fun `reopening collapsed parent refreshes expanded descendant caches`() = runTest {
        val projectDir = tempFolder.newFolder("project-nested-cache")
        val parentDir = File(projectDir, "parent").apply { mkdirs() }
        val childDir = File(parentDir, "child").apply { mkdirs() }

        val state = FileTreeState()
        state.loadRoot(projectDir.absolutePath)
        state.toggleNode(parentDir.absolutePath)
        state.toggleNode(childDir.absolutePath)
        state.toggleNode(parentDir.absolutePath)

        val nestedFile = File(childDir, "generated.cpp").apply { writeText("int generated = 42;") }

        state.toggleNode(parentDir.absolutePath)

        assertThat(state.visibleNodes.map { it.absolutePath }).contains(nestedFile.absolutePath)
    }

    private fun pendingFileChange(
        file: File,
        kind: FileTreeState.FileChangeKind
    ): FileTreeState.PendingFileChange = FileTreeState.PendingFileChange(file.absolutePath, kind)
}

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class MainDispatcherRule(
    private val dispatcher: TestDispatcher = UnconfinedTestDispatcher()
) : TestWatcher() {
    override fun starting(description: Description) {
        Dispatchers.setMain(dispatcher)
    }

    override fun finished(description: Description) {
        Dispatchers.resetMain()
    }
}
