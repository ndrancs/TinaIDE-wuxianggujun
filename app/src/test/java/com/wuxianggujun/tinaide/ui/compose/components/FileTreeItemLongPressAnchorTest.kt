package com.wuxianggujun.tinaide.ui.compose.components

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class FileTreeItemLongPressAnchorTest {

    @Test
    fun resolveFileTreeItemLongPressAnchor_shouldUsePointerInsideRow() {
        val rowBounds = Rect(
            left = 0f,
            top = 40f,
            right = 320f,
            bottom = 88f
        )
        val pointer = Offset(220f, 64f)

        val anchor = resolveFileTreeItemLongPressAnchor(
            boundsInRoot = rowBounds,
            pointerInRoot = pointer
        )

        assertThat(anchor).isEqualTo(pointer)
    }

    @Test
    fun resolveFileTreeItemLongPressAnchor_shouldFallbackToRowBottomCenter() {
        val rowBounds = Rect(
            left = 0f,
            top = 40f,
            right = 320f,
            bottom = 88f
        )

        val anchor = resolveFileTreeItemLongPressAnchor(
            boundsInRoot = rowBounds,
            pointerInRoot = null
        )

        assertThat(anchor).isEqualTo(Offset(160f, 88f))
    }
}
