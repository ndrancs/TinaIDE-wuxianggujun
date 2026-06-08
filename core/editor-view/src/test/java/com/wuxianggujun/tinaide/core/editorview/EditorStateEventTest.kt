package com.wuxianggujun.tinaide.core.editorview

import com.google.common.truth.Truth.assertThat
import com.wuxianggujun.tinaide.core.textengine.RopeTextBuffer
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class EditorStateEventTest {

    @Test
    fun updateFocus_shouldEmitFocusChangedEvent() = runTest {
        val state = EditorState(RopeTextBuffer())
        val events = mutableListOf<EditorEvent>()
        val job = launch(start = CoroutineStart.UNDISPATCHED) {
            state.events.collect { event ->
                events += event
            }
        }

        state.updateFocus(true)
        advanceUntilIdle()

        assertThat(events).hasSize(1)
        assertThat(events.first()).isEqualTo(EditorEvent.FocusChanged(true))
        job.cancel()
    }

    @Test
    fun replaceRange_shouldEmitTextChangedEvent() = runTest {
        val buffer = RopeTextBuffer()
        buffer.insert(0, "abc")
        val state = EditorState(buffer)
        val events = mutableListOf<EditorEvent>()
        val job = launch(start = CoroutineStart.UNDISPATCHED) {
            state.events.collect { event ->
                events += event
            }
        }

        state.moveCursorTo(1)
        state.replaceRange(startOffset = 1, endOffset = 2, replacement = "XYZ")
        advanceUntilIdle()

        val textChanged = events.filterIsInstance<EditorEvent.TextChanged>().firstOrNull()
        assertThat(textChanged).isNotNull()
        assertThat(textChanged!!.reason).isEqualTo("replaceRange")
        assertThat(textChanged.length).isEqualTo(state.textBuffer.length)
        job.cancel()
    }

    @Test
    fun moveCursorTo_shouldEmitCursorMovedEvent() = runTest {
        val buffer = RopeTextBuffer()
        buffer.insert(0, "abcdef")
        val state = EditorState(buffer)
        state.updateMetrics(
            lineHeightPx = 1f,
            charWidthPx = 1f,
            viewportHeightPx = 100f,
            viewportWidthPx = 100f,
            contentStartXPx = 0f
        )
        val events = mutableListOf<EditorEvent>()
        val job = launch(start = CoroutineStart.UNDISPATCHED) {
            state.events.collect { event ->
                events += event
            }
        }

        state.moveCursorTo(3)
        advanceUntilIdle()

        val cursorMoved = events.filterIsInstance<EditorEvent.CursorMoved>()
        assertThat(cursorMoved).containsExactly(
            EditorEvent.CursorMoved(
                oldOffset = 0,
                newOffset = 3
            )
        )
        job.cancel()
    }

    @Test
    fun selectRange_shouldEmitSelectionChangedEvent() = runTest {
        val buffer = RopeTextBuffer()
        buffer.insert(0, "abcdef")
        val state = EditorState(buffer)
        state.updateMetrics(
            lineHeightPx = 1f,
            charWidthPx = 1f,
            viewportHeightPx = 100f,
            viewportWidthPx = 100f,
            contentStartXPx = 0f
        )
        val events = mutableListOf<EditorEvent>()
        val job = launch(start = CoroutineStart.UNDISPATCHED) {
            state.events.collect { event ->
                events += event
            }
        }

        state.selectRange(startOffset = 1, endOffset = 4)
        advanceUntilIdle()

        val selectionChanged = events.filterIsInstance<EditorEvent.SelectionChanged>()
        assertThat(selectionChanged).containsExactly(
            EditorEvent.SelectionChanged(
                OffsetRange(1, 4)
            )
        )
        job.cancel()
    }

    @Test
    fun scrollBy_shouldEmitScrollChangedEvent() = runTest {
        val buffer = RopeTextBuffer()
        val lines = buildString {
            repeat(50) { index ->
                append("line$index")
                if (index < 49) append('\n')
            }
        }
        buffer.insert(0, lines)
        val state = EditorState(buffer)
        state.updateMetrics(
            lineHeightPx = 1f,
            charWidthPx = 1f,
            viewportHeightPx = 10f,
            viewportWidthPx = 100f,
            contentStartXPx = 0f
        )
        val events = mutableListOf<EditorEvent>()
        val job = launch(start = CoroutineStart.UNDISPATCHED) {
            state.events.collect { event ->
                events += event
            }
        }

        state.scrollBy(5f)
        advanceUntilIdle()

        val scrollChanged = events.filterIsInstance<EditorEvent.ScrollChanged>().firstOrNull()
        assertThat(scrollChanged).isNotNull()
        assertThat(scrollChanged!!.offsetY).isGreaterThan(0f)
        job.cancel()
    }

    @Test
    fun gotoLine_shouldClampOutOfRangeLineAndColumn() {
        val buffer = RopeTextBuffer("a\nbc")
        val state = EditorState(buffer)

        state.gotoLine(line = 396, column = 99)

        val cursor = state.cursorPosition
        assertThat(cursor.line).isEqualTo(1)
        assertThat(cursor.column).isEqualTo(2)
    }
}
