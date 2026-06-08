package com.wuxianggujun.tinaide.editor.session

import com.google.common.truth.Truth.assertThat
import java.io.File
import java.nio.charset.Charset
import java.nio.file.Files
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class DocumentSessionTest {

    @Test
    fun reloadFromDisk_shouldRefreshUndoRedoAndCharset() {
        val gbk = Charset.forName("GBK")
        val file = Files.createTempFile("document-session-reload", ".txt").toFile()
        file.writeText("中文内容", gbk)
        val session = createSession(file)
        val binding = FakeEditorBinding(
            text = "stale",
            canUndo = true,
            canRedo = true
        )

        try {
            session.attachEditor(binding)

            val reloaded = session.reloadFromDisk()

            assertThat(reloaded).isTrue()
            assertThat(binding.readText()).isEqualTo("中文内容")
            assertThat(session.state.value.canUndo).isFalse()
            assertThat(session.state.value.canRedo).isFalse()
            assertThat(session.state.value.charsetName).isEqualTo(gbk.name())
        } finally {
            session.stopFileWatcher()
            file.delete()
        }
    }

    @Test
    fun save_shouldPreserveDetectedCharset() = runTest {
        val gbk = Charset.forName("GBK")
        val file = Files.createTempFile("document-session-save", ".txt").toFile()
        file.writeText("旧内容", gbk)
        val session = createSession(file, this)
        val binding = FakeEditorBinding(
            text = "新的中文内容",
            canUndo = true,
            canRedo = false
        )

        try {
            session.attachEditor(binding)

            val result = session.save(SaveReason.MANUAL)

            assertThat(result).isInstanceOf(SaveResult.Success::class.java)
            assertThat(file.readBytes()).isEqualTo("新的中文内容".toByteArray(gbk))
            assertThat(session.state.value.charsetName).isEqualTo(gbk.name())
        } finally {
            session.stopFileWatcher()
            file.delete()
        }
    }

    private fun createSession(
        file: File,
        scope: CoroutineScope = TestScope(StandardTestDispatcher())
    ): DocumentSession = DocumentSession(
        context = RuntimeEnvironment.getApplication(),
        tabId = "tab-id",
        file = file,
        coroutineScope = scope
    )

    private class FakeEditorBinding(
        text: String,
        private var canUndo: Boolean,
        private var canRedo: Boolean
    ) : DocumentSession.EditorBinding {
        private var currentText = text
        private var version = 0L

        override fun readText(): String = currentText

        override fun setText(text: CharSequence) {
            currentText = text.toString()
            version++
            canUndo = false
            canRedo = false
        }

        override fun textLength(): Int = currentText.length

        override fun canUndo(): Boolean = canUndo

        override fun canRedo(): Boolean = canRedo

        override fun undo() = Unit

        override fun redo() = Unit

        override fun currentDocumentVersion(): Long = version
    }
}
