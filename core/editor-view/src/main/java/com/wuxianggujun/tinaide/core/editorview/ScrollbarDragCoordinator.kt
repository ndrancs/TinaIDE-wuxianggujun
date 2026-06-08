package com.wuxianggujun.tinaide.core.editorview

import android.os.SystemClock
import android.view.ViewConfiguration
import androidx.compose.ui.input.pointer.AwaitPointerEventScope
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.unit.Density
import kotlin.coroutines.cancellation.CancellationException
import kotlin.math.abs
import kotlin.math.sqrt
import kotlinx.coroutines.withTimeoutOrNull

internal data class ActiveScrollbarDrag(
    val pointerId: PointerId,
    val axis: ScrollbarAxis
)

internal class ScrollbarDragCoordinator(
    private val state: EditorState,
    private val scrollbarRenderer: EditorScrollbarRenderer,
    private val scrollGestureCoordinator: EditorScrollGestureCoordinator,
    private val gestureHandler: EditorGestureHandler,
    private val onActiveDragChanged: (ActiveScrollbarDrag?) -> Unit,
    private val onContextMenuVisibilityChanged: (Boolean) -> Unit,
    private val onTriggerScrollbarVisibility: (Boolean) -> Unit,
    private val cancelPendingCompletionRequest: () -> Unit,
    private val logEditorTouch: (String, Boolean) -> Unit
) {
    private fun logTouch(message: String, verbose: Boolean = false) {
        logEditorTouch(message, verbose)
    }

    suspend fun AwaitPointerEventScope.runDragLoop(
        canvasWidthPxProvider: () -> Float,
        canvasHeightPxProvider: () -> Float,
        touchSlopPx: Float,
        density: Density
    ) {
        try {
            while (true) {
                val down = awaitFirstPointerDown(pass = PointerEventPass.Initial)
                val canvasWidthPx = canvasWidthPxProvider()
                val canvasHeightPx = canvasHeightPxProvider()
                val layout = scrollbarRenderer.calculateLayout(
                    state = state,
                    canvasWidth = canvasWidthPx,
                    canvasHeight = canvasHeightPx,
                    density = density
                )
                val dragTarget = layout.hitTest(down.position) ?: continue

                // 这里采用“长按后再拖动”的策略：
                // - 避免误触（右侧靠近文本时容易碰到滚动条）
                // - 避免与文本长按选词/菜单冲突（通过 suppressBasicGestures 抑制一小段时间）
                val longPressTimeoutMs = ViewConfiguration.getLongPressTimeout().toLong().coerceAtLeast(120L)
                gestureHandler.suppressBasicGestures(durationMs = longPressTimeoutMs + 180L)
                logTouch(
                    "scrollbar pressCandidate axis=${dragTarget.axis} pointer=(${down.position.x.toInt()},${down.position.y.toInt()})",
                    verbose = false
                )

                var lastPosition = down.position
                var cancelReason: String? = null
                // 手指静止判定对“滚动条长按”要更宽松：
                // 1) 屏幕边缘区域容易被系统手势策略影响
                // 2) 用户手指自然抖动在 1x touchSlop 内很常见
                val longPressMoveSlopPx = touchSlopPx * 1.7f
                val longPressMoveSlopSquared = longPressMoveSlopPx * longPressMoveSlopPx
                val longPressed = withTimeoutOrNull(longPressTimeoutMs) {
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        val change = event.changes.firstOrNull { it.id == down.id } ?: run {
                            cancelReason = "pointerMissing"
                            return@withTimeoutOrNull false
                        }
                        lastPosition = change.position
                        if (!change.pressed) {
                            cancelReason = "pointerUpBeforeLongPress"
                            return@withTimeoutOrNull false
                        }

                        val dx = change.position.x - down.position.x
                        val dy = change.position.y - down.position.y
                        if (dx * dx + dy * dy > longPressMoveSlopSquared) {
                            // 手指已经明显移动，交还给常规滚动手势（scrollable/fling）。
                            cancelReason = "movedBeforeLongPress dist=${"%.1f".format(sqrt(dx * dx + dy * dy))} slop=${"%.1f".format(longPressMoveSlopPx)}"
                            return@withTimeoutOrNull false
                        }
                    }
                } == null

                if (!longPressed) {
                    logTouch(
                        "scrollbar longPressRejected axis=${dragTarget.axis} reason=${cancelReason ?: "unknown"} " +
                            "pointer=(${lastPosition.x.toInt()},${lastPosition.y.toInt()})",
                        verbose = false
                    )
                    continue
                }
                logTouch(
                    "scrollbar longPressAccepted axis=${dragTarget.axis} pointer=(${lastPosition.x.toInt()},${lastPosition.y.toInt()})",
                    verbose = false
                )

                val drag = ActiveScrollbarDrag(
                    pointerId = down.id,
                    axis = dragTarget.axis
                )
                onActiveDragChanged(drag)
                scrollGestureCoordinator.onScrollbarDragStarted(drag.axis)
                onContextMenuVisibilityChanged(false)
                onTriggerScrollbarVisibility(true)
                cancelPendingCompletionRequest()
                logTouch(
                    "scrollbar dragStart axis=${drag.axis} fromThumb=${dragTarget.hitOnThumb} " +
                        "pointer=(${lastPosition.x.toInt()},${lastPosition.y.toInt()}) " +
                        "offset=(${state.scrollOffsetXPx.toInt()},${state.scrollOffsetPx.toInt()})"
                )
                down.consume()

                val dragGeometry = when (drag.axis) {
                    ScrollbarAxis.Vertical -> layout.vertical
                    ScrollbarAxis.Horizontal -> layout.horizontal
                }
                val movableTrackPx = dragGeometry?.let { geometry ->
                    (geometry.trackLengthPx - geometry.thumbLengthPx).coerceAtLeast(1f)
                } ?: 0f
                val trackStartPx = dragGeometry?.trackStartPx ?: 0f
                val pointerAxisAtDown = when (drag.axis) {
                    ScrollbarAxis.Vertical -> lastPosition.y
                    ScrollbarAxis.Horizontal -> lastPosition.x
                }
                val dragOffsetInThumbPx = if (dragGeometry != null) {
                    (pointerAxisAtDown - dragGeometry.thumbStartPx)
                        .coerceIn(0f, dragGeometry.thumbLengthPx)
                } else {
                    0f
                }
                val canMapPointerToScroll = (
                    dragGeometry != null &&
                        dragGeometry.maxScrollOffsetPx > 0f &&
                        movableTrackPx > 0f
                    )
                var accumulatedConsumedPx = 0f
                var verboseLogAtMs = 0L
                var dragFinished = false

                fun scrollFromPointer(pointerAxis: Float) {
                    val geometry = dragGeometry ?: return
                    if (!canMapPointerToScroll) return
                    val desiredThumbStart = (pointerAxis - dragOffsetInThumbPx)
                        .coerceIn(trackStartPx, trackStartPx + movableTrackPx)
                    val progress = ((desiredThumbStart - trackStartPx) / movableTrackPx)
                        .coerceIn(0f, 1f)
                    val targetOffset = progress * geometry.maxScrollOffsetPx
                    val before = when (drag.axis) {
                        ScrollbarAxis.Vertical -> state.scrollOffsetPx
                        ScrollbarAxis.Horizontal -> state.scrollOffsetXPx
                    }
                    val delta = targetOffset - before
                    if (abs(delta) < 0.001f) return
                    when (drag.axis) {
                        ScrollbarAxis.Vertical -> state.scrollBy(delta)
                        ScrollbarAxis.Horizontal -> state.scrollByX(delta)
                    }
                    val after = when (drag.axis) {
                        ScrollbarAxis.Vertical -> state.scrollOffsetPx
                        ScrollbarAxis.Horizontal -> state.scrollOffsetXPx
                    }
                    val consumedAbs = abs(after - before)
                    if (consumedAbs > 0.01f) {
                        accumulatedConsumedPx += consumedAbs
                        val now = SystemClock.uptimeMillis()
                        if (now - verboseLogAtMs >= 120L) {
                            verboseLogAtMs = now
                            logTouch(
                                "scrollbar drag axis=${drag.axis} pointer=${pointerAxis.toInt()} " +
                                    "thumbStart=${desiredThumbStart.toInt()} " +
                                    "offset=(${state.scrollOffsetXPx.toInt()},${state.scrollOffsetPx.toInt()})",
                                verbose = true
                            )
                        }
                    }
                }

                try {
                    var dragging = true
                    while (dragging) {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        val change = event.changes.firstOrNull { it.id == drag.pointerId }
                        if (change == null) {
                            logTouch(
                                "scrollbar dragStop axis=${drag.axis} reason=pointerMissing " +
                                    "offset=(${state.scrollOffsetXPx.toInt()},${state.scrollOffsetPx.toInt()})"
                            )
                            dragging = false
                            continue
                        }
                        if (!change.pressed) {
                            logTouch(
                                "scrollbar dragStop axis=${drag.axis} reason=pointerUp " +
                                    "offset=(${state.scrollOffsetXPx.toInt()},${state.scrollOffsetPx.toInt()})"
                            )
                            dragging = false
                            continue
                        }
                        val currentPointerAxis = when (drag.axis) {
                            ScrollbarAxis.Vertical -> change.position.y
                            ScrollbarAxis.Horizontal -> change.position.x
                        }
                        scrollFromPointer(currentPointerAxis)
                        change.consume()
                    }
                    logTouch(
                        "scrollbar dragEnd axis=${drag.axis} consumedPx=${accumulatedConsumedPx.toInt()} " +
                            "offset=(${state.scrollOffsetXPx.toInt()},${state.scrollOffsetPx.toInt()})"
                    )
                    dragFinished = true
                } finally {
                    if (!dragFinished) {
                        logTouch(
                            "scrollbar dragAbort axis=${drag.axis} reason=cancelled " +
                                "offset=(${state.scrollOffsetXPx.toInt()},${state.scrollOffsetPx.toInt()})"
                        )
                    }
                    onActiveDragChanged(null)
                    scrollGestureCoordinator.onScrollbarDragFinished()
                    onTriggerScrollbarVisibility(false)
                }
            }
        } catch (e: CancellationException) {
            // pointerInput 被 Compose 取消/重启（key 变化或上层重新组合导致的协程重建）
            logTouch("scrollbar pointerInputCancel reason=cancelled", verbose = true)
            throw e
        }
    }
}
