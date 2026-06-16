package com.wuxianggujun.tinaide.terminal.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wuxianggujun.tinaide.core.i18n.Strings
import com.wuxianggujun.tinaide.terminal.session.SessionStatus
import com.wuxianggujun.tinaide.terminal.session.TerminalSessionState
import com.wuxianggujun.tinaide.terminal.shell.TerminalBackend
import com.wuxianggujun.tinaide.ui.compose.components.TinaAlertDialog
import com.wuxianggujun.tinaide.ui.compose.components.TinaDialogCard
import com.wuxianggujun.tinaide.ui.compose.components.TinaDialogContentColumn
import com.wuxianggujun.tinaide.ui.compose.components.TinaDialogTitleText
import com.wuxianggujun.tinaide.ui.compose.components.TinaPrimaryButton
import com.wuxianggujun.tinaide.ui.compose.components.TinaTextButton
import kotlin.math.abs

/**
 * 终端标签栏
 *
 * 显示所有终端会话的标签，支持切换、关闭、重命名和新建会话。
 */
@Composable
fun TerminalTabBar(
    sessions: List<TerminalSessionState>,
    activeSessionId: String?,
    onTabClick: (String) -> Unit,
    onTabClose: (String) -> Unit,
    onTabRename: ((String, String) -> Unit)? = null,
    onNewTab: () -> Unit,
    onNewTabLongClick: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    // 重命名对话框状态
    var renameDialogSession by remember { mutableStateOf<TerminalSessionState?>(null) }
    val scrollState = rememberScrollState()

    // 记录上一次的会话数量，只在新增标签页时滚动
    var previousSessionCount by remember { mutableStateOf(sessions.size) }

    // 新增标签页后自动滚动到最右侧（只在数量增加时触发，避免其他情况下的滚动）
    LaunchedEffect(sessions.size) {
        if (sessions.size > previousSessionCount && sessions.isNotEmpty()) {
            // 使用非动画滚动，避免视觉跳动
            scrollState.scrollTo(scrollState.maxValue)
        }
        previousSessionCount = sessions.size
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(Color(0xFF252525)) // 整个标签栏区域的背景色
    ) {
        // 标签栏主体
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(40.dp)
                .horizontalScroll(scrollState)
                .padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            // 会话标签
            sessions.forEach { session ->
                key(session.id) {
                    TerminalTab(
                        session = session,
                        isActive = session.id == activeSessionId,
                        onClick = { onTabClick(session.id) },
                        onClose = { onTabClose(session.id) },
                        onLongClick = if (onTabRename != null) {
                            { renameDialogSession = session }
                        } else {
                            null
                        },
                        showCloseButton = sessions.size > 1
                    )
                }
            }

            // 新建标签按钮 - 点击创建 HOST，长按选择后端
            Box(
                modifier = Modifier
                    .size(32.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(Color(0xFF4FC3F7).copy(alpha = 0.3f))
                    .combinedClickable(
                        onClick = onNewTab,
                        onLongClick = onNewTabLongClick
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Add,
                    contentDescription = stringResource(Strings.terminal_new_tab),
                    tint = Color(0xFF4FC3F7), // 使用主题色
                    modifier = Modifier.size(20.dp)
                )
            }
        }

        // 底部分隔线 - 使用更明显的颜色
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(Color(0xFF4FC3F7).copy(alpha = 0.5f)) // 使用主题色作为分隔线
        )
    }

    // 重命名对话框
    renameDialogSession?.let { session ->
        RenameTabDialog(
            currentTitle = session.title,
            onDismiss = { renameDialogSession = null },
            onConfirm = { newTitle ->
                onTabRename?.invoke(session.id, newTitle)
                renameDialogSession = null
            }
        )
    }
}

/**
 * 重命名标签对话框
 */
@Composable
private fun RenameTabDialog(
    currentTitle: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    var text by remember { mutableStateOf(currentTitle) }

    TinaAlertDialog(
        onDismissRequest = onDismiss,
        title = { TinaDialogTitleText(stringResource(Strings.terminal_rename_title)) },
        text = {
            TinaDialogContentColumn {
                TinaDialogCard {
                    OutlinedTextField(
                        value = text,
                        onValueChange = { text = it },
                        label = { Text(stringResource(Strings.terminal_tab_name_label)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = Color(0xFF4FC3F7),
                            cursorColor = Color(0xFF4FC3F7)
                        )
                    )
                }
            }
        },
        confirmButton = {
            TinaPrimaryButton(
                text = stringResource(Strings.btn_confirm),
                enabled = text.isNotBlank(),
                onClick = { onConfirm(text.trim().ifEmpty { currentTitle }) },
            )
        },
        dismissButton = {
            TinaTextButton(
                text = stringResource(Strings.btn_cancel),
                onClick = onDismiss
            )
        }
    )
}

/**
 * 单个终端标签
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TerminalTab(
    session: TerminalSessionState,
    isActive: Boolean,
    onClick: () -> Unit,
    onClose: () -> Unit,
    onLongClick: (() -> Unit)? = null,
    showCloseButton: Boolean = true
) {
    // 滑动关闭的偏移量
    var dragOffset by remember { mutableFloatStateOf(0f) }
    val closeThreshold = 100f

    val backgroundColor by animateColorAsState(
        targetValue = when {
            isActive -> Color(0xFF3D3D3D) // 更明显的激活状态背景
            session.status == SessionStatus.EXITED -> Color(0xFF3D2020)
            session.status == SessionStatus.ERROR -> Color(0xFF4D2020)
            else -> Color(0xFF2A2A2A) // 非激活状态也有轻微背景，增加可见性
        },
        label = "tabBackground"
    )

    val statusIndicatorColor = when (session.status) {
        SessionStatus.STARTING -> Color(0xFFFFAA00)
        SessionStatus.RUNNING -> Color(0xFF00AA00)
        SessionStatus.EXITED -> Color(0xFF888888)
        SessionStatus.ERROR -> Color(0xFFFF4444)
    }

    Box(
        modifier = Modifier
            .pointerInput(Unit) {
                detectHorizontalDragGestures(
                    onDragEnd = {
                        if (abs(dragOffset) > closeThreshold) {
                            onClose()
                        }
                        dragOffset = 0f
                    },
                    onDragCancel = {
                        dragOffset = 0f
                    },
                    onHorizontalDrag = { _, dragAmount ->
                        dragOffset += dragAmount
                    }
                )
            }
            .clip(RoundedCornerShape(topStart = 8.dp, topEnd = 8.dp))
            .background(backgroundColor)
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick
            )
            .padding(horizontal = 10.dp, vertical = 4.dp)
            // 根据是否显示关闭按钮调整宽度限制
            .then(
                if (showCloseButton) {
                    Modifier.widthIn(min = 80.dp, max = 150.dp)
                } else {
                    Modifier.widthIn(max = 120.dp) // 没有关闭按钮时，不设置最小宽度，让标签自适应内容
                }
            )
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.fillMaxHeight()
        ) {
            // 状态指示器
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(statusIndicatorColor)
            )

            // 后端标识
            val (badgeText, badgeColor) = when (session.backend) {
                TerminalBackend.HOST -> "H" to Color(0xFF81C784)
                TerminalBackend.PROOT -> "P" to Color(0xFFFFB74D)
            }
            Text(
                text = badgeText,
                color = badgeColor,
                fontSize = 9.sp,
                modifier = Modifier
                    .clip(RoundedCornerShape(3.dp))
                    .background(badgeColor.copy(alpha = 0.15f))
                    .padding(horizontal = 3.dp, vertical = 1.dp)
            )

            // 标题 - 只有在显示关闭按钮时才使用 weight，否则让标题自适应宽度
            Text(
                text = session.title,
                color = if (isActive) Color.White else Color.White.copy(alpha = 0.85f),
                fontSize = 12.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = if (showCloseButton) Modifier.weight(1f) else Modifier
            )

            // 关闭按钮（仅在有多个标签页时显示）
            if (showCloseButton) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = stringResource(Strings.btn_close),
                    tint = Color.White.copy(alpha = 0.5f),
                    modifier = Modifier
                        .size(14.dp)
                        .clickable(onClick = onClose)
                )
            }
        }
    }
}
