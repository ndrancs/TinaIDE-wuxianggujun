package com.wuxianggujun.tinaide.core.editorview

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import com.wuxianggujun.tinaide.ui.compose.components.MarkdownViewer
import kotlin.math.ceil

internal data class EditorPopupViewportMetrics(
    val windowWidthPx: Float,
    val windowHeightPx: Float,
    val imeBottomInsetPx: Float
)

internal data class EditorPopupLayoutProbe(
    val onCompletionLayout: ((CompletionPopupLayout) -> Unit)? = null,
    val onSignatureHelpLayout: ((SignatureHelpPopupLayout) -> Unit)? = null
)

// 允许测试覆盖窗口与 IME 视口参数，避免依赖真实设备窗口状态。
internal val LocalEditorPopupViewportMetricsOverride =
    compositionLocalOf<EditorPopupViewportMetrics?> { null }

internal val LocalEditorPopupLayoutProbe = compositionLocalOf<EditorPopupLayoutProbe?> { null }

@Composable
internal fun EditorSelectionContextMenuOverlay(
    session: TinaEditorSession
) {
    val state = session.state
    val ui = session.ui
    val density = session.density
    val lineTextLookup = remember(state) { EditorLineTextLookup(state) }
    val coordinator = session.selectionContextMenuCoordinator
    val selectedText = coordinator.selectedText()
    val availableKeyboardActions = coordinator.availableKeyboardActions()
    val keyboardSelectedAction = ui.contextMenuKeyboardAction
        .takeIf { it in availableKeyboardActions }
    val viewportMetrics = resolveEditorPopupViewportMetrics(density)
    val anchorInWindowPx = IntOffset(
        x = ui.canvasOriginInWindowPx.x + ui.contextMenuOffset.x,
        y = ui.canvasOriginInWindowPx.y + ui.contextMenuOffset.y
    )
    val positionProvider = remember(anchorInWindowPx, viewportMetrics.imeBottomInsetPx, density) {
        ContextMenuPopupPositionProvider(
            anchorInWindowPx = anchorInWindowPx,
            imeBottomInsetPx = viewportMetrics.imeBottomInsetPx.toInt(),
            marginPx = with(density) { 8.dp.roundToPx() }
        )
    }
    EditorSelectionContextMenu(
        visible = ui.contextMenuVisible,
        positionProvider = positionProvider,
        selectedText = selectedText,
        keyboardSelectedAction = keyboardSelectedAction,
        colorScheme = state.colorScheme,
        hoverEnabled = state.onRequestHover != null,
        peekDefinitionEnabled = state.onRequestPeekDefinition != null,
        gotoDefinitionEnabled = state.onRequestGotoDefinition != null,
        findReferencesEnabled = state.onRequestFindReferences != null,
        gotoTypeDefinitionEnabled = state.onRequestGotoTypeDefinition != null,
        gotoImplementationEnabled = state.onRequestGotoImplementation != null,
        codeActionsEnabled = state.onRequestCodeActions != null,
        renameSymbolEnabled = state.onRequestRenameSymbol != null,
        switchHeaderSourceEnabled = state.onRequestSwitchHeaderSource != null,
        onCopy = {
            coordinator.onCopy(selectedText)
        },
        onCut = {
            coordinator.onCut(selectedText)
        },
        onPaste = {
            coordinator.onPaste()
        },
        onSelectAll = {
            // 使用与渲染/命中测试一致的行内 prefix 布局，避免 selectAll 后菜单锚点“飘移”。
            val anchorLine = state.cursorPosition.line
                .coerceIn(0, (state.textBuffer.lineCount - 1).coerceAtLeast(0))
            val lineText = lineTextLookup.lineText(anchorLine)
            val anchorColumn = state.cursorPosition.column.coerceIn(0, lineText.length)
            val cursorVisualLine = state.visualLineForPosition(anchorLine, anchorColumn)
            val segmentStartColumn = state.visualLineStartColumn(cursorVisualLine).coerceIn(0, lineText.length)
            session.textPaint.typeface = state.typeface
            session.textPaint.textSize = with(density) { state.fontSizeSp.sp.toPx() }
            val prefixLayout = session.lineLayoutCache.getPrefixLayout(
                line = anchorLine,
                lineText = lineText,
                textVersion = state.textBuffer.version,
                paint = session.textPaint,
                tabSize = state.config.tabSize
            )
            val segmentStartXInTextPx = prefixLayout.prefix[segmentStartColumn.coerceIn(0, prefixLayout.length)]
            val cursorXInTextPx = prefixLayout.prefix[anchorColumn.coerceIn(segmentStartColumn, prefixLayout.length)]
            val cursorAnchorX =
                ui.contentStartXPx + (cursorXInTextPx - segmentStartXInTextPx) - state.scrollOffsetXPx
            val cursorAnchorY =
                state.visualLineTopInViewport(cursorVisualLine) + state.lineHeightPx * 0.62f

            val fallbackAnchorX =
                (ui.contentStartXPx + (ui.canvasWidthPx - ui.contentStartXPx) * 0.5f).coerceAtLeast(ui.contentStartXPx)
            val fallbackAnchorY = (ui.canvasHeightPx * 0.28f).coerceAtLeast(0f)
            val anchorX = if (cursorAnchorX.isFinite()) cursorAnchorX else fallbackAnchorX
            val anchorY = if (cursorAnchorY.isFinite()) cursorAnchorY else fallbackAnchorY
            val clampedX = anchorX.coerceIn(0f, (ui.canvasWidthPx - 1f).coerceAtLeast(0f))
            val clampedY = anchorY.coerceIn(0f, (ui.canvasHeightPx - 1f).coerceAtLeast(0f))

            coordinator.onSelectAll(
                anchorInViewportPx = IntOffset(clampedX.toInt(), clampedY.toInt())
            )
        },
        onPeekDefinition = {
            coordinator.onPeekDefinition()
        },
        onGotoDefinition = {
            coordinator.onGotoDefinition()
        },
        onFindReferences = {
            coordinator.onFindReferences()
        },
        onGotoTypeDefinition = {
            coordinator.onGotoTypeDefinition()
        },
        onGotoImplementation = {
            coordinator.onGotoImplementation()
        },
        onCodeActions = {
            coordinator.onRequestCodeActions()
        },
        onRenameSymbol = {
            coordinator.onRequestRenameSymbol()
        },
        onSwitchHeaderSource = {
            coordinator.onSwitchHeaderSource()
        },
        onHover = {
            coordinator.onHover(ui.contextMenuOffset)
        },
        onDismiss = {
            ui.contextMenuKeyboardAction = null
            coordinator.onDismiss()
        }
    )
}

@Composable
internal fun EditorHoverOverlay(
    session: TinaEditorSession
) {
    val state = session.state
    val hoverState = state.hoverUiState
    if (hoverState is HoverUiState.Hidden) return

    val ui = session.ui
    val density = session.density
    val uriHandler = LocalUriHandler.current
    val viewportMetrics = resolveEditorPopupViewportMetrics(density)
    val anchorInWindowPx = IntOffset(
        x = ui.canvasOriginInWindowPx.x + ui.hoverOffset.x,
        y = ui.canvasOriginInWindowPx.y + ui.hoverOffset.y
    )
    val positionProvider = remember(anchorInWindowPx, viewportMetrics.imeBottomInsetPx, density) {
        ContextMenuPopupPositionProvider(
            anchorInWindowPx = anchorInWindowPx,
            imeBottomInsetPx = viewportMetrics.imeBottomInsetPx.toInt(),
            marginPx = with(density) { 8.dp.roundToPx() }
        )
    }
    val content = when (hoverState) {
        HoverUiState.Hidden -> return
        HoverUiState.Loading -> stringResource(R.string.editor_hover_loading)
        is HoverUiState.Visible -> hoverState.markdown
    }
    val popupColors = rememberEditorPopupColors(state.colorScheme)

    Popup(
        popupPositionProvider = positionProvider,
        onDismissRequest = { session.interactionController.dismissHover() },
        properties = PopupProperties(focusable = false)
    ) {
        EditorPopupScaffold(
            colors = popupColors,
            modifier = Modifier
                .widthIn(max = 320.dp)
                .heightIn(max = 240.dp),
            contentModifier = Modifier
                .widthIn(max = 320.dp)
                .heightIn(max = 240.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 12.dp, end = 4.dp, top = 4.dp, bottom = 2.dp),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                EditorPopupActionButton(
                    onClick = { session.interactionController.dismissHover() },
                    colors = popupColors,
                    contentColor = popupColors.secondaryTextColor,
                    contentPadding = editorPopupCompactActionPadding
                ) {
                    Text(stringResource(R.string.editor_hover_close))
                }
            }
            EditorPopupDivider(colors = popupColors)
            if (hoverState is HoverUiState.Loading) {
                EditorPopupLoadingBar(colors = popupColors)
            }
            if (hoverState is HoverUiState.Visible) {
                MarkdownViewer(
                    markdown = content,
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f, fill = false)
                        .verticalScroll(rememberScrollState()),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
                    onLinkClick = { url ->
                        runCatching { uriHandler.openUri(url) }
                        session.interactionController.dismissHover()
                    },
                    onCodeCopy = { code ->
                        session.clipboardCoordinator.copyText(code)
                    }
                )
            } else {
                Text(
                    text = content,
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f, fill = false)
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    color = popupColors.secondaryTextColor
                )
            }
        }
    }
}

@Composable
internal fun EditorCompletionOverlay(
    session: TinaEditorSession
) {
    val state = session.state
    val completionState = state.completionUiState
    val popupItems = when (completionState) {
        is CompletionUiState.Visible -> completionState.items
        is CompletionUiState.Loading -> completionState.previousItems
        CompletionUiState.Hidden -> return
    }
    if (popupItems.isEmpty()) return

    val ui = session.ui
    val density = session.density
    val layoutProbe = LocalEditorPopupLayoutProbe.current
    val viewportMetrics = resolveEditorPopupViewportMetrics(density)

    val preferredCompletionPopupWidthPx = with(density) { 300.dp.toPx() }
    val completionPopupMaxHeightPx = with(density) { 280.dp.toPx() }
    val completionItemHeightPx = with(density) { 34.dp.toPx() }
    val completionLoadingHeightPx = with(density) { 20.dp.toPx() }
    val completionMarginPx = with(density) { 8.dp.toPx() }
    val completionCursorGapPx = with(density) { 16.dp.toPx() }
    val completionNarrowEditorThresholdPx = with(density) { 500.dp.toPx() }
    val completionMinHeightPx = with(density) { 96.dp.toPx() }
    val lineHeightPx = state.lineHeightPx
    val canvasWidthPx = ui.canvasWidthPx
    val canvasHeightPx = ui.canvasHeightPx
    val cursorAnchor = resolveCursorPopupAnchor(session)
    val completionLayout = CompletionPopupLayoutResolver.resolve(
        cursorXInViewportPx = cursorAnchor.cursorXInViewportPx,
        cursorLineTopInViewportPx = cursorAnchor.cursorLineTopInViewportPx,
        lineHeightPx = lineHeightPx,
        canvasOriginInWindowPx = ui.canvasOriginInWindowPx,
        canvasWidthPx = canvasWidthPx,
        canvasHeightPx = canvasHeightPx,
        windowWidthPx = viewportMetrics.windowWidthPx,
        windowHeightPx = viewportMetrics.windowHeightPx,
        imeBottomInsetPx = viewportMetrics.imeBottomInsetPx,
        preferredPopupWidthPx = preferredCompletionPopupWidthPx,
        popupMaxHeightPx = completionPopupMaxHeightPx,
        itemCount = popupItems.size,
        itemHeightPx = completionItemHeightPx,
        loadingIndicatorHeightPx = completionLoadingHeightPx,
        isLoading = completionState is CompletionUiState.Loading,
        marginPx = completionMarginPx,
        cursorGapPx = completionCursorGapPx,
        narrowEditorThresholdPx = completionNarrowEditorThresholdPx,
        minPopupHeightPx = completionMinHeightPx
    )
    SideEffect {
        layoutProbe?.onCompletionLayout?.invoke(completionLayout)
    }
    val selectedIndex = when (completionState) {
        is CompletionUiState.Visible -> completionState.selectedIndex
        is CompletionUiState.Loading -> completionState.selectedIndex.coerceIn(0, maxOf(0, popupItems.lastIndex))
        CompletionUiState.Hidden -> -1
    }
    val popupQuery = when (completionState) {
        is CompletionUiState.Visible -> completionState.query
        is CompletionUiState.Loading -> completionState.query
        CompletionUiState.Hidden -> ""
    }
    EditorCompletionPopup(
        items = popupItems,
        selectedIndex = selectedIndex,
        query = popupQuery,
        offset = completionLayout.offset,
        widthPx = completionLayout.widthPx,
        maxHeightPx = completionLayout.maxHeightPx,
        colorScheme = state.colorScheme,
        isLoading = completionState is CompletionUiState.Loading,
        onSelectedIndexChange = { index -> state.setCompletionSelectedIndex(index) },
        onSelect = { item ->
            state.applyCompletion(item)
        },
        onDismiss = { state.dismissCompletion() }
    )
}

@Composable
internal fun EditorSignatureHelpOverlay(
    session: TinaEditorSession
) {
    val state = session.state
    val signatureState = state.signatureHelpUiState
    if (signatureState is SignatureHelpUiState.Hidden) return

    val resolvedResult = when (signatureState) {
        is SignatureHelpUiState.Visible -> signatureState.result
        is SignatureHelpUiState.Loading -> signatureState.previousResult
        SignatureHelpUiState.Hidden -> null
    }
    val ui = session.ui
    val density = session.density
    val layoutProbe = LocalEditorPopupLayoutProbe.current
    val viewportMetrics = resolveEditorPopupViewportMetrics(density)

    val preferredPopupWidthPx = with(density) { 340.dp.toPx() }
    val popupMaxHeightPx = with(density) { 244.dp.toPx() }
    val popupMinHeightPx = with(density) { 68.dp.toPx() }
    val popupMarginPx = with(density) { 8.dp.toPx() }
    val popupCursorGapPx = with(density) { 10.dp.toPx() }
    val popupNarrowEditorThresholdPx = with(density) { 500.dp.toPx() }
    val displayedSignatureIndex = state.resolveDisplayedSignatureHelpIndex(resolvedResult)
    val activeSignatureIndex = resolvedResult?.let {
        displayedSignatureIndex.coerceIn(0, it.signatures.lastIndex.coerceAtLeast(0))
    } ?: 0
    val activeSignature = resolvedResult?.signatures
        ?.getOrNull(activeSignatureIndex)
        .orEmpty()
    val estimatedCharsPerLine = (preferredPopupWidthPx / state.charWidthPx.coerceAtLeast(1f))
        .toInt()
        .coerceAtLeast(24)
    val estimatedLineCountPerSignature = ceil(
        activeSignature.length.coerceAtLeast(1).toFloat() / estimatedCharsPerLine.toFloat()
    ).toInt().coerceIn(1, 4)
    val visibleSignatureCount = resolvedResult?.signatures?.size?.coerceIn(1, 3) ?: 1
    val preferredContentHeightPx = (
        with(density) { 18.dp.toPx() } +
            if ((resolvedResult?.signatures?.size ?: 0) > 1) {
                with(density) { 34.dp.toPx() }
            } else {
                0f +
                    (
                        state.lineHeightPx * estimatedLineCountPerSignature * 1.1f +
                            with(density) { 18.dp.toPx() }
                        ) * visibleSignatureCount +
                    if (signatureState is SignatureHelpUiState.Loading) with(density) { 20.dp.toPx() } else 0f
            }
        ).coerceAtLeast(popupMinHeightPx)
    val cursorAnchor = resolveCursorPopupAnchor(session)
    val layout = SignatureHelpPopupLayoutResolver.resolve(
        cursorXInViewportPx = cursorAnchor.cursorXInViewportPx,
        cursorLineTopInViewportPx = cursorAnchor.cursorLineTopInViewportPx,
        lineHeightPx = state.lineHeightPx,
        canvasOriginInWindowPx = ui.canvasOriginInWindowPx,
        canvasWidthPx = ui.canvasWidthPx,
        canvasHeightPx = ui.canvasHeightPx,
        windowWidthPx = viewportMetrics.windowWidthPx,
        windowHeightPx = viewportMetrics.windowHeightPx,
        imeBottomInsetPx = viewportMetrics.imeBottomInsetPx,
        preferredPopupWidthPx = preferredPopupWidthPx,
        popupMaxHeightPx = popupMaxHeightPx,
        preferredContentHeightPx = preferredContentHeightPx,
        marginPx = popupMarginPx,
        cursorGapPx = popupCursorGapPx,
        narrowEditorThresholdPx = popupNarrowEditorThresholdPx,
        minPopupHeightPx = popupMinHeightPx
    )
    SideEffect {
        layoutProbe?.onSignatureHelpLayout?.invoke(layout)
    }
    EditorSignatureHelpPopup(
        result = resolvedResult,
        displayedSignatureIndex = displayedSignatureIndex,
        offset = layout.offset,
        widthPx = layout.widthPx,
        minHeightPx = popupMinHeightPx,
        maxHeightPx = layout.maxHeightPx,
        colorScheme = state.colorScheme,
        isLoading = signatureState is SignatureHelpUiState.Loading,
        onSelectSignature = { index -> state.selectSignatureHelp(index) },
        onCycleSignature = { delta -> state.cycleSignatureHelp(delta) },
        onDismiss = { session.interactionController.dismissSignatureHelp() }
    )
}

@Composable
private fun resolveCursorPopupAnchor(
    session: TinaEditorSession
): CursorPopupAnchor {
    val state = session.state
    val density = session.density
    val lineTextLookup = remember(state) { EditorLineTextLookup(state) }
    return CursorPopupAnchorResolver.resolve(
        state = state,
        contentStartXPx = session.ui.contentStartXPx,
        textPaint = session.textPaint,
        lineLayoutCache = session.lineLayoutCache,
        fontSizePx = with(density) { state.fontSizeSp.sp.toPx() },
        lineTextProvider = lineTextLookup::lineText,
        textScanCache = session.textScanCache
    )
}

@Composable
private fun resolveEditorPopupViewportMetrics(
    density: androidx.compose.ui.unit.Density
): EditorPopupViewportMetrics {
    val override = LocalEditorPopupViewportMetricsOverride.current
    if (override != null) return override

    val configuration = LocalConfiguration.current
    val view = LocalView.current
    val fallbackWindowWidthPx = with(density) { configuration.screenWidthDp.dp.toPx() }
    val fallbackWindowHeightPx = with(density) { configuration.screenHeightDp.dp.toPx() }
    return EditorPopupViewportMetrics(
        windowWidthPx = view.rootView.width.toFloat().takeIf { it > 0f } ?: fallbackWindowWidthPx,
        windowHeightPx = view.rootView.height.toFloat().takeIf { it > 0f } ?: fallbackWindowHeightPx,
        imeBottomInsetPx = WindowInsets.ime.getBottom(density).toFloat()
    )
}
