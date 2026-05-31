package com.wuxianggujun.tinaide.ui.compose.components

import android.content.ClipData
import android.widget.Toast
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.toClipEntry
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wuxianggujun.tinaide.core.i18n.Strings
import kotlinx.coroutines.launch

/**
 * 调试内容 - 水平标签页布局
 *
 * 将断点、变量、调用栈、控制台分为独立的水平标签页，
 * 避免垂直堆叠导致的空间挤压问题
 */
@Composable
fun DebugContentWithHorizontalTabs(
    modifier: Modifier = Modifier,
    breakpoints: List<BreakpointInfo> = emptyList(),
    variables: List<DebugVariable> = emptyList(),
    callStack: List<StackFrame> = emptyList(),
    consoleLines: List<String> = emptyList(),
    onBreakpointToggle: (Int) -> Unit = {},
    onBreakpointRemove: (Int) -> Unit = {},
    onVariableClick: (DebugVariable) -> Unit = {},
    onStackFrameClick: (StackFrame) -> Unit = {},
    onClearConsole: () -> Unit = {}
) {
    var selectedTabIndex by remember { mutableIntStateOf(0) }

    val tabs = rememberDebugTabTitles(
        breakpoints = breakpoints,
        variables = variables,
        callStack = callStack,
        consoleLines = consoleLines
    )

    Column(modifier = modifier.fillMaxSize()) {
        TinaOverlayPanelSurface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 8.dp),
            shape = MaterialTheme.shapes.small,
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.36f),
            tonalElevation = 0.dp,
            shadowElevation = 0.dp
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(4.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                tabs.forEachIndexed { index, title ->
                    val selected = selectedTabIndex == index
                    TinaPanelSegmentButton(
                        onClick = { selectedTabIndex = index },
                        modifier = Modifier.height(32.dp),
                        minHeight = 32.dp,
                        shape = MaterialTheme.shapes.small,
                        color = if (selected) {
                            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.88f)
                        } else {
                            MaterialTheme.colorScheme.surface.copy(alpha = 0.22f)
                        },
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp)
                    ) {
                        Text(
                            text = title,
                            style = MaterialTheme.typography.labelMedium,
                            color = if (selected) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            }
                        )
                    }
                }
            }
        }

        // 标签页内容
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(8.dp)
        ) {
            when (selectedTabIndex) {
                0 -> BreakpointsTabContent(
                    breakpoints = breakpoints,
                    onBreakpointToggle = onBreakpointToggle,
                    onBreakpointRemove = onBreakpointRemove
                )
                1 -> VariablesTabContent(
                    variables = variables,
                    onVariableClick = onVariableClick
                )
                2 -> CallStackTabContent(
                    callStack = callStack,
                    onStackFrameClick = onStackFrameClick
                )
                3 -> ConsoleTabContent(
                    consoleLines = consoleLines,
                    onClearConsole = onClearConsole
                )
            }
        }
    }
}

/**
 * 断点标签页内容
 */
@Composable
private fun BreakpointsTabContent(
    breakpoints: List<BreakpointInfo>,
    onBreakpointToggle: (Int) -> Unit,
    onBreakpointRemove: (Int) -> Unit
) {
    if (breakpoints.isEmpty()) {
        EmptyStateText(stringResource(Strings.debug_empty_breakpoints))
    } else {
        LazyColumn(modifier = Modifier.fillMaxSize()) {
            items(breakpoints) { bp ->
                BreakpointItem(
                    breakpoint = bp,
                    onToggle = { onBreakpointToggle(bp.id) },
                    onRemove = { onBreakpointRemove(bp.id) }
                )
            }
        }
    }
}

/**
 * 变量标签页内容
 */
@Composable
private fun VariablesTabContent(
    variables: List<DebugVariable>,
    onVariableClick: (DebugVariable) -> Unit
) {
    if (variables.isEmpty()) {
        EmptyStateText(stringResource(Strings.debug_empty_variables))
    } else {
        LazyColumn(modifier = Modifier.fillMaxSize()) {
            items(variables) { variable ->
                VariableItem(
                    variable = variable,
                    onClick = { onVariableClick(variable) }
                )
            }
        }
    }
}

/**
 * 调用栈标签页内容
 */
@Composable
private fun CallStackTabContent(
    callStack: List<StackFrame>,
    onStackFrameClick: (StackFrame) -> Unit
) {
    if (callStack.isEmpty()) {
        EmptyStateText(stringResource(Strings.debug_empty_call_stack))
    } else {
        LazyColumn(modifier = Modifier.fillMaxSize()) {
            items(callStack) { frame ->
                StackFrameItem(
                    frame = frame,
                    onClick = { onStackFrameClick(frame) }
                )
            }
        }
    }
}

/**
 * 控制台标签页内容
 */
@Composable
private fun ConsoleTabContent(
    consoleLines: List<String>,
    onClearConsole: () -> Unit
) {
    val context = LocalContext.current
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    val textToCopy = remember(consoleLines) { consoleLines.takeLast(500).joinToString("\n") }

    val clearText = stringResource(Strings.action_clear)
    val copyText = stringResource(Strings.action_copy)
    val consoleCopiedText = stringResource(Strings.debug_console_copied)
    val emptyConsoleText = stringResource(Strings.debug_empty_console)

    Column(modifier = Modifier.fillMaxSize()) {
        TinaOverlayPanelSurface(
            modifier = Modifier.padding(bottom = 8.dp),
            shape = MaterialTheme.shapes.small,
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.28f),
            tonalElevation = 0.dp,
            shadowElevation = 0.dp
        ) {
            Row(
                modifier = Modifier.padding(4.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                TinaPanelSegmentButton(
                    onClick = onClearConsole,
                    modifier = Modifier.height(32.dp),
                    minHeight = 32.dp,
                    shape = MaterialTheme.shapes.small,
                    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.22f),
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp)
                ) {
                    Text(
                        text = clearText,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                }

                TinaPanelSegmentButton(
                    onClick = {
                        if (textToCopy.isNotBlank()) {
                            scope.launch {
                                val clipData = ClipData.newPlainText("console", textToCopy)
                                clipboard.setClipEntry(clipData.toClipEntry())
                            }
                            Toast.makeText(context, consoleCopiedText, Toast.LENGTH_SHORT).show()
                        }
                    },
                    modifier = Modifier.height(32.dp),
                    minHeight = 32.dp,
                    shape = MaterialTheme.shapes.small,
                    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.22f),
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp)
                ) {
                    Text(
                        text = copyText,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
        }

        // 控制台输出
        if (consoleLines.isEmpty()) {
            EmptyStateText(emptyConsoleText)
        } else {
            SelectionContainer {
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    items(consoleLines.takeLast(500)) { line ->
                        Text(
                            text = line,
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurface,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                }
            }
        }
    }
}

/**
 * 空状态文本
 */
@Composable
private fun EmptyStateText(message: String) {
    Text(
        text = message,
        fontSize = 12.sp,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(16.dp)
    )
}

@Composable
private fun rememberDebugTabTitles(
    breakpoints: List<BreakpointInfo>,
    variables: List<DebugVariable>,
    callStack: List<StackFrame>,
    consoleLines: List<String>
): List<String> = listOf(
    stringResource(Strings.debug_tab_breakpoints, breakpoints.size),
    stringResource(Strings.debug_tab_variables, variables.size),
    stringResource(Strings.debug_tab_call_stack, callStack.size),
    stringResource(Strings.debug_tab_console, consoleLines.size)
)
