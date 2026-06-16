package com.wuxianggujun.tinaide.core.editorview

import com.google.common.truth.Truth.assertThat
import com.wuxianggujun.tinaide.core.textengine.RopeTextBuffer
import org.junit.Test

class EditorStateWordWrapTest {

    @Test
    fun visualLineMap_shouldMatchWrapLayoutAcrossRepresentativeInputs() {
        val cases = listOf(
            WrapCase(lineText = "", wrapColumns = 4, tabSize = 4),
            WrapCase(lineText = "abcd", wrapColumns = 4, tabSize = 4),
            WrapCase(lineText = "abcde", wrapColumns = 4, tabSize = 4),
            WrapCase(lineText = "ab\tcd", wrapColumns = 4, tabSize = 4),
            WrapCase(lineText = "\t\tab", wrapColumns = 4, tabSize = 4),
            WrapCase(lineText = "a😀bc", wrapColumns = 4, tabSize = 4)
        )

        cases.forEach { case ->
            val buffer = RopeTextBuffer().apply {
                if (case.lineText.isNotEmpty()) {
                    insert(0, case.lineText)
                }
            }
            val state = createWordWrapState(buffer, case.wrapColumns, case.tabSize)
            val wrapLayout = EditorWordWrapLayoutCache().getWrapLayout(
                line = 0,
                lineText = case.lineText,
                textVersion = buffer.version,
                wrapColumns = case.wrapColumns,
                tabSize = case.tabSize
            )

            assertThat(state.visualLineCount()).isEqualTo(wrapLayout.segmentCount)
            repeat(wrapLayout.segmentCount) { segmentIndex ->
                assertThat(state.visualLineStartColumn(segmentIndex))
                    .isEqualTo(wrapLayout.startColumnForSegment(segmentIndex))
                assertThat(state.visualLineEndColumn(segmentIndex))
                    .isEqualTo(wrapLayout.endColumnForSegment(segmentIndex))
                assertThat(
                    state.visualLineForPosition(
                        line = 0,
                        column = wrapLayout.startColumnForSegment(segmentIndex)
                    )
                ).isEqualTo(segmentIndex)
            }
        }
    }

    @Test
    fun visualLineCount_shouldAccumulateSegmentCountsAcrossLines() {
        val lines = listOf("ab\tcd", "12345", "😀xy")
        val wrapColumns = 4
        val tabSize = 4
        val content = lines.joinToString("\n")
        val buffer = RopeTextBuffer().apply { insert(0, content) }
        val state = createWordWrapState(buffer, wrapColumns, tabSize)
        val cache = EditorWordWrapLayoutCache()

        val expectedVisualLines = lines.mapIndexed { index, lineText ->
            cache.getWrapLayout(
                line = index,
                lineText = lineText,
                textVersion = buffer.version,
                wrapColumns = wrapColumns,
                tabSize = tabSize
            ).segmentCount
        }.sum()

        assertThat(state.visualLineCount()).isEqualTo(expectedVisualLines)
    }

    private fun createWordWrapState(
        buffer: RopeTextBuffer,
        wrapColumns: Int,
        tabSize: Int
    ): EditorState = EditorState(
        textBuffer = buffer,
        config = EditorConfig(
            wordWrap = true,
            codeFolding = false,
            tabSize = tabSize
        )
    ).apply {
        updateMetrics(
            lineHeightPx = 1f,
            charWidthPx = 1f,
            viewportHeightPx = 100f,
            viewportWidthPx = wrapColumns.toFloat(),
            contentStartXPx = 0f
        )
    }

    private data class WrapCase(
        val lineText: String,
        val wrapColumns: Int,
        val tabSize: Int
    )
}
