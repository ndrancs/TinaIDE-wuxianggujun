package com.wuxianggujun.tinaide.terminal.ui

import android.content.Context
import android.graphics.Typeface
import android.view.inputmethod.InputMethodManager
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.termux.terminal.TerminalSession
import com.termux.view.TerminalView
import kotlin.math.roundToInt
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Termux TerminalView 的 Compose 包装器
 */
@Composable
fun TerminalViewWrapper(
    session: TerminalSession?,
    frameId: Long,
    ctrlEnabled: Boolean,
    altEnabled: Boolean,
    onSingleTap: () -> Unit = {},
    onScale: (Float) -> Float = { it },
    modifier: Modifier = Modifier,
    fontSizeSp: Float = 13f,
    typeface: Typeface = Typeface.MONOSPACE,
    onFontSizeChange: ((Float) -> Unit)? = null,
    cursorBlinkEnabled: Boolean = false,
    cursorBlinkRate: Int = 500,
) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()

    // 保存 View 引用
    val viewRef = remember { mutableStateOf<TerminalView?>(null) }
    val appliedTypefaceRef = remember { mutableStateOf<Typeface?>(null) }
    // 记录已应用的字体像素值，避免 Compose 重组触发重复 setTextSize。
    val appliedTextSizePxRef = remember { mutableStateOf<Int?>(null) }

    // 缩放手势期间的视觉字号（sp），手势结束后同步回 fontSizeSp
    val visualFontSizeSp = remember { mutableFloatStateOf(fontSizeSp) }
    // 仅用于防抖 Prefs 持久化，不再用于延迟 emulator resize。
    val persistJob = remember { mutableStateOf<Job?>(null) }

    val currentOnFontSizeChange by rememberUpdatedState(onFontSizeChange)

    // 创建 TerminalViewClient
    val viewClient = remember(context) {
        TinaTerminalViewClient(
            context = context,
            onScale = { scale ->
                // 策略：按整数 sp 阶梯（±10% 阈值）切档，手势过程中只重建 Renderer
                // （setTextSizeWithoutResize），不调 updateSize() —— 避免在连续切档时
                // 给 PTY 反复发 SIGWINCH，shell/vim 来不及重绘而导致"排版错乱 / 文本
                // 缺失"。手势结束判定：最后一次切档 300ms 内无新事件，此时再调一次
                // view.updateSize() 触发真正的 cols/rows 重算（Termux 内部还会判重，
                // 只有尺寸真变才会通知 session），并顺带持久化字号到 Prefs。
                if (scale < 0.9f || scale > 1.1f) {
                    val increase = scale > 1f
                    val currentSp = visualFontSizeSp.floatValue.roundToInt()
                    val nextSp = (if (increase) currentSp + 1 else currentSp - 1)
                        .coerceIn(MIN_FONT_SP, MAX_FONT_SP)
                        .toFloat()
                    if (nextSp != visualFontSizeSp.floatValue) {
                        visualFontSizeSp.floatValue = nextSp
                        val newSizePx = spToPx(density, nextSp)
                        viewRef.value?.let { view ->
                            if (newSizePx != appliedTextSizePxRef.value) {
                                val savedTopRow = view.topRow
                                view.setTextSizeWithoutResize(newSizePx)
                                appliedTextSizePxRef.value = newSizePx
                                val activeTranscriptRows =
                                    view.mEmulator?.screen?.activeTranscriptRows ?: 0
                                val clamped = savedTopRow.coerceIn(-activeTranscriptRows, 0)
                                if (clamped != view.topRow) {
                                    view.topRow = clamped
                                    view.invalidate()
                                }
                            }
                        }
                        // Debounce：最后一次切档 300ms 后（手势已结束）再触发 resize
                        // 和 Prefs 持久化。手势中间每次切档都会 cancel 上一次任务，
                        // 因此 updateSize/persist 在一次完整的 pinch 里只会发生一次。
                        persistJob.value?.cancel()
                        persistJob.value = scope.launch {
                            delay(300)
                            viewRef.value?.updateSize()
                            currentOnFontSizeChange?.invoke(nextSp)
                        }
                    }
                    1f // 重置累积缩放因子
                } else {
                    scale // 累积未达阈值，保留累积继续等下一次事件
                }
            },
            onSingleTap = { _ ->
                viewRef.value?.let { view ->
                    showSoftKeyboard(context, view)
                }
                onSingleTap()
            },
            onEmulatorSet = {
                // 当模拟器设置完成时，可以进行一些初始化
            }
        ).apply {
            this.ctrlEnabled = ctrlEnabled
            this.altEnabled = altEnabled
        }
    }

    // 更新修饰键状态
    LaunchedEffect(ctrlEnabled, altEnabled) {
        viewClient.ctrlEnabled = ctrlEnabled
        viewClient.altEnabled = altEnabled
    }

    // 外部字号变化时（如设置页修改），同步视觉字号
    LaunchedEffect(fontSizeSp) {
        visualFontSizeSp.floatValue = fontSizeSp
    }

    // 更新光标闪烁设置
    LaunchedEffect(cursorBlinkEnabled, cursorBlinkRate) {
        viewRef.value?.let { view ->
            if (cursorBlinkEnabled) {
                view.setTerminalCursorBlinkerRate(cursorBlinkRate)
                view.setTerminalCursorBlinkerState(true, true)
            } else {
                view.setTerminalCursorBlinkerState(false, false)
            }
        }
    }

    val textSizePx = with(density) { fontSizeSp.sp.roundToPx() }

    // 当 frameId 变化时，强制重绘 View
    LaunchedEffect(frameId) {
        viewRef.value?.invalidate()
    }

    // 当 session 变化时，附加到 View
    LaunchedEffect(session) {
        session?.let { s ->
            viewRef.value?.attachSession(s)
        }
    }

    // 避免在组合树销毁后仍持有 View 引用
    DisposableEffect(Unit) {
        onDispose { viewRef.value = null }
    }

    Box(modifier = modifier.clipToBounds()) {
        AndroidView(
            factory = { ctx ->
                TerminalView(ctx, null).apply {
                    isFocusable = true
                    isFocusableInTouchMode = true
                    isClickable = true
                    isLongClickable = true

                    setTerminalViewClient(viewClient)
                    setTextSize(textSizePx)
                    appliedTextSizePxRef.value = textSizePx
                    setTypeface(typeface)
                    appliedTypefaceRef.value = typeface

                    // 设置光标闪烁
                    if (cursorBlinkEnabled) {
                        setTerminalCursorBlinkerRate(cursorBlinkRate)
                        setTerminalCursorBlinkerState(true, true)
                    }

                    // 附加会话
                    session?.let { s ->
                        attachSession(s)
                    }

                    viewRef.value = this
                }
            },
            update = { view ->
                viewClient.ctrlEnabled = ctrlEnabled
                viewClient.altEnabled = altEnabled

                // 仅在字号真正变化时才 setTextSize：Termux 的 setTextSize 会重建
                // Renderer 并调用 updateSize()，后者在行列变化时会把 mTopRow 重置为 0，
                // 导致用户翻阅 scrollback 时缩放触发视图跳回底部，误以为内容缺失。
                // 同时，缩放手势进行中若因无关状态重组误调，会抹掉视觉字号。
                if (appliedTextSizePxRef.value != textSizePx) {
                    val savedTopRow = view.topRow
                    view.setTextSize(textSizePx)
                    appliedTextSizePxRef.value = textSizePx
                    val activeTranscriptRows =
                        view.mEmulator?.screen?.activeTranscriptRows ?: 0
                    val clamped = savedTopRow.coerceIn(-activeTranscriptRows, 0)
                    if (clamped != view.topRow) {
                        view.topRow = clamped
                        view.invalidate()
                    }
                }

                if (appliedTypefaceRef.value !== typeface) {
                    view.setTypeface(typeface)
                    appliedTypefaceRef.value = typeface
                }

                // 如果 session 变化，重新附加
                if (session != null && view.currentSession != session) {
                    view.attachSession(session)
                }

                viewRef.value = view
            },
            modifier = Modifier.fillMaxSize()
        )
    }
}

/**
 * 显示软键盘
 */
fun showSoftKeyboard(context: Context, view: TerminalView) {
    view.requestFocus()
    val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
    imm.showSoftInput(view, InputMethodManager.SHOW_IMPLICIT)
}

/**
 * 隐藏软键盘
 */
fun hideSoftKeyboard(context: Context, view: TerminalView) {
    val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
    imm.hideSoftInputFromWindow(view.windowToken, 0)
}

private fun spToPx(density: Density, sp: Float): Int = with(density) { sp.sp.roundToPx() }

// 字号阶梯上下限（与 TerminalPreferences/AppFontManager 的 8f..32f 对齐）。
private const val MIN_FONT_SP = 8
private const val MAX_FONT_SP = 32
