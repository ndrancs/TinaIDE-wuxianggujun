package com.wuxianggujun.tinaide.ui.compose.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ExitToApp
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wuxianggujun.tinaide.core.i18n.Strings
import java.io.BufferedReader
import java.io.InputStreamReader
import kotlin.math.roundToInt
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.isActive

/**
 * SDL 图形运行时的悬浮覆盖层。
 *
 * 提供：
 * - 可拖拽的半透明小球（始终显示）
 * - 点击小球展开控制面板：退出按钮 + 日志面板
 * - 日志面板实时捕获 logcat 的 TINA_USER_OUTPUT 标签输出
 */
@Composable
fun FloatingOverlay(
    enableFloatingLog: Boolean,
    onExit: () -> Unit,
    modifier: Modifier = Modifier
) {
    val density = LocalDensity.current
    val windowSize = LocalWindowInfo.current.containerSize
    val ballSizePx = with(density) { 48.dp.toPx() }
    val screenWidthPx = windowSize.width.toFloat().coerceAtLeast(ballSizePx)
    val screenHeightPx = windowSize.height.toFloat().coerceAtLeast(ballSizePx)
    val maxOffsetX = (screenWidthPx - ballSizePx).coerceAtLeast(0f)
    val maxOffsetY = (screenHeightPx - ballSizePx).coerceAtLeast(0f)

    var offsetX by remember { mutableFloatStateOf(screenWidthPx - with(density) { 60.dp.toPx() }) }
    var offsetY by remember { mutableFloatStateOf(screenHeightPx * 0.3f) }
    var expanded by remember { mutableStateOf(false) }
    var showExitDialog by remember { mutableStateOf(false) }

    val logLines = remember { mutableStateListOf<LogEntry>() }
    val listState = rememberLazyListState()

    if (enableFloatingLog) {
        LaunchedEffect(Unit) {
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                captureLogcat(logLines)
            }
        }

        LaunchedEffect(logLines.size) {
            if (logLines.isNotEmpty()) {
                listState.animateScrollToItem(logLines.size - 1)
            }
        }
    }

    Box(modifier = modifier.fillMaxSize()) {
        // 展开的日志面板（仅在开启悬浮日志时可展开）
        AnimatedVisibility(
            visible = expanded && enableFloatingLog,
            enter = fadeIn() + scaleIn(initialScale = 0.8f),
            exit = fadeOut() + scaleOut(targetScale = 0.8f),
            modifier = Modifier.align(Alignment.Center)
        ) {
            FloatingPanel(
                enableFloatingLog = enableFloatingLog,
                logLines = logLines,
                listState = listState,
                onClearLog = { logLines.clear() },
                onClose = { expanded = false },
                onExit = { showExitDialog = true }
            )
        }

        // 可拖拽小球（日志面板展开时隐藏，否则始终可见）
        AnimatedVisibility(
            visible = !(expanded && enableFloatingLog),
            enter = fadeIn() + scaleIn(),
            exit = fadeOut() + scaleOut(),
            modifier = Modifier.offset { IntOffset(offsetX.roundToInt(), offsetY.roundToInt()) }
        ) {
            FloatingBall(
                hasLogs = enableFloatingLog && logLines.isNotEmpty(),
                onTap = {
                    if (enableFloatingLog) {
                        expanded = true
                    } else {
                        // 无日志面板时直接弹退出确认
                        showExitDialog = true
                    }
                },
                onDrag = { dragAmount ->
                    offsetX = (offsetX + dragAmount.x)
                        .coerceIn(0f, maxOffsetX)
                    offsetY = (offsetY + dragAmount.y)
                        .coerceIn(0f, maxOffsetY)
                }
            )
        }
    }

    if (showExitDialog) {
        TinaConfirmDialog(
            title = stringResource(Strings.floating_overlay_exit),
            message = stringResource(Strings.floating_overlay_exit_confirm),
            confirmText = stringResource(Strings.floating_overlay_exit_confirm_yes),
            dismissText = stringResource(Strings.floating_overlay_exit_confirm_no),
            onConfirm = {
                showExitDialog = false
                onExit()
            },
            onDismiss = { showExitDialog = false }
        )
    }
}

@Composable
private fun FloatingBall(
    hasLogs: Boolean,
    onTap: () -> Unit,
    onDrag: (androidx.compose.ui.geometry.Offset) -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier
            .size(48.dp)
            .shadow(6.dp, CircleShape)
            .alpha(0.7f)
            .pointerInput(Unit) {
                detectDragGestures { change, dragAmount ->
                    change.consume()
                    onDrag(dragAmount)
                }
            }
            .pointerInput(Unit) {
                detectTapGestures(onTap = { onTap() })
            },
        shape = CircleShape,
        color = if (hasLogs) {
            MaterialTheme.colorScheme.primary
        } else {
            MaterialTheme.colorScheme.surfaceVariant
        },
        contentColor = if (hasLogs) {
            MaterialTheme.colorScheme.onPrimary
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        }
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.ExitToApp,
                contentDescription = stringResource(Strings.floating_overlay_ball_desc),
                modifier = Modifier.size(24.dp)
            )
        }
    }
}

@Composable
private fun FloatingPanel(
    enableFloatingLog: Boolean,
    logLines: List<LogEntry>,
    listState: androidx.compose.foundation.lazy.LazyListState,
    onClearLog: () -> Unit,
    onClose: () -> Unit,
    onExit: () -> Unit,
    modifier: Modifier = Modifier
) {
    val density = LocalDensity.current
    val windowSize = LocalWindowInfo.current.containerSize
    val panelWidth = with(density) { (windowSize.width * 0.85f).toDp() }
    val panelHeight = with(density) { (windowSize.height * 0.6f).toDp() }
    val panelShape = RoundedCornerShape(TinaShapes.DialogCorner)

    TinaOverlayPanelSurface(
        modifier = modifier
            .widthIn(max = panelWidth),
        shape = panelShape,
        containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.95f),
        tonalElevation = 8.dp,
        shadowElevation = 8.dp
    ) {
        TinaCustomDialogScaffold(
            modifier = Modifier.padding(16.dp),
            header = {
                TinaCustomDialogHeader(
                    title = if (enableFloatingLog) {
                        stringResource(Strings.floating_overlay_log_title)
                    } else {
                        stringResource(Strings.floating_overlay_exit)
                    },
                    trailingContent = {
                        if (enableFloatingLog) {
                            FloatingOverlayActionButton(
                                onClick = onClearLog,
                                color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.24f)
                            ) {
                                Icon(
                                    Icons.Default.Delete,
                                    contentDescription = stringResource(Strings.floating_overlay_log_clear),
                                    tint = MaterialTheme.colorScheme.error,
                                    modifier = Modifier.size(18.dp)
                                )
                            }

                            Spacer(modifier = Modifier.width(8.dp))
                        }

                        FloatingOverlayActionButton(onClick = onClose) {
                            Icon(
                                Icons.Default.Close,
                                contentDescription = stringResource(Strings.floating_overlay_exit_confirm_no),
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                )
            },
            footer = {
                TinaDialogActionRow {
                    TinaOutlinedButton(
                        text = stringResource(Strings.floating_overlay_exit),
                        onClick = onExit,
                        leadingIcon = Icons.AutoMirrored.Filled.ExitToApp
                    )
                }
            }
        ) {
            if (enableFloatingLog) {
                FloatingOverlayLogSurface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(panelHeight)
                ) {
                    if (logLines.isEmpty()) {
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = stringResource(Strings.floating_overlay_log_empty),
                                color = Color.Gray,
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    } else {
                        LazyColumn(
                            state = listState,
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(8.dp),
                            verticalArrangement = Arrangement.spacedBy(2.dp)
                        ) {
                            items(logLines) { entry ->
                                Text(
                                    text = entry.message,
                                    color = when (entry.level) {
                                        'E' -> Color(0xFFFF6B6B)
                                        'W' -> Color(0xFFFFD93D)
                                        else -> Color(0xFFCCCCCC)
                                    },
                                    fontSize = 11.sp,
                                    fontFamily = FontFamily.Monospace,
                                    maxLines = 3,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.fillMaxWidth()
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun FloatingOverlayActionButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.28f),
    content: @Composable BoxScope.() -> Unit
) {
    TinaPanelSegmentButton(
        onClick = onClick,
        modifier = modifier.size(32.dp),
        minHeight = 32.dp,
        color = color,
        contentPadding = PaddingValues(0.dp),
        contentAlignment = Alignment.Center,
        content = content
    )
}

@Composable
private fun FloatingOverlayLogSurface(
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit
) {
    TinaOverlayPanelSurface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        containerColor = Color(0xFF1E1E1E),
        tonalElevation = 0.dp,
        shadowElevation = 0.dp
    ) {
        Box(
            modifier = Modifier.fillMaxSize(),
            content = content
        )
    }
}

data class LogEntry(
    val level: Char,
    val message: String,
    val timestamp: Long = System.currentTimeMillis()
)

private const val MAX_LOG_LINES = 500

/**
 * 捕获的 logcat 标签列表。
 *
 * - TINA_USER_OUTPUT: 原生 log redirect 库重定向的 stdout/stderr
 * - SDL: SDL_Log 系列输出
 * - System.out / System.err: Java 标准输出
 */
private val LOG_TAGS = listOf(
    "TINA_USER_OUTPUT",
    "SDL",
    "System.out",
    "System.err"
)

/**
 * 通过 logcat 捕获用户程序的 stdout/stderr 及 SDL 日志。
 *
 * 使用 `--pid` 限制为当前进程，`-T 1` 从启动时刻开始读取（忽略历史），
 * `-s` 只保留 [LOG_TAGS] 中列出的标签。
 * 当行数超过 [MAX_LOG_LINES] 时移除最早的条目。
 */
private suspend fun captureLogcat(logLines: MutableList<LogEntry>) = coroutineScope {
    val pid = android.os.Process.myPid()
    val tagFilters = LOG_TAGS.map { "$it:*" }
    val cmd = mutableListOf(
        "logcat",
        "--pid",
        pid.toString(),
        "-v",
        "brief",
        "-T",
        "1",
        "-s"
    ).apply { addAll(tagFilters) }

    val process = try {
        ProcessBuilder(cmd)
            .redirectErrorStream(true)
            .start()
    } catch (e: Exception) {
        logLines.add(LogEntry('E', "Failed to start logcat: ${e.message}"))
        return@coroutineScope
    }

    try {
        val reader = BufferedReader(InputStreamReader(process.inputStream))
        var line: String?
        while (isActive) {
            line = reader.readLine() ?: break
            if (line.isBlank() || line.startsWith("-----")) continue

            val level = when {
                line.isNotEmpty() && line[0] in "VDIWEF" -> line[0]
                else -> 'I'
            }
            val message = line.substringAfter("): ", line)

            logLines.add(LogEntry(level, message))
            while (logLines.size > MAX_LOG_LINES) {
                logLines.removeAt(0)
            }
        }
    } catch (_: Exception) {
        // reader closed
    } finally {
        process.destroy()
    }
}
