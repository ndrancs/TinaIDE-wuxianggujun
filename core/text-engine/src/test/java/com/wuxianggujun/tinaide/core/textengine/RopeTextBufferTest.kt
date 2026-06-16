package com.wuxianggujun.tinaide.core.textengine

import com.google.common.truth.Truth.assertThat
import java.io.File
import kotlinx.coroutines.test.runTest
import org.junit.Test

class RopeTextBufferTest {

    @Test
    fun lineApiShouldWork() {
        val buffer = RopeTextBuffer()
        buffer.insert(0, "hello\nworld")

        assertThat(buffer.lineCount).isEqualTo(2)
        assertThat(buffer.getLine(0)).isEqualTo("hello")
        assertThat(buffer.getLine(1)).isEqualTo("world")
        assertThat(buffer.offsetToLine(7)).isEqualTo(1)
    }

    @Test
    fun undoRedoShouldWork() {
        val buffer = RopeTextBuffer()
        buffer.insert(0, "abc")
        buffer.insert(3, "def")

        assertThat(buffer.toString()).isEqualTo("abcdef")
        // 连续相邻 Insert 会被 EditHistory 合并为一条 —— 一次 undo 直接回到空串，对齐 IntelliJ/Sora 的直觉。
        assertThat(buffer.undo()).isNotNull()
        assertThat(buffer.toString()).isEqualTo("")
        assertThat(buffer.redo()).isNotNull()
        assertThat(buffer.toString()).isEqualTo("abcdef")
    }

    @Test
    fun undo_shouldReturnAppliedTextChange() {
        val buffer = RopeTextBuffer("abc")
        buffer.insert(1, "XYZ")

        val change = buffer.undo()

        assertThat(change).isNotNull()
        assertThat(change!!.startOffset).isEqualTo(1)
        assertThat(change.oldText).isEqualTo("XYZ")
        assertThat(change.newText).isEqualTo("")
        assertThat(change.fromUndoRedo).isTrue()
        assertThat(buffer.toString()).isEqualTo("abc")
    }

    @Test
    fun redo_shouldReturnAppliedTextChange() {
        val buffer = RopeTextBuffer("abc")
        buffer.insert(1, "XYZ")
        buffer.undo()

        val change = buffer.redo()

        assertThat(change).isNotNull()
        assertThat(change!!.startOffset).isEqualTo(1)
        assertThat(change.oldText).isEqualTo("")
        assertThat(change.newText).isEqualTo("XYZ")
        assertThat(change.fromUndoRedo).isTrue()
        assertThat(buffer.toString()).isEqualTo("aXYZbc")
    }

    @Test
    fun undo_shouldNotMergeAcrossNewline() {
        val buffer = RopeTextBuffer()
        buffer.insert(0, "abc")
        buffer.insert(3, "\n")
        buffer.insert(4, "def")

        assertThat(buffer.toString()).isEqualTo("abc\ndef")
        // 回车是合并边界：undo 第一次应该回到换行前的状态。
        assertThat(buffer.undo()).isNotNull()
        assertThat(buffer.toString()).isEqualTo("abc\n")
        assertThat(buffer.undo()).isNotNull()
        assertThat(buffer.toString()).isEqualTo("abc")
        assertThat(buffer.undo()).isNotNull()
        assertThat(buffer.toString()).isEqualTo("")
    }

    @Test
    fun charAt_shouldReturnSingleCharacterWithoutSubstringAllocation() {
        val buffer = RopeTextBuffer("ab\ncd")

        assertThat(buffer.charAt(0)).isEqualTo('a')
        assertThat(buffer.charAt(2)).isEqualTo('\n')
        assertThat(buffer.charAt(4)).isEqualTo('d')
        assertThat(buffer.charAt(-1)).isNull()
        assertThat(buffer.charAt(buffer.length)).isNull()
    }

    @Test
    fun loadAndSaveShouldWork() = runTest {
        val tempDir = createTempDir(prefix = "tina-text-engine-")
        val source = File(tempDir, "in.txt").apply { writeText("a\nb\nc") }
        val target = File(tempDir, "out.txt")

        val buffer = RopeTextBuffer()
        val load = buffer.loadFromFile(source)
        assertThat(load.isSuccess).isTrue()
        assertThat(buffer.lineCount).isEqualTo(3)

        buffer.insert(buffer.length, "\nend")
        val save = buffer.saveToFile(target)
        assertThat(save.isSuccess).isTrue()
        assertThat(target.readText()).isEqualTo("a\nb\nc\nend")
    }

    @Test
    fun replaceAll_shouldReportOldRangeUsingPreviousLineIndex() {
        val buffer = RopeTextBuffer("ab\ncde")
        var captured: TextChange? = null
        buffer.addChangeListener { change ->
            captured = change
        }

        buffer.replaceAll("x\n")

        assertThat(captured).isNotNull()
        assertThat(captured!!.startOffset).isEqualTo(0)
        assertThat(captured!!.endOffset).isEqualTo(6)
        assertThat(captured!!.startLine).isEqualTo(0)
        assertThat(captured!!.startColumn).isEqualTo(0)
        assertThat(captured!!.endLine).isEqualTo(1)
        assertThat(captured!!.endColumn).isEqualTo(3)
    }
}
