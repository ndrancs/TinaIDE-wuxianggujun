package com.wuxianggujun.tinaide.ui.compose.components

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.annotation.StringRes
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalTextToolbar
import androidx.compose.ui.platform.TextToolbar
import androidx.compose.ui.platform.TextToolbarStatus
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wuxianggujun.tinaide.core.compile.BuildLogEntry
import com.wuxianggujun.tinaide.core.compile.BuildLogLevel
import com.wuxianggujun.tinaide.core.i18n.Strings
import com.wuxianggujun.tinaide.core.i18n.strOr
import kotlinx.coroutines.launch

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ComposeBuildLogPanel(
    logs: List<BuildLogEntry>,
    modifier: Modifier = Modifier,
    autoScroll: Boolean = true,
    fontSizeSp: Float = 12f,
    selectAllRequest: Int = 0,
    scrollToBottomRequest: Int = 0,
    @StringRes copyActionTitleRes: Int = Strings.btn_copy_log,
    onCopyAll: (() -> Unit)? = null,
    onClear: (() -> Unit)? = null
) {
    val focusRequester = remember { FocusRequester() }
    val interactionSource = remember { MutableInteractionSource() }
    val scope = rememberCoroutineScope()
    val verticalScrollState = rememberScrollState()
    val horizontalScrollState = rememberScrollState()
    var actionMenuExpanded by remember { mutableStateOf(false) }

    val annotatedLogs = remember(logs, fontSizeSp) {
        buildColoredLogText(logs, fontSizeSp)
    }
    var logTextFieldValue by remember {
        mutableStateOf(TextFieldValue(annotatedLogs))
    }
    val hasLogs = annotatedLogs.isNotEmpty()

    fun selectAllLogs() {
        if (annotatedLogs.isEmpty()) return
        logTextFieldValue = TextFieldValue(
            annotatedLogs,
            TextRange(0, annotatedLogs.length)
        )
        focusRequester.requestFocus()
    }

    fun scrollToBottom() {
        scope.launch {
            verticalScrollState.animateScrollTo(verticalScrollState.maxValue)
        }
    }

    LaunchedEffect(annotatedLogs) {
        logTextFieldValue = TextFieldValue(
            annotatedLogs,
            logTextFieldValue.selection.coerceInTextLength(annotatedLogs.length)
        )
    }

    LaunchedEffect(logs.size, autoScroll, scrollToBottomRequest) {
        if ((autoScroll || scrollToBottomRequest > 0) && logs.isNotEmpty()) {
            verticalScrollState.animateScrollTo(verticalScrollState.maxValue)
        }
    }

    LaunchedEffect(selectAllRequest) {
        if (selectAllRequest > 0 && annotatedLogs.isNotEmpty()) {
            selectAllLogs()
        }
    }

    TinaOverlayPanelSurface(
        modifier = modifier
            .fillMaxSize()
            .padding(start = 8.dp, end = 8.dp, bottom = 8.dp),
        shape = MaterialTheme.shapes.small,
        containerColor = MaterialTheme.colorScheme.surface,
        tonalElevation = 0.dp,
        shadowElevation = 0.dp
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(verticalScrollState)
                .horizontalScroll(horizontalScrollState)
                .padding(horizontal = 10.dp, vertical = 8.dp)
        ) {
            CompositionLocalProvider(LocalTextToolbar provides DisabledBuildLogTextToolbar) {
                BasicTextField(
                    value = logTextFieldValue,
                    onValueChange = { value ->
                        logTextFieldValue = TextFieldValue(
                            annotatedLogs,
                            value.selection.coerceInTextLength(annotatedLogs.length)
                        )
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(focusRequester)
                        .combinedClickable(
                            interactionSource = interactionSource,
                            indication = null,
                            enabled = hasLogs,
                            onClick = { focusRequester.requestFocus() },
                            onLongClick = { actionMenuExpanded = true }
                        ),
                    readOnly = true,
                    textStyle = TextStyle(
                        fontFamily = FontFamily.Monospace,
                        fontSize = fontSizeSp.sp,
                        lineHeight = (fontSizeSp * 1.5f).sp
                    ),
                )
            }

            BuildLogActionMenu(
                expanded = actionMenuExpanded,
                copyActionTitleRes = copyActionTitleRes,
                copyEnabled = onCopyAll != null,
                clearEnabled = onClear != null,
                onSelectAll = {
                    actionMenuExpanded = false
                    selectAllLogs()
                },
                onCopy = {
                    actionMenuExpanded = false
                    onCopyAll?.invoke()
                },
                onScrollToBottom = {
                    actionMenuExpanded = false
                    scrollToBottom()
                },
                onClear = {
                    actionMenuExpanded = false
                    onClear?.invoke()
                },
                onDismiss = { actionMenuExpanded = false }
            )
        }
    }
}

private object DisabledBuildLogTextToolbar : TextToolbar {
    override val status: TextToolbarStatus
        get() = TextToolbarStatus.Hidden

    override fun showMenu(
        rect: Rect,
        onCopyRequested: (() -> Unit)?,
        onPasteRequested: (() -> Unit)?,
        onCutRequested: (() -> Unit)?,
        onSelectAllRequested: (() -> Unit)?
    ) = Unit

    override fun hide() = Unit
}

@Composable
private fun BuildLogActionMenu(
    expanded: Boolean,
    @StringRes copyActionTitleRes: Int,
    copyEnabled: Boolean,
    clearEnabled: Boolean,
    onSelectAll: () -> Unit,
    onCopy: () -> Unit,
    onScrollToBottom: () -> Unit,
    onClear: () -> Unit,
    onDismiss: () -> Unit
) {
    TinaDropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismiss
    ) {
        TinaDropdownMenuItem(
            text = { Text(stringResource(Strings.action_select_all)) },
            onClick = onSelectAll
        )
        TinaDropdownMenuItem(
            text = { Text(stringResource(copyActionTitleRes)) },
            enabled = copyEnabled,
            onClick = onCopy
        )
        TinaDropdownMenuItem(
            text = { Text(stringResource(Strings.action_scroll_to_bottom)) },
            onClick = onScrollToBottom
        )
        TinaDropdownMenuDivider()
        TinaDropdownMenuDangerItem(
            text = { Text(stringResource(Strings.action_clear)) },
            enabled = clearEnabled,
            onClick = onClear
        )
    }
}

internal fun copyLogEntriesToClipboard(
    context: Context,
    logs: List<BuildLogEntry>,
    @StringRes clipboardLabelRes: Int,
    @StringRes emptyMessageRes: Int
) {
    val plainText = buildPlainLogText(logs)
    if (plainText.isEmpty()) {
        Toast.makeText(context, emptyMessageRes.strOr(context), Toast.LENGTH_SHORT).show()
        return
    }

    val clipboardManager = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    val clip = ClipData.newPlainText(clipboardLabelRes.strOr(context), plainText)
    clipboardManager.setPrimaryClip(clip)
    Toast.makeText(context, Strings.toast_copied.strOr(context), Toast.LENGTH_SHORT).show()
}

private fun buildColoredLogText(
    logs: List<BuildLogEntry>,
    fontSizeSp: Float
): AnnotatedString = buildAnnotatedString {
    val timestampColor = Color(0xFF6E6E6E)

    logs.forEachIndexed { index, entry ->
        withStyle(SpanStyle(color = timestampColor)) {
            append(entry.formattedTime)
            append("  ")
        }

        withStyle(
            SpanStyle(
                color = getLevelColorValue(entry.level),
                fontWeight = getLevelFontWeight(entry.level)
            )
        ) {
            append(entry.message)
        }

        if (index < logs.size - 1) {
            append("\n")
        }
    }
}

private fun buildPlainLogText(logs: List<BuildLogEntry>): String = logs.joinToString("\n") { it.fullText }

private fun getLevelColorValue(level: BuildLogLevel): Color = when (level) {
    BuildLogLevel.VERBOSE -> TinaSemanticColors.Log.verbose
    BuildLogLevel.DEBUG -> TinaSemanticColors.Log.debug
    BuildLogLevel.PROGRESS -> TinaSemanticColors.Log.success
    BuildLogLevel.INFO -> TinaSemanticColors.Log.info
    BuildLogLevel.WARN -> TinaSemanticColors.Log.warn
    BuildLogLevel.ERROR -> TinaSemanticColors.Log.error
    BuildLogLevel.SUCCESS -> TinaSemanticColors.Log.success
    BuildLogLevel.FAIL -> TinaSemanticColors.Log.fail
}

private fun getLevelFontWeight(level: BuildLogLevel): FontWeight? = when (level) {
    BuildLogLevel.PROGRESS -> FontWeight.SemiBold
    else -> null
}

private fun TextRange.coerceInTextLength(length: Int): TextRange = TextRange(
    start = start.coerceIn(0, length),
    end = end.coerceIn(0, length)
)
