package com.wuxianggujun.tinaide.core.treesitter

import com.google.common.truth.Truth.assertThat
import com.wuxianggujun.tinaide.core.textengine.TextChange
import org.junit.Test

class TreeSitterIncrementalSupportTest {

    @Test
    fun buildLineStartOffsets_shouldHandleEmptyAndTrailingNewline() {
        assertThat(buildLineStartOffsets("")).asList().containsExactly(0)
        assertThat(buildLineStartOffsets("ab\ncd\n")).asList().containsExactly(0, 3, 6).inOrder()
    }

    @Test
    fun applyTextChange_shouldShiftWholeLineDeletionWithoutDroppingMovedLine() {
        val change = TextChange(
            startOffset = 2,
            endOffset = 4,
            oldText = "b\n",
            newText = "",
            startLine = 1,
            startColumn = 0,
            endLine = 2,
            endColumn = 0
        )

        val updated = HighlightLineCacheUpdater.applyTextChange(
            cache = linkedMapOf(
                0 to listOf(HighlightLineSegment(0, 1, HighlightType.KEYWORD)),
                1 to listOf(HighlightLineSegment(0, 1, HighlightType.STRING)),
                2 to listOf(HighlightLineSegment(0, 1, HighlightType.TYPE))
            ),
            change = HighlightLineCacheChange.from(change)
        )

        assertThat(updated.keys).containsExactly(0, 1).inOrder()
        assertThat(updated[1]).isEqualTo(listOf(HighlightLineSegment(0, 1, HighlightType.TYPE)))
    }

    @Test
    fun applyTextChange_shouldKeepStaleFallbackWhenJoiningLinesByDeletingNewline() {
        // 多行编辑（删除换行）：dirty 行 0/1 会被标 dirty。
        // 新行为：保留旧 segments 做"过期色"兜底；被移位覆盖的新行号让移位结果赢。
        val change = TextChange(
            startOffset = 1,
            endOffset = 2,
            oldText = "\n",
            newText = "",
            startLine = 0,
            startColumn = 1,
            endLine = 1,
            endColumn = 0
        )

        val updated = HighlightLineCacheUpdater.applyTextChange(
            cache = linkedMapOf(
                0 to listOf(HighlightLineSegment(0, 1, HighlightType.KEYWORD)),
                1 to listOf(HighlightLineSegment(0, 1, HighlightType.STRING)),
                2 to listOf(HighlightLineSegment(0, 1, HighlightType.TYPE))
            ),
            change = HighlightLineCacheChange.from(change)
        )

        // 新行 1：旧 line 2 移位占位（TYPE）。
        // 新行 0：dirty fallback 用旧 line 0 的 KEYWORD 顶着。
        assertThat(updated.keys).containsExactly(0, 1)
        assertThat(updated[0]).isEqualTo(listOf(HighlightLineSegment(0, 1, HighlightType.KEYWORD)))
        assertThat(updated[1]).isEqualTo(listOf(HighlightLineSegment(0, 1, HighlightType.TYPE)))
    }

    @Test
    fun applyTextChange_shouldKeepStaleFallbackOnInsertedLine() {
        // 多行编辑（插入换行）：dirty = 旧 line 0；新 line 1 由旧 line 1 移位占位。
        // 新行为：dirty 行保留旧 KEYWORD 做过期色兜底，避免 parse 前闪默认色。
        val change = TextChange(
            startOffset = 1,
            endOffset = 1,
            oldText = "",
            newText = "\n",
            startLine = 0,
            startColumn = 1,
            endLine = 0,
            endColumn = 1
        )

        val updated = HighlightLineCacheUpdater.applyTextChange(
            cache = linkedMapOf(
                0 to listOf(HighlightLineSegment(0, 1, HighlightType.KEYWORD)),
                1 to listOf(HighlightLineSegment(0, 1, HighlightType.STRING))
            ),
            change = HighlightLineCacheChange.from(change)
        )

        assertThat(updated.keys).containsExactly(0, 2)
        assertThat(updated[0]).isEqualTo(listOf(HighlightLineSegment(0, 1, HighlightType.KEYWORD)))
        assertThat(updated[2]).isEqualTo(listOf(HighlightLineSegment(0, 1, HighlightType.STRING)))
    }

    @Test
    fun applyTextChange_singleLineInsertion_shouldShiftSegmentsAfterCaret() {
        // Type "X" on line 1 at column 3: "abc|def" → "abcX|def"
        val change = TextChange(
            startOffset = 8,
            endOffset = 8,
            oldText = "",
            newText = "X",
            startLine = 1,
            startColumn = 3,
            endLine = 1,
            endColumn = 3
        )

        val updated = HighlightLineCacheUpdater.applyTextChange(
            cache = linkedMapOf(
                1 to listOf(
                    HighlightLineSegment(0, 3, HighlightType.KEYWORD), // "abc"
                    HighlightLineSegment(3, 6, HighlightType.VARIABLE) // "def"
                )
            ),
            change = HighlightLineCacheChange.from(change)
        )

        assertThat(updated.keys).containsExactly(1)
        assertThat(updated[1]).containsExactly(
            HighlightLineSegment(0, 3, HighlightType.KEYWORD),
            HighlightLineSegment(4, 7, HighlightType.VARIABLE)
        ).inOrder()
    }

    @Test
    fun applyTextChange_singleLineInsertion_shouldExtendSegmentThatStraddlesCaret() {
        // Type "X" at col 2 inside a 4-char identifier at cols 0..4
        val change = TextChange(
            startOffset = 2,
            endOffset = 2,
            oldText = "",
            newText = "X",
            startLine = 0,
            startColumn = 2,
            endLine = 0,
            endColumn = 2
        )

        val updated = HighlightLineCacheUpdater.applyTextChange(
            cache = linkedMapOf(
                0 to listOf(HighlightLineSegment(0, 4, HighlightType.VARIABLE))
            ),
            change = HighlightLineCacheChange.from(change)
        )

        assertThat(updated[0]).containsExactly(
            HighlightLineSegment(0, 5, HighlightType.VARIABLE)
        )
    }

    @Test
    fun applyTextChange_singleLineDeletion_shouldDropSegmentsWithinDeletedRange() {
        // Delete cols 2..5 on line 0
        val change = TextChange(
            startOffset = 2,
            endOffset = 5,
            oldText = "abc",
            newText = "",
            startLine = 0,
            startColumn = 2,
            endLine = 0,
            endColumn = 5
        )

        val updated = HighlightLineCacheUpdater.applyTextChange(
            cache = linkedMapOf(
                0 to listOf(
                    HighlightLineSegment(0, 2, HighlightType.KEYWORD),
                    HighlightLineSegment(2, 5, HighlightType.STRING),
                    HighlightLineSegment(5, 8, HighlightType.VARIABLE)
                )
            ),
            change = HighlightLineCacheChange.from(change)
        )

        assertThat(updated[0]).containsExactly(
            HighlightLineSegment(0, 2, HighlightType.KEYWORD),
            HighlightLineSegment(2, 5, HighlightType.VARIABLE)
        ).inOrder()
    }

    @Test
    fun toTsInputEdit_shouldComputeUtf16BytePositionsForInsertion() {
        val change = TextChange(
            startOffset = 4,
            endOffset = 4,
            oldText = "",
            newText = "xyz",
            startLine = 1,
            startColumn = 2,
            endLine = 1,
            endColumn = 2
        )

        val edit = change.toTsInputEdit()

        assertThat(edit.startByte).isEqualTo(8)
        assertThat(edit.oldEndByte).isEqualTo(8)
        assertThat(edit.newEndByte).isEqualTo(14)
        assertThat(edit.startPoint.row).isEqualTo(1)
        assertThat(edit.startPoint.column).isEqualTo(4)
        assertThat(edit.newEndPoint.row).isEqualTo(1)
        assertThat(edit.newEndPoint.column).isEqualTo(10)
    }
}
