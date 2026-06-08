package com.wuxianggujun.tinaide.core.editorview

import android.graphics.Typeface
import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.wuxianggujun.tinaide.core.editorlsp.SignatureHelpResult
import com.wuxianggujun.tinaide.core.textengine.Position
import com.wuxianggujun.tinaide.core.textengine.TextBuffer
import com.wuxianggujun.tinaide.core.textengine.TextChange
import com.wuxianggujun.tinaide.core.textengine.TextScanKernel
import com.wuxianggujun.tinaide.core.treesitter.SyntaxHighlighter
import com.wuxianggujun.tinaide.core.treesitter.TreeSitterFoldingProvider.FoldRegion
import java.io.File
import java.util.LinkedHashMap
import kotlin.math.floor
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import timber.log.Timber

sealed interface EditorCompletionFetchResult {
    data class Success(val items: List<EditorCompletionItem>) : EditorCompletionFetchResult
    data class TransientFailure(val reason: String? = null) : EditorCompletionFetchResult
}

sealed interface CompletionUiState {
    data object Hidden : CompletionUiState
    data class Loading(
        val previousItems: List<EditorCompletionItem>,
        val query: String,
        val selectedIndex: Int,
        val requestId: Long
    ) : CompletionUiState

    data class Visible(
        val items: List<EditorCompletionItem>,
        val query: String,
        val selectedIndex: Int,
        val requestId: Long
    ) : CompletionUiState
}

sealed interface HoverUiState {
    data object Hidden : HoverUiState
    data object Loading : HoverUiState
    data class Visible(val markdown: String) : HoverUiState
}

sealed interface SignatureHelpUiState {
    data object Hidden : SignatureHelpUiState
    data class Loading(
        val previousResult: SignatureHelpResult?,
        val requestId: Long
    ) : SignatureHelpUiState

    data class Visible(
        val result: SignatureHelpResult,
        val requestId: Long
    ) : SignatureHelpUiState
}

@Stable
class EditorState(
    override val textBuffer: TextBuffer,
    val file: File? = null,
    val projectRootPath: String? = null,
    config: EditorConfig = EditorConfig()
) : EditorStateSnapshot,
    EditorEditOperations {
    private companion object {
        private const val SLOW_OPERATION_THRESHOLD_MS = 16L
        private const val SLOW_OPERATION_LOG_INTERVAL_MS = 800L
        private const val HORIZONTAL_FULL_SCAN_LINE_THRESHOLD = 4_000
        private const val HORIZONTAL_WIDTH_GUARD_CHARS = 32
        private const val HORIZONTAL_WIDTH_BOOTSTRAP_SAMPLES = 96
        private const val HORIZONTAL_WIDTH_SCAN_MAX_BATCH_LINES = 256
        private const val HORIZONTAL_WIDTH_SCAN_CHECK_INTERVAL = 32
        private const val HORIZONTAL_WIDTH_SCAN_TIME_BUDGET_NS = 700_000L
    }

    private var lastSlowOperationLogAtMs: Long = 0L

    private var _config by mutableStateOf(config)
    var config: EditorConfig
        get() = _config
        set(value) {
            val old = _config
            if (old == value) return
            _config = value
            onConfigChanged(old = old, new = value)
        }
    var typeface by mutableStateOf<Typeface>(Typeface.MONOSPACE)
    var colorScheme by mutableStateOf(EditorColorScheme.builtinGray())

    override var cursorOffset by mutableStateOf(0)
    override var selectionRange by mutableStateOf<OffsetRange?>(null)

    private var cachedCursorOffset = -1
    private var cachedCursorVersion = -1L
    private var cachedCursorPosition = Position(0, 0)

    val cursorPosition: Position
        get() {
            val offset = cursorOffset.coerceIn(0, textBuffer.length)
            val version = textBuffer.version
            if (offset == cachedCursorOffset && version == cachedCursorVersion) {
                return cachedCursorPosition
            }
            val pos = textBuffer.offsetToPosition(offset)
            cachedCursorOffset = offset
            cachedCursorVersion = version
            cachedCursorPosition = pos
            return pos
        }

    val cursorLine: Int get() = cursorPosition.line
    val cursorColumn: Int get() = cursorPosition.column

    private fun cursorLineColumn(): Pair<Int, Int> {
        val pos = cursorPosition
        return pos.line to pos.column
    }
    var fontSizeSp by mutableStateOf(config.fontSizeSp)
    var pinLineNumber by mutableStateOf(config.pinLineNumber)

    var scrollOffsetPx by mutableStateOf(0f)
    var scrollOffsetXPx by mutableStateOf(0f)
    var viewportHeightPx by mutableStateOf(1f)
    var viewportWidthPx by mutableStateOf(1f)

    // 文本区起始位置（canvas 坐标）。用于处理“行号栏是否跟随横向滚动”的坐标收敛。
    var contentStartXPx by mutableStateOf(0f)
    var lineHeightPx by mutableStateOf(1f)
    var charWidthPx by mutableStateOf(1f)
    var isFocused by mutableStateOf(false)
    var cursorBlinkVisible by mutableStateOf(true)

    /**
     * 双指缩放锚点：用于在字体缩放导致 wordWrap 重排时，保持“手指附近的文本”尽可能稳定。
     *
     * 为什么需要它：
     * - 仅按像素比例缩放 scrollOffset（(old+focus)*scale-focus）在 wordWrap 场景下会失效：
     *   wrapColumns 会随着字宽变化而变化，视觉行会重排，导致缩放过程中内容“漂移”。
     * - 我们改为记录缩放发生时焦点附近的 charOffset，在下一帧 metrics 更新后按新布局回推 scrollOffset。
     *
     * 生命周期：
     * - 手势侧在修改 fontSize 前写入
     * - 下一帧 [updateMetrics] 应用并清空
     */
    internal data class PendingScaleAnchor(
        val charOffset: Int,
        val focusX: Float,
        val focusY: Float,
        val focusYInVisualLineRatio: Float
    )

    internal var pendingScaleAnchor: PendingScaleAnchor? = null

    /**
     * wordWrap 缩放策略（对齐 Sora）：
     * - 双指缩放过程中，如果每次 fontSize 改变都立刻用新的 charWidth 重新计算 wrapColumns，
     *   会导致视觉行重排，从而出现“手指附近文本漂移/回弹”的观感。
     * - Sora 的做法是：缩放过程中冻结 wordwrap 布局，结束后再重建并做一次锚点回推。
     *
     * 这里用 frozenWordWrapColumns 实现同样的冻结效果：
     * - frozen != null 时，visualLineMap() 使用 frozen 的 wrapColumns（保持视觉行分段不变）
     * - 缩放结束后再清空 frozen，让布局按新字体度量重新分段，并结合 [PendingScaleAnchor] 做最终对齐
     */
    internal var frozenWordWrapColumns by mutableStateOf<Int?>(null)
        private set

    internal fun isWordWrapLayoutFrozen(): Boolean = frozenWordWrapColumns != null

    internal fun freezeWordWrapLayoutIfNeeded() {
        if (!config.wordWrap) return
        if (frozenWordWrapColumns != null) return
        val safeCharWidth = charWidthPx.coerceAtLeast(1f)
        val safeViewportWidth = viewportWidthPx.coerceAtLeast(1f)
        frozenWordWrapColumns = (safeViewportWidth / safeCharWidth).toInt().coerceAtLeast(1)
    }

    internal fun unfreezeWordWrapLayout() {
        if (frozenWordWrapColumns == null) return
        frozenWordWrapColumns = null
    }

    /**
     * 可选的“列号 -> X 像素”解析器（X 为文本行内坐标：从行首开始累加的宽度）。
     *
     * 为什么需要它：
     * - 编辑器渲染与命中测试已经统一到 [EditorLineLayoutCache]（prefix advances）。
     * - 但滚动对齐（ensureCursorVisible / 自动滚动）如果仍用 `column * charWidthPx`，
     *   在包含 CJK/组合字符/不同 glyph advance 的情况下会出现“内容突然左右偏移”的观感差异。
     *
     * 由 UI 层（Session）注入，确保使用与渲染一致的 Paint/字体度量。
     */
    internal var columnXInTextPxResolver: ((line: Int, column: Int) -> Float)? = null

    // 用于触发 Compose 重组的状态，每次文本变化时更新
    internal var textVersion by mutableStateOf(textBuffer.version)
        private set

    internal var highlightVersion by mutableStateOf(0L)
        private set

    fun notifyHighlightChanged() {
        highlightVersion++
        bumpStylingVersion()
    }

    internal var semanticTokensVersion by mutableStateOf(0L)
        private set

    /**
     * 统一的 styling 版本号：每次 syntax 或 semantic 更新都推进，最晚到达者负责递增。
     *
     * 作用：让渲染 / cache / LaunchedEffect 可以通过单一 key 感知 styling 变化，
     * 消除「新语法 + 旧语义」这种短暂错位帧（因为两类更新都同步推进同一计数器，
     * 下游 key 变化只需要比较一个值）。
     */
    internal var effectiveStylingVersion by mutableStateOf(0L)
        private set

    private fun bumpStylingVersion() {
        effectiveStylingVersion++
    }

    private var cachedMaxLineChars = 0
    private var cachedMaxLineCharsVersion = -1L
    private var widthScanVersion = -1L
    private var widthScanNextLine = 0
    private var widthScanMaxChars = 0
    private var widthScanLineLengths = IntArray(0)
    private var widthLineSnapshotVersion = -1L
    private var widthLineMaxChars = 0
    private var widthLineLengths = IntArray(0)

    var completionItems by mutableStateOf<List<EditorCompletionItem>>(emptyList())
        private set
    var completionSelectedIndex by mutableStateOf(-1)
        private set
    var completionQuery by mutableStateOf("")
        private set
    var completionUiState by mutableStateOf<CompletionUiState>(CompletionUiState.Hidden)
        private set
    val showCompletion: Boolean
        get() = when (val ui = completionUiState) {
            is CompletionUiState.Visible -> ui.items.isNotEmpty()
            is CompletionUiState.Loading -> ui.previousItems.isNotEmpty()
            CompletionUiState.Hidden -> completionItems.isNotEmpty()
        }
    var hoverUiState by mutableStateOf<HoverUiState>(HoverUiState.Hidden)
        private set
    var signatureHelpUiState by mutableStateOf<SignatureHelpUiState>(SignatureHelpUiState.Hidden)
        private set
    var signatureHelpSelectedSignatureIndex by mutableStateOf<Int?>(null)
        private set
    internal var completionRequestSeq: Long = 0L
    internal var activeCompletionRequestId: Long = 0L
    internal var hoverRequestSeq: Long = 0L
    internal var activeHoverRequestId: Long = 0L
    internal var signatureHelpRequestSeq: Long = 0L
    internal var activeSignatureHelpRequestId: Long = 0L
    internal var cachedCompletionResults: List<EditorCompletionItem> = emptyList()
    internal var cachedCompletionPrefix: String = ""
    internal var snippetChoiceCompletionActive by mutableStateOf(false)
    internal var activeSnippetSession by mutableStateOf<SnippetSession?>(null)
        private set

    private fun publishCompletionLoading(
        previousItems: List<EditorCompletionItem>,
        query: String,
        selectedIndex: Int,
        requestId: Long
    ) {
        val normalizedSelectedIndex = if (previousItems.isEmpty()) {
            -1
        } else {
            selectedIndex.coerceIn(0, previousItems.lastIndex)
        }
        completionItems = previousItems
        completionQuery = query
        completionSelectedIndex = normalizedSelectedIndex
        completionUiState = CompletionUiState.Loading(
            previousItems = previousItems,
            query = query,
            selectedIndex = normalizedSelectedIndex,
            requestId = requestId
        )
    }

    private fun publishCompletionResults(
        items: List<EditorCompletionItem>,
        query: String,
        selectedIndex: Int,
        requestId: Long
    ) {
        val normalizedSelectedIndex = if (items.isEmpty()) {
            -1
        } else {
            selectedIndex.coerceIn(0, items.lastIndex)
        }
        completionQuery = query
        completionItems = items
        completionSelectedIndex = normalizedSelectedIndex
        completionUiState = if (items.isEmpty()) {
            CompletionUiState.Hidden
        } else {
            CompletionUiState.Visible(
                items = items,
                query = query,
                selectedIndex = normalizedSelectedIndex,
                requestId = requestId
            )
        }
    }

    private fun publishCompletionSelection(index: Int) {
        completionSelectedIndex = index
        completionUiState = when (val ui = completionUiState) {
            is CompletionUiState.Visible -> ui.copy(selectedIndex = index)
            is CompletionUiState.Loading -> ui.copy(selectedIndex = index)
            CompletionUiState.Hidden -> CompletionUiState.Hidden
        }
    }

    internal fun publishHoverLoading() {
        hoverUiState = HoverUiState.Loading
    }

    internal fun publishHoverVisible(markdown: String) {
        val normalizedMarkdown = markdown.trim()
        if (normalizedMarkdown.isEmpty()) {
            clearHover()
            return
        }
        hoverUiState = HoverUiState.Visible(normalizedMarkdown)
    }

    private fun clearHover() {
        hoverUiState = HoverUiState.Hidden
    }

    internal fun publishSignatureHelpLoading(
        previousResult: SignatureHelpResult?,
        requestId: Long,
        selectedIndex: Int? = signatureHelpSelectedSignatureIndex
    ) {
        signatureHelpSelectedSignatureIndex =
            normalizeSignatureHelpSelection(previousResult, selectedIndex)
        signatureHelpUiState = SignatureHelpUiState.Loading(
            previousResult = previousResult,
            requestId = requestId
        )
    }

    internal fun publishSignatureHelpVisible(
        result: SignatureHelpResult,
        requestId: Long,
        selectedIndex: Int? = signatureHelpSelectedSignatureIndex
    ) {
        val normalized = normalizeSignatureHelpResult(result)
        if (normalized == null) {
            clearSignatureHelp()
            return
        }
        signatureHelpSelectedSignatureIndex =
            normalizeSignatureHelpSelection(normalized, selectedIndex)
        signatureHelpUiState = SignatureHelpUiState.Visible(
            result = normalized,
            requestId = requestId
        )
    }

    private fun publishSignatureHelpSelection(index: Int) {
        signatureHelpSelectedSignatureIndex =
            normalizeSignatureHelpSelection(currentSignatureHelpResult(), index)
    }

    private fun clearSignatureHelp() {
        signatureHelpSelectedSignatureIndex = null
        signatureHelpUiState = SignatureHelpUiState.Hidden
    }

    var highlighter by mutableStateOf<SyntaxHighlighter?>(null)
    var semanticTokens by mutableStateOf<List<SemanticToken>>(emptyList())
    var semanticTokensByLine by mutableStateOf<Map<Int, List<SemanticToken>>>(emptyMap())
    var diagnostics by mutableStateOf<List<EditorDiagnostic>>(emptyList())
    var diagnosticsByLine by mutableStateOf<Map<Int, List<EditorDiagnostic>>>(emptyMap())
    private var diagnosticLinesSortedRef: Map<Int, List<EditorDiagnostic>>? = null
    private var diagnosticLinesSorted: IntArray = IntArray(0)
        get() {
            val current = diagnosticsByLine
            if (current !== diagnosticLinesSortedRef) {
                diagnosticLinesSortedRef = current
                field = current.keys.toIntArray().also { it.sort() }
            }
            return field
        }
    val gutterDecorations = mutableStateMapOf<Int, GutterDecoration>()

    private val wordWrapLayoutCache = EditorWordWrapLayoutCache()

    fun clearSemanticTokens() {
        if (semanticTokens.isEmpty() && semanticTokensByLine.isEmpty()) return
        semanticTokens = emptyList()
        semanticTokensByLine = emptyMap()
        semanticTokensVersion++
        bumpStylingVersion()
    }

    fun replaceSemanticTokens(tokens: List<SemanticToken>) {
        val groupedByLine = tokens.groupBy { it.line }
        if (semanticTokens == tokens && semanticTokensByLine == groupedByLine) return
        semanticTokens = tokens
        semanticTokensByLine = groupedByLine
        semanticTokensVersion++
        bumpStylingVersion()
    }

    fun mergeSemanticTokens(tokens: List<SemanticToken>) {
        if (tokens.isEmpty()) return

        val groupedByLine = tokens.groupBy { it.line }
        val mergedByLine = semanticTokensByLine.toMutableMap()
        var changed = false

        groupedByLine.forEach { (line, lineTokens) ->
            if (mergedByLine[line] != lineTokens) {
                mergedByLine[line] = lineTokens
                changed = true
            }
        }

        if (!changed) return

        semanticTokensByLine = mergedByLine
        semanticTokens = mergedByLine.values.flatten()
        semanticTokensVersion++
        bumpStylingVersion()
    }

    internal fun applyTextChangeToSemanticTokens(change: TextChange) {
        val currentByLine = semanticTokensByLine
        if (currentByLine.isEmpty()) return

        val startLine = change.startLine.coerceAtLeast(0)
        val oldChangedEndLine = when {
            change.startColumn == 0 &&
                change.endColumn == 0 &&
                change.oldText.endsWith('\n') ->
                (change.endLine - 1).coerceAtLeast(startLine)

            else -> change.endLine.coerceAtLeast(startLine)
        }
        val lineDelta = change.lineDelta
        val shiftFromLine = (oldChangedEndLine + 1).coerceAtLeast(0)

        val updatedByLine = LinkedHashMap<Int, List<SemanticToken>>(currentByLine.size)
        currentByLine.entries
            .sortedBy { it.key }
            .forEach { (line, tokens) ->
                if (line in startLine..oldChangedEndLine) {
                    return@forEach
                }

                val targetLine = if (line >= shiftFromLine) {
                    line + lineDelta
                } else {
                    line
                }
                if (targetLine < 0) return@forEach

                updatedByLine[targetLine] = if (targetLine == line) {
                    tokens
                } else {
                    tokens.map { token -> token.copy(line = targetLine) }
                }
            }

        if (updatedByLine == currentByLine) return

        semanticTokensByLine = updatedByLine
        semanticTokens = updatedByLine.values.flatten()
        semanticTokensVersion++
        bumpStylingVersion()
    }

    // ========== 代码折叠（按行隐藏） ==========
    private var foldRegions: List<FoldRegion> by mutableStateOf(emptyList())
    private var foldRegionsDocumentVersion by mutableStateOf(-1L)
    private var foldRegionByStartLine: Map<Int, FoldRegion> = emptyMap()
    private var collapsedFoldStartLines by mutableStateOf<Set<Int>>(emptySet())
    private var foldDataVersion by mutableStateOf(0)

    private data class BrokenFoldRecord(val startLine: Int, val originalEndLine: Int)
    private val brokenFoldRecords = mutableSetOf<BrokenFoldRecord>()

    private data class LineMap(
        val docLineCount: Int,
        val visualToDocLine: IntArray,
        val docToVisualLine: IntArray,
        val hiddenDocLine: BooleanArray,
        val hiddenOwnerStartLine: IntArray
    ) {
        val visualLineCount: Int
            get() = visualToDocLine.size
    }

    private data class VisualLineMap(
        val docLineCount: Int,
        /**
         * folding 后的“可见文档行列表”（索引=折叠后的可见行序号，值=docLine）。
         *
         * 注意：这不是最终的 visualLine（因为每个 docLine 可能会被 wordWrap 拆成多段）。
         */
        val visibleDocLines: IntArray,
        /** 每个 visibleDocLine 对应的“首个视觉行”索引（按 wordWrap 展开后）。 */
        val firstVisualLineByVisibleIndex: IntArray,
        /** 每个 visibleDocLine 对应的“视觉行段数”（>=1）。 */
        val visualLineCountByVisibleIndex: IntArray,
        /** 全部视觉行总数（folding + wordWrap 后）。 */
        val visualLineCount: Int,
        val wordWrapEnabled: Boolean,
        val wrapColumns: Int
    ) {
        val visibleDocLineCount: Int
            get() = visibleDocLines.size
    }

    private var lineMapCache: LineMap? = null
    private var lineMapCacheFoldDataVersion: Int = Int.MIN_VALUE
    private var lineMapCacheFoldingEnabled: Boolean = false

    private var visualLineMapCache: VisualLineMap? = null
    private var visualLineMapCacheEpoch: Long = Long.MIN_VALUE

    // 按文档行缓存 wrap segmentCount（wordWrap 下每行视觉行数）。
    // 替代原本每次 visualLineMap() 重算时对全部可见文档行做 getLine()+getWrapLayout() 的 O(N) 扫描；
    // 文本变化时只重算编辑窗内的行，其余行直接从数组读。
    private var docSegmentCounts: IntArray? = null
    private var docSegmentCountsWrapColumns: Int = Int.MIN_VALUE
    private var docSegmentCountsTabSize: Int = Int.MIN_VALUE
    private var docSegmentCountsVersion: Long = Long.MIN_VALUE

    private var visualLineMapEpochCounter: Long = 0L
    private var vlmEpochTextVersion: Long = Long.MIN_VALUE
    private var vlmEpochFoldDataVersion: Int = Int.MIN_VALUE
    private var vlmEpochFoldingEnabled: Boolean = false
    private var vlmEpochWordWrap: Boolean = false
    private var vlmEpochWrapColumns: Int = Int.MIN_VALUE
    private var vlmEpochTabSize: Int = Int.MIN_VALUE
    private var vlmEpochDocLineCount: Int = Int.MIN_VALUE

    private fun visualLineMapEpoch(
        textVersion: Long,
        foldDataVersion: Int,
        foldingEnabled: Boolean,
        wordWrap: Boolean,
        wrapColumns: Int,
        tabSize: Int,
        docLineCount: Int
    ): Long {
        val effectiveTextVersion = if (wordWrap) textVersion else 0L
        if (effectiveTextVersion == vlmEpochTextVersion &&
            foldDataVersion == vlmEpochFoldDataVersion &&
            foldingEnabled == vlmEpochFoldingEnabled &&
            wordWrap == vlmEpochWordWrap &&
            wrapColumns == vlmEpochWrapColumns &&
            tabSize == vlmEpochTabSize &&
            docLineCount == vlmEpochDocLineCount
        ) {
            return visualLineMapEpochCounter
        }
        vlmEpochTextVersion = effectiveTextVersion
        vlmEpochFoldDataVersion = foldDataVersion
        vlmEpochFoldingEnabled = foldingEnabled
        vlmEpochWordWrap = wordWrap
        vlmEpochWrapColumns = wrapColumns
        vlmEpochTabSize = tabSize
        vlmEpochDocLineCount = docLineCount
        return ++visualLineMapEpochCounter
    }

    var useRelativeLineNumbers by mutableStateOf(config.useRelativeLineNumbers)

    var onLineNumberTap: ((line: Int) -> Unit)? = null
    var onLineNumberLongPress: ((line: Int) -> Unit)? = null
    var onRequestCompletion: (suspend (Position, Char?) -> EditorCompletionFetchResult)? = null
    var onRequestHover: (suspend (Position) -> String?)? = null
    var onRequestSignatureHelp: (suspend (Position) -> SignatureHelpResult?)? = null
    var onRequestPeekDefinition: (() -> Unit)? = null
    var onRequestGotoDefinition: (() -> Unit)? = null
    var onRequestFindReferences: (() -> Unit)? = null
    var onRequestGotoTypeDefinition: (() -> Unit)? = null
    var onRequestGotoImplementation: (() -> Unit)? = null
    var onRequestCodeActions: (() -> Unit)? = null
    var onRequestRenameSymbol: (() -> Unit)? = null
    var onRequestSwitchHeaderSource: (() -> Unit)? = null
    var onGutterTap: ((line: Int) -> Unit)? = null
    var onGutterFoldToggle: ((line: Int) -> Unit)? = null
    var onGutterLongPress: ((line: Int) -> Unit)? = null

    private val _events = MutableSharedFlow<EditorEvent>(
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val events: SharedFlow<EditorEvent> = _events

    /**
     * 当前视口顶部对应的“视觉行”索引（折叠后：视觉行 != 文档行）。
     */
    val firstVisibleLine: Int
        get() = floor(scrollOffsetPx / lineHeightPx).toInt().coerceAtLeast(0)

    val visibleLines by derivedStateOf {
        // 依赖 textVersion 以确保文本变化时重新计算
        @Suppress("UNUSED_EXPRESSION")
        textVersion
        // 依赖 foldDataVersion 以确保折叠状态变化时重新计算
        @Suppress("UNUSED_EXPRESSION")
        foldDataVersion
        // 依赖 config.codeFolding，避免开关变化后 visibleLines 不刷新
        @Suppress("UNUSED_EXPRESSION")
        config.codeFolding
        // 依赖 wordWrap/tabSize 等，避免“设置变化但可见窗口不刷新”
        @Suppress("UNUSED_EXPRESSION")
        config.wordWrap
        @Suppress("UNUSED_EXPRESSION")
        config.tabSize
        // 依赖 wordWrap 冻结状态，保证缩放结束后解除冻结能触发可视窗口重新计算。
        @Suppress("UNUSED_EXPRESSION")
        frozenWordWrapColumns
        val first = firstVisibleLine
        val visibleCount = (viewportHeightPx / lineHeightPx).toInt() + 2
        val maxVisualLine = (visualLineCount() - 1).coerceAtLeast(0)
        val last = (first + visibleCount).coerceAtMost(maxVisualLine)
        first..last
    }

    /**
     * 供 LSP / 语义 Token / 语法高亮等“按文档行”请求使用的可见范围。
     *
     * 注意：折叠后可见的文档行并非连续，但多数外部接口仅接受 IntRange，
     * 这里返回“可见视觉窗口对应的首/末文档行”形成的范围（可能包含被折叠隐藏的行）。
     */
    val visibleDocumentLines: IntRange
        get() {
            val visualRange = visibleLines
            if (visualRange.isEmpty()) return 0..-1
            val totalVisualLines = visualLineCount()
            if (totalVisualLines <= 0) return 0..-1
            val firstVisual = visualRange.first.coerceIn(0, totalVisualLines - 1)
            val lastVisual = visualRange.last.coerceIn(firstVisual, totalVisualLines - 1)
            val firstDoc = docLineForVisualLine(firstVisual)
            val lastDoc = docLineForVisualLine(lastVisual)
            return firstDoc..lastDoc
        }

    internal fun docLineForVisualLine(visualLine: Int): Int {
        val map = visualLineMap()
        if (map.visualLineCount <= 0) return 0
        val safeVisual = visualLine.coerceIn(0, map.visualLineCount - 1)
        val visibleIndex = resolveVisibleIndexForVisualLine(map, safeVisual)
        return map.visibleDocLines.getOrElse(visibleIndex) { 0 }
    }

    internal fun visualLineForDocLine(docLine: Int): Int {
        val map = visualLineMap()
        if (map.visibleDocLineCount <= 0) return 0
        val visibleIndex = resolveVisibleIndexForDocLine(docLine)
        if (visibleIndex < 0) return 0
        return map.firstVisualLineByVisibleIndex.getOrElse(visibleIndex) { 0 }
    }

    internal fun visualLineForPosition(line: Int, column: Int): Int {
        val map = visualLineMap()
        if (map.visibleDocLineCount <= 0 || map.visualLineCount <= 0) return 0
        val visibleIndex = resolveVisibleIndexForDocLine(line)
        if (visibleIndex < 0) return 0
        val firstVisual = map.firstVisualLineByVisibleIndex.getOrElse(visibleIndex) { 0 }
        if (!map.wordWrapEnabled) {
            return firstVisual.coerceIn(0, map.visualLineCount - 1)
        }

        val docLine = map.visibleDocLines.getOrElse(visibleIndex) { 0 }
        val lineText = textBuffer.getLine(docLine)
        val safeColumn = column.coerceIn(0, lineText.length)
        val layout = wordWrapLayoutCache.getWrapLayout(
            line = docLine,
            lineText = lineText,
            textVersion = textBuffer.version,
            wrapColumns = map.wrapColumns,
            tabSize = config.tabSize
        )
        val segmentIndex = layout.segmentIndexForColumn(safeColumn)
        return (firstVisual + segmentIndex).coerceIn(0, map.visualLineCount - 1)
    }

    internal fun visualLineStartColumn(visualLine: Int): Int {
        val map = visualLineMap()
        if (!map.wordWrapEnabled || map.visualLineCount <= 0) return 0
        val safeVisual = visualLine.coerceIn(0, map.visualLineCount - 1)
        val visibleIndex = resolveVisibleIndexForVisualLine(map, safeVisual)
        val docLine = map.visibleDocLines.getOrElse(visibleIndex) { 0 }
        val lineText = textBuffer.getLine(docLine)
        val firstVisual = map.firstVisualLineByVisibleIndex.getOrElse(visibleIndex) { 0 }
        val segmentIndex = (safeVisual - firstVisual).coerceAtLeast(0)
        val layout = wordWrapLayoutCache.getWrapLayout(
            line = docLine,
            lineText = lineText,
            textVersion = textBuffer.version,
            wrapColumns = map.wrapColumns,
            tabSize = config.tabSize
        )
        return layout.startColumnForSegment(segmentIndex).coerceIn(0, lineText.length)
    }

    internal fun visualLineEndColumn(visualLine: Int): Int {
        val map = visualLineMap()
        if (!map.wordWrapEnabled || map.visualLineCount <= 0) {
            val docLine = docLineForVisualLine(visualLine)
            return textBuffer.getLine(docLine).length
        }
        val safeVisual = visualLine.coerceIn(0, map.visualLineCount - 1)
        val visibleIndex = resolveVisibleIndexForVisualLine(map, safeVisual)
        val docLine = map.visibleDocLines.getOrElse(visibleIndex) { 0 }
        val lineText = textBuffer.getLine(docLine)
        val firstVisual = map.firstVisualLineByVisibleIndex.getOrElse(visibleIndex) { 0 }
        val segmentIndex = (safeVisual - firstVisual).coerceAtLeast(0)
        val layout = wordWrapLayoutCache.getWrapLayout(
            line = docLine,
            lineText = lineText,
            textVersion = textBuffer.version,
            wrapColumns = map.wrapColumns,
            tabSize = config.tabSize
        )
        return layout.endColumnForSegment(segmentIndex).coerceIn(0, lineText.length)
    }

    internal fun isVisualLineContinuation(visualLine: Int): Boolean = visualLineStartColumn(visualLine) > 0

    internal fun isDocLineHidden(docLine: Int): Boolean {
        val map = lineMap()
        if (map.docLineCount <= 0) return false
        val safeLine = docLine.coerceIn(0, map.docLineCount - 1)
        return map.hiddenDocLine[safeLine]
    }

    internal fun visualLineTopInViewport(visualLine: Int): Float {
        val firstLineOffset = scrollOffsetPx - firstVisibleLine * lineHeightPx
        return (visualLine - firstVisibleLine) * lineHeightPx - firstLineOffset
    }

    fun lineTopInViewport(line: Int): Float {
        val lineCount = textBuffer.lineCount
        if (lineCount <= 0) return 0f
        val safeLine = line.coerceIn(0, lineCount - 1)
        val visualLine = visualLineForDocLine(safeLine)
        return visualLineTopInViewport(visualLine)
    }

    fun updateFocus(focused: Boolean) {
        if (isFocused == focused) return
        isFocused = focused
        emitEvent(EditorEvent.FocusChanged(focused))
    }

    fun updateMetrics(
        lineHeightPx: Float,
        charWidthPx: Float,
        viewportHeightPx: Float,
        viewportWidthPx: Float,
        contentStartXPx: Float
    ) {
        val oldX = scrollOffsetXPx
        val oldY = scrollOffsetPx
        this.lineHeightPx = lineHeightPx.coerceAtLeast(1f)
        this.charWidthPx = charWidthPx.coerceAtLeast(1f)
        this.viewportHeightPx = viewportHeightPx.coerceAtLeast(1f)
        this.viewportWidthPx = viewportWidthPx.coerceAtLeast(1f)
        this.contentStartXPx = contentStartXPx.coerceAtLeast(0f)
        pendingScaleAnchor?.let { anchor ->
            pendingScaleAnchor = null
            applyScaleAnchor(anchor)
        }
        clampScroll()
        emitScrollChangedIfNeeded(oldX = oldX, oldY = oldY)
    }

    private fun applyScaleAnchor(anchor: PendingScaleAnchor) {
        val lineCount = textBuffer.lineCount
        if (lineCount <= 0) return
        val safeOffset = anchor.charOffset.coerceIn(0, textBuffer.length)
        val pos = textBuffer.offsetToPosition(safeOffset)
        val safeLine = pos.line.coerceIn(0, lineCount - 1)
        val safeColumn = pos.column.coerceIn(0, textBuffer.getLine(safeLine).length)

        val ratio = anchor.focusYInVisualLineRatio.coerceIn(0f, 1f)
        val visualLine = visualLineForPosition(safeLine, safeColumn)
        val targetContentY = visualLine * lineHeightPx + ratio * lineHeightPx
        scrollOffsetPx = targetContentY - anchor.focusY

        // wordWrap 开启时横向滚动被禁用（maxScrollXPx=0），无需尝试做 X 锚定。
        if (config.wordWrap) {
            scrollOffsetXPx = 0f
            return
        }

        val resolver = columnXInTextPxResolver
        val xInText = resolver?.invoke(safeLine, safeColumn) ?: (safeColumn * charWidthPx)
        scrollOffsetXPx = contentStartXPx + xInText - anchor.focusX
    }

    fun scrollBy(deltaPx: Float) {
        val oldX = scrollOffsetXPx
        val oldY = scrollOffsetPx
        scrollOffsetPx = (scrollOffsetPx + deltaPx).coerceIn(0f, maxScrollPx())
        emitScrollChangedIfNeeded(oldX = oldX, oldY = oldY)
    }

    fun scrollByX(deltaPx: Float) {
        val oldX = scrollOffsetXPx
        val oldY = scrollOffsetPx
        scrollOffsetXPx = (scrollOffsetXPx + deltaPx).coerceIn(0f, maxScrollXPx())
        emitScrollChangedIfNeeded(oldX = oldX, oldY = oldY)
    }

    fun maxVerticalScrollOffsetPx(): Float = maxScrollPx()

    fun maxHorizontalScrollOffsetPx(): Float = maxScrollXPx()

    fun scrollToLine(line: Int) {
        val oldX = scrollOffsetXPx
        val oldY = scrollOffsetPx
        val visualLine = resolveVisualLineForDocLine(line)
        scrollOffsetPx = (visualLine * lineHeightPx).coerceIn(0f, maxScrollPx())
        emitScrollChangedIfNeeded(oldX = oldX, oldY = oldY)
    }

    override fun moveCursorTo(offset: Int, clearSelection: Boolean) {
        val oldCursor = cursorOffset
        val oldSelection = selectionRange
        val oldX = scrollOffsetXPx
        val oldY = scrollOffsetPx
        val safeOffset = offset.coerceIn(0, textBuffer.length)
        val pos = textBuffer.offsetToPosition(safeOffset)
        revealLineIfFolded(pos.line)
        val clampedOffset = safeOffset.coerceIn(0, textBuffer.length)
        cursorOffset = clampedOffset
        val curPos = if (clampedOffset == safeOffset) pos else textBuffer.offsetToPosition(clampedOffset)
        ensureCursorVisible(curPos.line, curPos.column)
        if (isFocused) {
            cursorBlinkVisible = true
        }
        if (clearSelection) {
            selectionRange = null
        }
        if (oldCursor != cursorOffset) {
            emitEvent(EditorEvent.CursorMoved(oldCursor, cursorOffset))
        }
        if (oldSelection != selectionRange) {
            emitEvent(EditorEvent.SelectionChanged(selectionRange))
        }
        emitScrollChangedIfNeeded(oldX = oldX, oldY = oldY)
    }

    fun ensureCursorVisible() {
        val (line, column) = cursorLineColumn()
        ensureCursorVisible(line, column)
    }

    /**
     * 仅做“纵向”光标可见性对齐。
     *
     * 设计目标：
     * - IME 弹出/收起、窗口高度变化时，确保光标不会被遮挡
     * - 避免自动调整 X 轴导致视口在缩放/横向浏览后“吸附回弹”
     *
     * 说明：
     * - 用户主动移动光标/输入时仍会走 [moveCursorTo] → 完整的 ensureCursorVisible(X+Y)
     */
    internal fun ensureCursorVisibleVertically() {
        val oldX = scrollOffsetXPx
        val oldY = scrollOffsetPx
        val (line, column) = cursorLineColumn()
        ensureCursorVisibleVertically(line, column)
        emitScrollChangedIfNeeded(oldX = oldX, oldY = oldY)
    }

    private fun ensureCursorVisibleVertically(line: Int, column: Int) {
        val safeLine = line.coerceIn(0, (textBuffer.lineCount - 1).coerceAtLeast(0))
        val visualLine = visualLineForPosition(safeLine, column)
        val top = visualLine * lineHeightPx
        val bottom = top + lineHeightPx
        val paddingY = (lineHeightPx * 1.4f)
            .coerceAtMost(viewportHeightPx * 0.25f)
            .coerceAtLeast(0f)
        scrollOffsetPx = when {
            top < scrollOffsetPx + paddingY -> {
                top - paddingY
            }

            bottom > scrollOffsetPx + viewportHeightPx - paddingY -> {
                bottom - (viewportHeightPx - paddingY)
            }

            else -> scrollOffsetPx
        }.coerceIn(0f, maxScrollPx())
    }

    fun setCursorFromPoint(xPx: Float, yPx: Float, textStartXPx: Float) {
        val line = lineFromViewportY(yPx)
        val lineText = textBuffer.getLine(line)
        val contentX = (xPx - textStartXPx + scrollOffsetXPx).coerceAtLeast(0f)
        val column = (contentX / charWidthPx).toInt().coerceIn(0, lineText.length)
        moveCursorTo(textBuffer.positionToOffset(line, column))
    }

    fun lineFromViewportY(yPx: Float): Int {
        val visualLine = visualLineFromViewportY(yPx)
        return docLineForVisualLine(visualLine)
    }

    internal fun visualLineFromViewportY(yPx: Float): Int {
        val map = visualLineMap()
        if (map.visualLineCount <= 0) return 0
        return ((yPx + scrollOffsetPx) / lineHeightPx).toInt()
            .coerceIn(0, (map.visualLineCount - 1).coerceAtLeast(0))
    }

    fun dispatchGutterTap(line: Int) {
        // fold 优先：gutter 点击在“可折叠行”上默认触发折叠开关（更符合常见编辑器习惯），
        // bookmark 等次要标记建议通过长按或其它入口操作。
        if (gutterDecorations[line]?.foldable == true) {
            onGutterFoldToggle?.invoke(line)
            return
        }
        onGutterTap?.invoke(line)
    }

    fun dispatchGutterFoldToggle(line: Int) {
        if (gutterDecorations[line]?.foldable == true) {
            onGutterFoldToggle?.invoke(line)
            return
        }
        onGutterTap?.invoke(line)
    }

    fun dispatchGutterLongPress(line: Int) {
        onGutterLongPress?.invoke(line)
    }

    override fun insert(text: String) {
        editorInsert(this, text)
    }

    override fun backspace() {
        editorBackspace(this)
    }

    override fun deleteForward() {
        editorDeleteForward(this)
    }

    fun moveLeft(extendSelection: Boolean = false) {
        if (!extendSelection) {
            val range = selectionRange
            if (range != null && !range.isEmpty) {
                moveCursorTo(range.start)
                return
            }
        }
        if (cursorOffset <= 0) return
        val step = if (cursorOffset >= 2 &&
            textBuffer.charAt(cursorOffset - 1)?.let(Character::isLowSurrogate) == true
        ) {
            2
        } else {
            1
        }
        moveCursorToWithOptionalSelection(
            offset = skipFoldBackwardIfHidden(cursorOffset - step),
            extendSelection = extendSelection
        )
    }

    fun moveRight(extendSelection: Boolean = false) {
        if (!extendSelection) {
            val range = selectionRange
            if (range != null && !range.isEmpty) {
                moveCursorTo(range.end)
                return
            }
        }
        if (cursorOffset >= textBuffer.length) return
        val step = if (cursorOffset < textBuffer.length - 1 &&
            textBuffer.charAt(cursorOffset)?.let(Character::isHighSurrogate) == true
        ) {
            2
        } else {
            1
        }
        moveCursorToWithOptionalSelection(
            offset = skipFoldForwardIfHidden(cursorOffset + step),
            extendSelection = extendSelection
        )
    }

    /**
     * 向右移动时跳过折叠内部隐藏行。
     * 折叠末行是虚拟可见的（光标可以停留），但折叠内部行（startLine+1..endLine-1）不可停留。
     */
    private fun skipFoldForwardIfHidden(offset: Int): Int {
        if (!isFoldingDataValid()) return offset
        val safeOffset = offset.coerceIn(0, textBuffer.length)
        val pos = textBuffer.offsetToPosition(safeOffset)
        val map = lineMap()
        if (pos.line >= map.docLineCount || !map.hiddenDocLine[pos.line]) return offset
        val ownerStart = map.hiddenOwnerStartLine[pos.line]
        if (ownerStart < 0 || ownerStart !in collapsedFoldStartLines) return offset
        val region = foldRegionByStartLine[ownerStart] ?: return offset
        if (pos.line == region.endLine) return offset
        val endLine = region.endLine.coerceIn(0, (textBuffer.lineCount - 1).coerceAtLeast(0))
        val endLineText = textBuffer.getLine(endLine)
        val trimStartCol = TextScanKernel
            .scanLineWhitespace(endLineText, config.tabSize)
            .leadingWhitespaceEnd
        return textBuffer.positionToOffset(endLine, trimStartCol)
    }

    /**
     * 向左移动时跳过折叠内部隐藏行。
     * 从折叠末行可见文本的起始位置再往左 → 跳回折叠起始行末尾。
     * 从折叠内部行 → 跳回折叠起始行末尾。
     */
    private fun skipFoldBackwardIfHidden(offset: Int): Int {
        if (!isFoldingDataValid()) return offset
        val safeOffset = offset.coerceIn(0, textBuffer.length)
        val pos = textBuffer.offsetToPosition(safeOffset)
        val map = lineMap()
        if (pos.line >= map.docLineCount || !map.hiddenDocLine[pos.line]) return offset
        val ownerStart = map.hiddenOwnerStartLine[pos.line]
        if (ownerStart < 0 || ownerStart !in collapsedFoldStartLines) return offset
        val region = foldRegionByStartLine[ownerStart] ?: return offset
        if (pos.line == region.endLine) {
            val endLineText = textBuffer.getLine(region.endLine)
            val trimStartCol = TextScanKernel
                .scanLineWhitespace(endLineText, config.tabSize)
                .leadingWhitespaceEnd
            if (pos.column >= trimStartCol) return offset
        }
        val startLineText = textBuffer.getLine(ownerStart)
        return textBuffer.positionToOffset(ownerStart, startLineText.length)
    }

    fun moveUp(extendSelection: Boolean = false) {
        val pos = textBuffer.offsetToPosition(cursorOffset.coerceIn(0, textBuffer.length))
        val targetLine = (pos.line - 1).coerceAtLeast(0)
        val targetCol = pos.column.coerceAtMost(textBuffer.getLine(targetLine).length)
        moveCursorToWithOptionalSelection(
            offset = textBuffer.positionToOffset(targetLine, targetCol),
            extendSelection = extendSelection
        )
    }

    fun moveDown(extendSelection: Boolean = false) {
        val pos = textBuffer.offsetToPosition(cursorOffset.coerceIn(0, textBuffer.length))
        val targetLine = (pos.line + 1).coerceAtMost((textBuffer.lineCount - 1).coerceAtLeast(0))
        val targetCol = pos.column.coerceAtMost(textBuffer.getLine(targetLine).length)
        moveCursorToWithOptionalSelection(
            offset = textBuffer.positionToOffset(targetLine, targetCol),
            extendSelection = extendSelection
        )
    }

    private fun moveCursorToWithOptionalSelection(offset: Int, extendSelection: Boolean) {
        val safeOffset = offset.coerceIn(0, textBuffer.length)
        if (!extendSelection) {
            moveCursorTo(safeOffset)
            return
        }
        if (selectionRange == null) {
            startSelection(cursorOffset)
        }
        updateSelectionTo(safeOffset)
    }

    fun gotoLine(line: Int, column: Int = 0) {
        val lineCount = textBuffer.lineCount
        if (lineCount <= 0) {
            moveCursorTo(0)
            scrollToLine(0)
            return
        }
        val safeLine = line.coerceIn(0, lineCount - 1)
        val safeColumn = column.coerceIn(0, textBuffer.getLine(safeLine).length)
        moveCursorTo(textBuffer.positionToOffset(safeLine, safeColumn))
        scrollToLine(cursorLine)
    }

    override fun selectRange(startOffset: Int, endOffset: Int) {
        selectRangeInternal(
            startOffset = startOffset,
            endOffset = endOffset,
            ensureVisible = true
        )
    }

    /**
     * 句柄拖拽期间更新选区：
     * 由 [SelectionHandleDragCoordinator] 负责”到边缘自动滚动”，这里不要再强制 ensureCursorVisible，
     * 否则会与边缘滚动互相打架，造成跳动/卡顿。
     */
    internal fun selectRangeFromHandleDrag(startOffset: Int, endOffset: Int) {
        selectRangeInternal(
            startOffset = startOffset,
            endOffset = endOffset,
            ensureVisible = false
        )
    }

    private fun selectRangeInternal(
        startOffset: Int,
        endOffset: Int,
        ensureVisible: Boolean
    ) {
        val oldCursor = cursorOffset
        val oldSelection = selectionRange
        val oldX = scrollOffsetXPx
        val oldY = scrollOffsetPx
        val safeStart = startOffset.coerceIn(0, textBuffer.length)
        val safeEnd = endOffset.coerceIn(0, textBuffer.length)
        selectionRange = OffsetRange(safeStart, safeEnd)
        cursorOffset = safeEnd
        if (ensureVisible) {
            val pos = textBuffer.offsetToPosition(safeEnd)
            ensureCursorVisible(pos.line, pos.column)
        }
        if (oldCursor != cursorOffset) {
            emitEvent(EditorEvent.CursorMoved(oldCursor, cursorOffset))
        }
        if (oldSelection != selectionRange) {
            emitEvent(EditorEvent.SelectionChanged(selectionRange))
        }
        emitScrollChangedIfNeeded(oldX = oldX, oldY = oldY)
    }

    fun selectedText(): String? {
        val range = selectionRange ?: return null
        if (range.isEmpty) return null
        val start = range.start.coerceIn(0, textBuffer.length)
        val end = range.end.coerceIn(start, textBuffer.length)
        if (start >= end) return null
        return textBuffer.substring(start, end)
    }

    fun selectAll() {
        if (textBuffer.length <= 0) return
        val oldCursor = cursorOffset
        val oldSelection = selectionRange
        val endOffset = textBuffer.length
        selectionRange = OffsetRange(0, endOffset)
        cursorOffset = endOffset
        if (oldCursor != cursorOffset) {
            emitEvent(EditorEvent.CursorMoved(oldCursor, cursorOffset))
        }
        if (oldSelection != selectionRange) {
            emitEvent(EditorEvent.SelectionChanged(selectionRange))
        }
    }

    override fun replaceSelection(replacement: String): Boolean = editorReplaceSelection(this, replacement)

    override fun replaceRange(
        startOffset: Int,
        endOffset: Int,
        replacement: String
    ): Boolean = editorReplaceRange(
        state = this,
        startOffset = startOffset,
        endOffset = endOffset,
        replacement = replacement
    )

    fun clearSelection() {
        val oldSelection = selectionRange
        selectionRange = null
        if (oldSelection != selectionRange) {
            emitEvent(EditorEvent.SelectionChanged(selectionRange))
        }
    }

    fun hasWordAt(line: Int, column: Int): Boolean {
        val clampedLine = line.coerceIn(0, (textBuffer.lineCount - 1).coerceAtLeast(0))
        val lineText = textBuffer.getLine(clampedLine)
        if (lineText.isEmpty()) return false
        return TextScanKernel.findWordBounds(lineText, column) != null
    }

    fun selectWord(line: Int, column: Int): Boolean {
        val clampedLine = line.coerceIn(0, (textBuffer.lineCount - 1).coerceAtLeast(0))
        val lineText = textBuffer.getLine(clampedLine)
        if (lineText.isEmpty()) return false

        val bounds = TextScanKernel.findWordBounds(lineText, column) ?: return false

        selectRange(
            startOffset = textBuffer.positionToOffset(clampedLine, bounds.start),
            endOffset = textBuffer.positionToOffset(clampedLine, bounds.end)
        )
        return true
    }

    fun startSelection(anchorOffset: Int) {
        val oldCursor = cursorOffset
        val oldSelection = selectionRange
        val safe = anchorOffset.coerceIn(0, textBuffer.length)
        selectionRange = OffsetRange(safe, safe)
        cursorOffset = safe
        if (isFocused) {
            cursorBlinkVisible = true
        }
        if (oldCursor != cursorOffset) {
            emitEvent(EditorEvent.CursorMoved(oldCursor, cursorOffset))
        }
        if (oldSelection != selectionRange) {
            emitEvent(EditorEvent.SelectionChanged(selectionRange))
        }
    }

    fun updateSelectionTo(offset: Int) {
        val oldCursor = cursorOffset
        val oldSelection = selectionRange
        val oldX = scrollOffsetXPx
        val oldY = scrollOffsetPx
        val safe = offset.coerceIn(0, textBuffer.length)
        val current = selectionRange
        selectionRange = if (current == null) {
            OffsetRange(cursorOffset, safe)
        } else {
            current.copy(caret = safe)
        }
        cursorOffset = safe
        val pos = textBuffer.offsetToPosition(safe)
        ensureCursorVisible(pos.line, pos.column)
        if (isFocused) {
            cursorBlinkVisible = true
        }
        if (oldCursor != cursorOffset) {
            emitEvent(EditorEvent.CursorMoved(oldCursor, cursorOffset))
        }
        if (oldSelection != selectionRange) {
            emitEvent(EditorEvent.SelectionChanged(selectionRange))
        }
        emitScrollChangedIfNeeded(oldX = oldX, oldY = oldY)
    }

    fun canUndo(): Boolean = textBuffer.canUndo()
    fun canRedo(): Boolean = textBuffer.canRedo()

    override fun undo(): Boolean = editorUndo(this)

    override fun redo(): Boolean = editorRedo(this)

    suspend fun requestCompletion(triggerChar: Char? = null) {
        val provider = onRequestCompletion ?: return
        val replacingSnippetChoice = snippetChoiceCompletionActive
        snippetChoiceCompletionActive = false
        val requestId = ++completionRequestSeq
        activeCompletionRequestId = requestId

        val previousItems = if (replacingSnippetChoice) {
            emptyList()
        } else {
            when (val ui = completionUiState) {
                is CompletionUiState.Visible -> ui.items
                is CompletionUiState.Loading -> ui.previousItems
                CompletionUiState.Hidden -> completionItems
            }
        }
        val previousQuery = if (replacingSnippetChoice) "" else completionQuery
        val previousSelectedLabel = if (replacingSnippetChoice) {
            null
        } else {
            completionItems
                .getOrNull(completionSelectedIndex.coerceIn(0, completionItems.lastIndex.coerceAtLeast(0)))
                ?.label
        }
        val previousSelectedIndex = if (previousItems.isEmpty()) {
            -1
        } else {
            completionSelectedIndex.coerceIn(0, previousItems.lastIndex)
        }

        publishCompletionLoading(
            previousItems = previousItems,
            query = previousQuery,
            selectedIndex = previousSelectedIndex,
            requestId = requestId
        )

        val result = provider(cursorPosition, triggerChar)
        if (requestId != activeCompletionRequestId) return

        when (result) {
            is EditorCompletionFetchResult.TransientFailure -> {
                val fallbackItems = previousItems.ifEmpty { completionItems }
                if (fallbackItems.isNotEmpty()) {
                    val query = completionQueryFromCursor()
                    val filteredFallback = filterCompletionItems(fallbackItems, query)
                    val selectedIndex = if (filteredFallback.isEmpty()) {
                        -1
                    } else {
                        completionSelectedIndex.coerceIn(
                            0,
                            filteredFallback.lastIndex.coerceAtLeast(0)
                        )
                    }
                    publishCompletionResults(
                        items = filteredFallback,
                        query = query,
                        selectedIndex = selectedIndex,
                        requestId = requestId
                    )
                } else {
                    publishCompletionResults(
                        items = emptyList(),
                        query = "",
                        selectedIndex = -1,
                        requestId = requestId
                    )
                }
                return
            }

            is EditorCompletionFetchResult.Success -> {
                val query = completionQueryFromCursor()
                cachedCompletionResults = result.items
                cachedCompletionPrefix = query
                val filtered = filterCompletionItems(result.items, query)
                val selectedIndex = if (filtered.isEmpty()) {
                    -1
                } else {
                    val restoredIndex = previousSelectedLabel?.let { label ->
                        filtered.indexOfFirst { it.label == label }
                    } ?: -1
                    if (restoredIndex >= 0) restoredIndex else 0
                }
                publishCompletionResults(
                    items = filtered,
                    query = query,
                    selectedIndex = selectedIndex,
                    requestId = requestId
                )
            }
        }
    }

    suspend fun requestHover(position: Position = cursorPosition) {
        val provider = onRequestHover ?: return
        val requestId = ++hoverRequestSeq
        activeHoverRequestId = requestId
        publishHoverLoading()
        try {
            val markdown = provider(position)?.trim()
            if (requestId != activeHoverRequestId) return
            if (markdown.isNullOrBlank()) {
                activeHoverRequestId = 0L
                clearHover()
            } else {
                publishHoverVisible(markdown)
            }
        } catch (_: Throwable) {
            if (requestId == activeHoverRequestId) {
                activeHoverRequestId = 0L
                clearHover()
            }
        }
    }

    suspend fun requestSignatureHelp() {
        val provider = onRequestSignatureHelp ?: return
        val requestId = ++signatureHelpRequestSeq
        activeSignatureHelpRequestId = requestId
        val previousResult = when (val ui = signatureHelpUiState) {
            is SignatureHelpUiState.Visible -> ui.result
            is SignatureHelpUiState.Loading -> ui.previousResult
            SignatureHelpUiState.Hidden -> null
        }
        publishSignatureHelpLoading(
            previousResult = previousResult,
            requestId = requestId
        )
        try {
            val result = provider(cursorPosition)
            if (requestId != activeSignatureHelpRequestId) return
            val normalized = normalizeSignatureHelpResult(result)
            if (normalized == null) {
                activeSignatureHelpRequestId = 0L
                clearSignatureHelp()
            } else {
                publishSignatureHelpVisible(
                    result = normalized,
                    requestId = requestId
                )
            }
        } catch (_: Throwable) {
            if (requestId == activeSignatureHelpRequestId) {
                activeSignatureHelpRequestId = 0L
                clearSignatureHelp()
            }
        }
    }

    fun refilterCompletion() {
        val query = completionQueryFromCursor()
        if (cachedCompletionResults.isEmpty()) return
        val filtered = filterCompletionItems(cachedCompletionResults, query)
        publishCompletionResults(
            items = filtered,
            query = query,
            selectedIndex = if (filtered.isEmpty()) -1 else 0,
            requestId = activeCompletionRequestId
        )
    }

    internal fun showInlineCompletionItems(
        items: List<EditorCompletionItem>,
        selectedIndex: Int = 0,
        query: String = "",
        requestId: Long = 0L,
        snippetChoiceActive: Boolean = items.isNotEmpty()
    ) {
        val normalizedSelectedIndex = if (items.isEmpty()) {
            -1
        } else {
            selectedIndex.coerceIn(0, items.lastIndex)
        }
        snippetChoiceCompletionActive = snippetChoiceActive && items.isNotEmpty()
        activeCompletionRequestId = requestId
        cachedCompletionResults = items
        cachedCompletionPrefix = query
        publishCompletionResults(
            items = items,
            query = query,
            selectedIndex = normalizedSelectedIndex,
            requestId = requestId
        )
    }

    fun applyCompletion(item: EditorCompletionItem) {
        editorApplyCompletion(this, item)
    }

    fun moveCompletionSelection(delta: Int): Boolean {
        if (completionItems.isEmpty() || delta == 0) return false
        val size = completionItems.size
        val current = completionSelectedIndex.coerceIn(0, size - 1)
        val next = ((current + delta) % size + size) % size
        publishCompletionSelection(next)
        return true
    }

    fun setCompletionSelectedIndex(index: Int): Boolean {
        if (completionItems.isEmpty()) return false
        if (index !in completionItems.indices) return false
        publishCompletionSelection(index)
        return true
    }

    fun applySelectedCompletion(): Boolean {
        if (completionItems.isEmpty()) return false
        val selectedIndex = completionSelectedIndex.coerceIn(0, completionItems.lastIndex)
        val selectedItem = completionItems.getOrNull(selectedIndex) ?: return false
        applyCompletion(selectedItem)
        return true
    }

    fun dismissCompletion() {
        snippetChoiceCompletionActive = false
        publishCompletionResults(
            items = emptyList(),
            query = "",
            selectedIndex = -1,
            requestId = activeCompletionRequestId
        )
        activeCompletionRequestId = 0L
        cachedCompletionResults = emptyList()
        cachedCompletionPrefix = ""
    }

    fun dismissHover() {
        activeHoverRequestId = 0L
        clearHover()
    }

    fun dismissSignatureHelp() {
        activeSignatureHelpRequestId = 0L
        clearSignatureHelp()
    }

    fun resolveDisplayedSignatureHelpIndex(
        result: SignatureHelpResult?
    ): Int {
        val resolvedResult = result ?: return 0
        val fallback = resolvedResult.activeSignature.coerceIn(0, resolvedResult.signatures.lastIndex)
        return signatureHelpSelectedSignatureIndex?.coerceIn(0, resolvedResult.signatures.lastIndex)
            ?: fallback
    }

    fun selectSignatureHelp(index: Int): Boolean {
        val result = currentSignatureHelpResult() ?: return false
        if (index !in result.signatures.indices) return false
        publishSignatureHelpSelection(index)
        return true
    }

    fun cycleSignatureHelp(delta: Int): Boolean {
        val result = currentSignatureHelpResult() ?: return false
        if (delta == 0 || result.signatures.size <= 1) return false
        val currentIndex = resolveDisplayedSignatureHelpIndex(result)
        val size = result.signatures.size
        val nextIndex = ((currentIndex + delta) % size + size) % size
        publishSignatureHelpSelection(nextIndex)
        return true
    }

    private fun normalizeSignatureHelpResult(
        result: SignatureHelpResult?
    ): SignatureHelpResult? {
        val signatures = result?.signatures.orEmpty().filter { it.isNotBlank() }
        if (signatures.isEmpty()) return null
        return SignatureHelpResult(
            signatures = signatures,
            activeSignature = result?.activeSignature?.coerceIn(0, signatures.lastIndex) ?: 0,
            activeParameter = result?.activeParameter?.coerceAtLeast(0) ?: 0
        )
    }

    private fun normalizeSignatureHelpSelection(
        result: SignatureHelpResult?,
        selectedIndex: Int?
    ): Int? {
        val normalizedResult = result ?: return null
        if (normalizedResult.signatures.isEmpty()) return null
        return selectedIndex?.coerceIn(0, normalizedResult.signatures.lastIndex)
    }

    private fun currentSignatureHelpResult(): SignatureHelpResult? = when (val ui = signatureHelpUiState) {
        is SignatureHelpUiState.Visible -> ui.result
        is SignatureHelpUiState.Loading -> ui.previousResult
        SignatureHelpUiState.Hidden -> null
    }

    internal fun startSnippetSession(session: SnippetSession) {
        activeSnippetSession = session
        applySnippetPlaceholderFocus(session)
    }

    /**
     * Tab 正向跳转：前进到下一个 tabstop。
     * @return 是否消费了 Tab 键事件
     */
    fun advanceSnippet(): Boolean {
        val session = activeSnippetSession ?: return false
        val next = session.advance()
        if (next == null) {
            cancelSnippet()
            return true
        }
        activeSnippetSession = next
        applySnippetPlaceholderFocus(next)
        return true
    }

    /**
     * Shift+Tab 反向跳转：退回上一个 tabstop。
     * @return 是否消费了 Shift+Tab 键事件
     */
    fun retreatSnippet(): Boolean {
        val session = activeSnippetSession ?: return false
        val prev = session.retreat() ?: return false
        activeSnippetSession = prev
        applySnippetPlaceholderFocus(prev)
        return true
    }

    fun cancelSnippet() {
        activeSnippetSession = null
        if (snippetChoiceCompletionActive) {
            dismissCompletion()
        }
    }

    /**
     * 在 snippet 会话内发生编辑时，同步更新会话中所有占位符的偏移量。
     *
     * @param editOffset 编辑发生的绝对文本偏移
     * @param delta      变化量（插入为正，删除为负）
     */
    internal fun adjustSnippetOffsets(editOffset: Int, delta: Int) {
        val session = activeSnippetSession ?: return
        activeSnippetSession = session.adjustOffsets(editOffset, delta)
    }

    internal fun updateSnippetSession(session: SnippetSession?) {
        activeSnippetSession = session
    }

    /**
     * 将光标/选区定位到 [session] 当前步骤的第一个占位符位置。
     * 若当前分组无占位符（不应发生），结束会话。
     */
    private fun applySnippetPlaceholderFocus(session: SnippetSession) {
        val placeholder = session.currentPlaceholder() ?: run {
            activeSnippetSession = null
            return
        }
        val start = session.absoluteOffsetOf(placeholder)
            .coerceIn(0, textBuffer.length)
        val end = (start + placeholder.length).coerceIn(start, textBuffer.length)
        if (placeholder.length > 0) {
            moveCursorTo(end, clearSelection = false)
            selectionRange = OffsetRange(start, end)
        } else {
            moveCursorTo(start)
            selectionRange = null
        }

        val choices = placeholder.choices
        if (choices.isNullOrEmpty()) {
            if (snippetChoiceCompletionActive) {
                dismissCompletion()
            }
            return
        }

        val currentText = textBuffer.substring(start, end)
        val startPosition = textBuffer.offsetToPosition(start)
        val endPosition = textBuffer.offsetToPosition(end)
        val items = choices.map { choice ->
            EditorCompletionItem(
                label = choice,
                insertText = choice,
                kind = EditorCompletionKind.VALUE,
                textEdit = EditorCompletionTextEdit(
                    startLine = startPosition.line,
                    startColumn = startPosition.column,
                    endLine = endPosition.line,
                    endColumn = endPosition.column,
                    newText = choice
                )
            )
        }
        val selectedIndex = choices.indexOf(currentText).takeIf { it >= 0 } ?: 0
        showInlineCompletionItems(
            items = items,
            selectedIndex = selectedIndex,
            query = currentText
        )
    }

    fun replaceAll(
        findText: String,
        replaceText: String,
        caseSensitive: Boolean = false,
        useRegex: Boolean = false
    ): Int = editorReplaceAll(
        state = this,
        findText = findText,
        replaceText = replaceText,
        caseSensitive = caseSensitive,
        useRegex = useRegex
    )

    fun toggleLineComment(commentToken: String): Boolean = editorToggleLineComment(this, commentToken)

    private var cachedMaxScrollPxVersion = -1L
    private var cachedMaxScrollPxLineHeight = 0f
    private var cachedMaxScrollPxViewportH = 0f
    private var cachedMaxScrollPxValue = 0f

    private fun maxScrollPx(): Float {
        val version = textBuffer.version
        val lh = lineHeightPx
        val vh = viewportHeightPx
        if (version == cachedMaxScrollPxVersion && lh == cachedMaxScrollPxLineHeight && vh == cachedMaxScrollPxViewportH) {
            return cachedMaxScrollPxValue
        }
        val totalHeight = visualLineCount() * lh
        val bottomPadding = vh * 0.5f
        val result = (totalHeight - vh + bottomPadding).coerceAtLeast(0f)
        cachedMaxScrollPxVersion = version
        cachedMaxScrollPxLineHeight = lh
        cachedMaxScrollPxViewportH = vh
        cachedMaxScrollPxValue = result
        return result
    }

    private fun maxScrollXPx(): Float {
        // 横向右侧预留“可滚动空白”，避免长行滚动到末尾时文本直接贴住屏幕右边缘，
        // 影响光标/选区/句柄的可操作性。
        //
        // 对齐 Sora 的思路：CodeEditor#getScrollMaxX() 里会减去 viewWidth/2，
        // 等价于给右侧增加 half-viewport 的额外滚动空间。
        val endPadding = viewportWidthPx * 0.5f

        if (config.wordWrap) {
            // wordWrap 通常禁用横向滚动，但双指缩放过程中需要允许短暂的 X 锚定，
            // 否则当行号/ gutter 宽度随字体变化时会出现明显“漂移”。
            // 缩放结束（解除冻结）后再回到 0，行为对齐 Sora。
            return if (isWordWrapLayoutFrozen()) {
                endPadding.coerceAtLeast(0f)
            } else {
                0f
            }
        }

        val maxLineWidth = ensureMaxLineWidthPx()
        return (maxLineWidth - viewportWidthPx + endPadding).coerceAtLeast(0f)
    }

    private fun ensureMaxLineWidthPx(): Float {
        val version = textBuffer.version
        if (cachedMaxLineCharsVersion != version) {
            var resolvedForCurrentVersion = false
            cachedMaxLineChars = if (
                widthLineSnapshotVersion == version &&
                widthLineLengths.size == textBuffer.lineCount
            ) {
                resolvedForCurrentVersion = true
                maxOf(widthLineMaxChars, visibleAndCursorMaxLineChars())
            } else if (textBuffer.lineCount <= HORIZONTAL_FULL_SCAN_LINE_THRESHOLD) {
                resolvedForCurrentVersion = true
                buildFullWidthSnapshot(version)
            } else {
                if (widthScanVersion != version) {
                    widthScanVersion = version
                    widthScanNextLine = 0
                    widthScanLineLengths = IntArray(textBuffer.lineCount)
                    widthScanMaxChars = maxOf(
                        visibleAndCursorMaxLineChars(),
                        sampleMaxLineChars(HORIZONTAL_WIDTH_BOOTSTRAP_SAMPLES)
                    )
                } else {
                    widthScanMaxChars = maxOf(widthScanMaxChars, visibleAndCursorMaxLineChars())
                }

                val scanStartNs = System.nanoTime()
                var scannedCount = 0
                val lineCount = textBuffer.lineCount
                while (
                    widthScanNextLine < lineCount &&
                    scannedCount < HORIZONTAL_WIDTH_SCAN_MAX_BATCH_LINES
                ) {
                    val lineLength = textBuffer.getLine(widthScanNextLine).length
                    widthScanLineLengths[widthScanNextLine] = lineLength
                    if (lineLength > widthScanMaxChars) {
                        widthScanMaxChars = lineLength
                    }
                    widthScanNextLine++
                    scannedCount++
                    if (
                        scannedCount % HORIZONTAL_WIDTH_SCAN_CHECK_INTERVAL == 0 &&
                        (System.nanoTime() - scanStartNs) >= HORIZONTAL_WIDTH_SCAN_TIME_BUDGET_NS
                    ) {
                        break
                    }
                }

                if (widthScanNextLine >= lineCount) {
                    widthLineLengths = widthScanLineLengths
                    widthLineMaxChars = widthScanMaxChars
                    widthLineSnapshotVersion = version
                    resolvedForCurrentVersion = true
                } else {
                    widthLineSnapshotVersion = -1L
                }
                widthScanMaxChars
            }
            cachedMaxLineCharsVersion = if (
                resolvedForCurrentVersion ||
                textBuffer.lineCount <= HORIZONTAL_FULL_SCAN_LINE_THRESHOLD ||
                widthScanNextLine >= textBuffer.lineCount
            ) {
                version
            } else {
                -1L
            }
        }
        return cachedMaxLineChars * charWidthPx
    }

    private fun visibleAndCursorMaxLineChars(): Int {
        val lineCount = textBuffer.lineCount
        if (lineCount <= 0) return 0
        val maxLineIndex = lineCount - 1
        val snapshot = widthLineLengths
        val snapshotValid = widthLineSnapshotVersion == textBuffer.version && snapshot.size == lineCount
        val map = visualLineMap()
        val visibleMax = if (visibleLines.isEmpty()) {
            0
        } else {
            var maxChars = 0
            for (visualLine in visibleLines) {
                val safeVisual = visualLine.coerceIn(0, (map.visualLineCount - 1).coerceAtLeast(0))
                val visibleIndex = resolveVisibleIndexForVisualLine(map, safeVisual)
                val docLine = map.visibleDocLines.getOrElse(visibleIndex) { 0 }
                if (docLine !in 0..maxLineIndex) continue
                val length = if (snapshotValid) {
                    snapshot[docLine]
                } else {
                    lineVisualColumnsForWidth(textBuffer.getLine(docLine))
                }
                if (length > maxChars) {
                    maxChars = length
                }
            }
            maxChars
        }
        val curLine = cursorLine.coerceIn(0, maxLineIndex)
        val cursorLineLength = if (snapshotValid) {
            snapshot[curLine]
        } else {
            lineVisualColumnsForWidth(textBuffer.getLine(curLine))
        }
        return maxOf(visibleMax, cursorLineLength + HORIZONTAL_WIDTH_GUARD_CHARS)
    }

    private fun sampleMaxLineChars(sampleCount: Int): Int {
        val lineCount = textBuffer.lineCount
        if (lineCount <= 0) return 0
        if (lineCount <= sampleCount) {
            return (0 until lineCount).maxOfOrNull { lineVisualColumnsForWidth(textBuffer.getLine(it)) } ?: 0
        }
        val maxLineIndex = lineCount - 1
        var maxChars = 0
        val divisor = (sampleCount - 1).coerceAtLeast(1)
        for (index in 0 until sampleCount) {
            val line = (index.toLong() * maxLineIndex / divisor).toInt()
            val length = lineVisualColumnsForWidth(textBuffer.getLine(line))
            if (length > maxChars) {
                maxChars = length
            }
        }
        return maxChars
    }

    private fun buildFullWidthSnapshot(version: Long): Int {
        val lineCount = textBuffer.lineCount
        if (lineCount <= 0) {
            widthLineLengths = IntArray(0)
            widthLineMaxChars = 0
            widthLineSnapshotVersion = version
            widthScanVersion = -1L
            widthScanNextLine = 0
            widthScanMaxChars = 0
            widthScanLineLengths = IntArray(0)
            return 0
        }
        val lengths = IntArray(lineCount)
        var maxChars = 0
        for (line in 0 until lineCount) {
            val length = lineVisualColumnsForWidth(textBuffer.getLine(line))
            lengths[line] = length
            if (length > maxChars) {
                maxChars = length
            }
        }
        widthLineLengths = lengths
        widthLineMaxChars = maxChars
        widthLineSnapshotVersion = version
        widthScanVersion = -1L
        widthScanNextLine = 0
        widthScanMaxChars = 0
        widthScanLineLengths = IntArray(0)
        return maxChars
    }

    private fun invalidateWidthSnapshot() {
        widthLineSnapshotVersion = -1L
        widthLineMaxChars = 0
        widthLineLengths = IntArray(0)
        widthScanVersion = -1L
        widthScanNextLine = 0
        widthScanMaxChars = 0
        widthScanLineLengths = IntArray(0)
        cachedMaxLineCharsVersion = -1L
    }

    private fun applyWidthSnapshotChange(change: TextChange, currentVersion: Long) {
        if (widthLineSnapshotVersion < 0L) {
            cachedMaxLineCharsVersion = -1L
            return
        }
        if (widthLineSnapshotVersion + 1L != currentVersion) {
            invalidateWidthSnapshot()
            return
        }

        val oldLengths = widthLineLengths
        val oldLineCount = oldLengths.size
        val newLineCount = textBuffer.lineCount
        if (oldLineCount <= 0 || newLineCount <= 0) {
            buildFullWidthSnapshot(currentVersion)
            cachedMaxLineChars = maxOf(widthLineMaxChars, visibleAndCursorMaxLineChars())
            cachedMaxLineCharsVersion = currentVersion
            return
        }

        val startLine = change.startLine.coerceIn(0, oldLineCount - 1)
        val oldChangedEnd = change.endLine.coerceIn(startLine, oldLineCount - 1)
        val lineDelta = change.lineDelta
        val expectedNewLineCount = oldLineCount + lineDelta
        if (expectedNewLineCount != newLineCount) {
            invalidateWidthSnapshot()
            return
        }

        val newChangedEnd = maxOf(startLine, oldChangedEnd + lineDelta)
            .coerceIn(startLine, (newLineCount - 1).coerceAtLeast(startLine))
        val insertedCount = newChangedEnd - startLine + 1
        val insertedLengths = IntArray(insertedCount)
        var insertedMax = 0
        for (index in 0 until insertedCount) {
            val length = lineVisualColumnsForWidth(textBuffer.getLine(startLine + index))
            insertedLengths[index] = length
            if (length > insertedMax) {
                insertedMax = length
            }
        }

        val updatedLengths = IntArray(newLineCount)
        if (startLine > 0) {
            System.arraycopy(oldLengths, 0, updatedLengths, 0, startLine)
        }
        if (insertedCount > 0) {
            System.arraycopy(insertedLengths, 0, updatedLengths, startLine, insertedCount)
        }
        val oldSuffixStart = oldChangedEnd + 1
        val oldSuffixCount = oldLineCount - oldSuffixStart
        if (oldSuffixCount > 0) {
            System.arraycopy(
                oldLengths,
                oldSuffixStart,
                updatedLengths,
                startLine + insertedCount,
                oldSuffixCount
            )
        }

        var removedHadOldMax = false
        val oldMax = widthLineMaxChars
        var line = startLine
        while (line <= oldChangedEnd) {
            if (oldLengths[line] == oldMax) {
                removedHadOldMax = true
                break
            }
            line++
        }

        var updatedMax = maxOf(oldMax, insertedMax)
        if (removedHadOldMax && insertedMax < oldMax) {
            updatedMax = 0
            for (length in updatedLengths) {
                if (length > updatedMax) {
                    updatedMax = length
                }
            }
        }

        widthLineLengths = updatedLengths
        widthLineMaxChars = updatedMax
        widthLineSnapshotVersion = currentVersion
        widthScanVersion = -1L
        widthScanNextLine = 0
        widthScanMaxChars = 0
        widthScanLineLengths = IntArray(0)
        cachedMaxLineChars = maxOf(widthLineMaxChars, visibleAndCursorMaxLineChars())
        cachedMaxLineCharsVersion = currentVersion
    }

    private fun clampScroll() {
        scrollOffsetPx = scrollOffsetPx.coerceIn(0f, maxScrollPx())
        scrollOffsetXPx = scrollOffsetXPx.coerceIn(0f, maxScrollXPx())
    }

    private fun onConfigChanged(old: EditorConfig, new: EditorConfig) {
        // 同步“独立暴露”的开关（避免 config 更新后 state 字段滞后）。
        pinLineNumber = new.pinLineNumber
        useRelativeLineNumbers = new.useRelativeLineNumbers

        if (old.tabSize != new.tabSize) {
            // TabSize 影响：横向最大宽度/软换行分段/命中测试，直接失效相关快照。
            invalidateWidthSnapshot()
            visualLineMapCache = null
            wordWrapLayoutCache.invalidateAll()
        }

        if (old.wordWrap != new.wordWrap) {
            visualLineMapCache = null
            if (new.wordWrap) {
                // wordWrap 开启后横向滚动被禁用，强制回到 0 避免“坐标系漂移”。
                scrollOffsetXPx = 0f
            } else {
                // 关闭 wordWrap：清理冻结状态与缩放锚点，避免后续布局计算被旧状态污染。
                frozenWordWrapColumns = null
                pendingScaleAnchor = null
            }
        }

        if (old.codeFolding != new.codeFolding) {
            visualLineMapCache = null
        }

        clampScroll()
    }

    private fun ensureCursorVisible(line: Int, column: Int) {
        val visualLine = visualLineForPosition(line, column)
        val top = visualLine * lineHeightPx
        val bottom = top + lineHeightPx
        // 更接近常见编辑器体验：只有当光标即将出屏或贴边时才调整滚动，
        // 避免“选词/移动光标后内容整体大幅偏移”的突兀感。
        val paddingY = (lineHeightPx * 1.4f)
            .coerceAtMost(viewportHeightPx * 0.25f)
            .coerceAtLeast(0f)
        scrollOffsetPx = when {
            top < scrollOffsetPx + paddingY -> {
                top - paddingY
            }

            bottom > scrollOffsetPx + viewportHeightPx - paddingY -> {
                bottom - (viewportHeightPx - paddingY)
            }

            else -> scrollOffsetPx
        }.coerceIn(0f, maxScrollPx())

        // 开启 wordWrap 时不再进行横向滚动对齐（横向滚动被禁用）。
        if (config.wordWrap) {
            scrollOffsetXPx = 0f
            return
        }

        val paddingX = maxOf(charWidthPx * 4.0f, lineHeightPx * 0.7f)
            .coerceAtMost(viewportWidthPx * 0.25f)
            .coerceAtLeast(0f)
        val safeLine = line.coerceIn(0, (textBuffer.lineCount - 1).coerceAtLeast(0))
        val safeColumn = column.coerceAtLeast(0)
        val resolver = columnXInTextPxResolver
        val cursorLeft = resolver?.invoke(safeLine, safeColumn) ?: (safeColumn * charWidthPx)
        val nextColumn = safeColumn + 1
        val cursorRight = resolver?.invoke(safeLine, nextColumn) ?: (cursorLeft + charWidthPx)
        // 行号栏不固定时（pinLineNumber=false），允许文本在横向滚动后“吃掉”左侧行号栏区域，
        // 也就是：光标可见区域的左边界从 `textStartX(=contentStartXPx)` 变为 `0`。
        // 将其转换到“行内坐标”(cursorLeft/cursorRight)后，相当于左边界向左扩展了 contentStartXPx。
        val leftInsetInTextPx = if (pinLineNumber) 0f else contentStartXPx
        val leftBoundary = (scrollOffsetXPx - leftInsetInTextPx) + paddingX
        val rightBoundary = (scrollOffsetXPx + viewportWidthPx) - paddingX
        scrollOffsetXPx = when {
            cursorLeft < leftBoundary -> {
                cursorLeft + leftInsetInTextPx - paddingX
            }

            cursorRight > rightBoundary -> {
                cursorRight - (viewportWidthPx - paddingX)
            }

            else -> scrollOffsetXPx
        }.coerceIn(0f, maxScrollXPx())
    }

    /**
     * 更新折叠区间（通常由 Tree-sitter/LSP 等外部数据源计算后传入）。
     *
     * @param documentVersion 该折叠结果对应的文档版本（必须与当前 [textBuffer.version] 一致，否则忽略）。
     */
    fun setFoldRegions(
        regions: List<FoldRegion>,
        documentVersion: Long
    ) {
        if (documentVersion != textBuffer.version) return

        val lineCount = textBuffer.lineCount
        if (!config.codeFolding || lineCount <= 0) {
            clearFoldRegionsInternal()
            return
        }

        val normalized = regions.asSequence()
            .map { region ->
                val start = region.startLine.coerceIn(0, (lineCount - 1).coerceAtLeast(0))
                val end = region.endLine.coerceIn(start, (lineCount - 1).coerceAtLeast(0))
                FoldRegion(startLine = start, endLine = end)
            }
            .filter { it.isFoldable }
            .filter { region ->
                val broken = brokenFoldRecords.find { it.startLine == region.startLine }
                broken == null || region.endLine == broken.originalEndLine
            }
            .distinct()
            .sortedWith(compareBy<FoldRegion> { it.startLine }.thenByDescending { it.endLine })
            .toList()

        brokenFoldRecords.removeAll { broken ->
            normalized.none { it.startLine == broken.startLine }
        }

        foldRegions = normalized
        foldRegionsDocumentVersion = documentVersion
        foldRegionByStartLine = normalized.associateBy { it.startLine }
        collapsedFoldStartLines = collapsedFoldStartLines
            .filter { it in foldRegionByStartLine }
            .toSet()

        applyFoldableDecorations(startLines = foldRegionByStartLine.keys)
        onFoldDataChanged()
        clampScroll()

        Timber.tag("EditorState").d(
            "setFoldRegions: input=%d, normalized=%d, decorations=%d, docVersion=%d, bufVersion=%d",
            regions.size,
            normalized.size,
            foldRegionByStartLine.size,
            documentVersion,
            textBuffer.version
        )
    }

    fun clearFoldRegions() {
        clearFoldRegionsInternal()
    }

    fun toggleFoldAtLine(line: Int) {
        if (!config.codeFolding) return
        if (foldRegionsDocumentVersion != textBuffer.version) return
        val lineCount = textBuffer.lineCount
        if (lineCount <= 0) return
        val startLine = line.coerceIn(0, lineCount - 1)
        val region = foldRegionByStartLine[startLine] ?: return
        if (!region.isFoldable) return

        val wasCollapsed = startLine in collapsedFoldStartLines
        collapsedFoldStartLines = if (wasCollapsed) {
            collapsedFoldStartLines - startLine
        } else {
            collapsedFoldStartLines + startLine
        }
        onFoldDataChanged()

        // 折叠后：若光标落在隐藏区间内，将其回退到折叠起始行，避免”光标消失”。
        if (!wasCollapsed) {
            val curPos = textBuffer.offsetToPosition(cursorOffset.coerceIn(0, textBuffer.length))
            val curLine = curPos.line
            if (curLine in (region.startLine + 1)..region.endLine) {
                val lineText = textBuffer.getLine(region.startLine)
                val safeColumn = curPos.column.coerceIn(0, lineText.length)
                moveCursorTo(textBuffer.positionToOffset(region.startLine, safeColumn))
            }
        }
        clampScroll()
    }

    internal fun isFoldCollapsedAtLine(line: Int): Boolean = line in collapsedFoldStartLines

    internal fun isFoldingDataValid(): Boolean = config.codeFolding && foldRegionsDocumentVersion == textBuffer.version

    internal fun isCollapsedFoldStart(line: Int): Boolean = isFoldingDataValid() && line in collapsedFoldStartLines

    internal fun collapsedFoldEndLine(startLine: Int): Int {
        if (!isCollapsedFoldStart(startLine)) return -1
        return foldRegionByStartLine[startLine]?.endLine ?: -1
    }

    /**
     * 折叠末行是否"虚拟可见"。
     *
     * IntelliJ 风格：折叠末行（如包含 `}` 的行）虽然在 lineMap 中被标记为 hidden，
     * 但其 trim 后的文本作为折叠装饰的一部分显示在起始行末尾，光标可以定位到该文本上。
     */
    internal fun isFoldEndLineVirtuallyVisible(docLine: Int): Boolean {
        if (!isFoldingDataValid()) return false
        val map = lineMap()
        if (docLine < 0 || docLine >= map.docLineCount) return false
        if (!map.hiddenDocLine[docLine]) return false
        val ownerStart = map.hiddenOwnerStartLine[docLine]
        if (ownerStart < 0 || ownerStart !in collapsedFoldStartLines) return false
        val region = foldRegionByStartLine[ownerStart] ?: return false
        return docLine == region.endLine
    }

    internal fun foldOwnerForEndLine(docLine: Int): Int {
        if (!isFoldEndLineVirtuallyVisible(docLine)) return -1
        return lineMap().hiddenOwnerStartLine[docLine]
    }

    internal fun markFoldAsBroken(startLine: Int) {
        val region = foldRegionByStartLine[startLine] ?: return
        brokenFoldRecords.add(BrokenFoldRecord(startLine, region.endLine))
    }

    internal fun foldOwnerForHiddenLine(docLine: Int): Int {
        if (!isFoldingDataValid()) return -1
        val map = lineMap()
        if (docLine < 0 || docLine >= map.docLineCount) return -1
        if (!map.hiddenDocLine[docLine]) return -1
        return map.hiddenOwnerStartLine[docLine]
    }

    internal fun hasHiddenDiagnosticsInFold(startLine: Int): Boolean {
        if (!isFoldingDataValid()) return false
        if (startLine !in collapsedFoldStartLines) return false
        val region = foldRegionByStartLine[startLine] ?: return false
        val hiddenStart = (region.startLine + 1).coerceAtLeast(0)
        val hiddenEnd = region.endLine
        if (hiddenEnd <= hiddenStart) return false
        val sortedDiagLines = diagnosticLinesSorted
        if (sortedDiagLines.isEmpty()) return false
        var lo = 0
        var hi = sortedDiagLines.size - 1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            when {
                sortedDiagLines[mid] < hiddenStart -> lo = mid + 1
                sortedDiagLines[mid] > hiddenEnd -> hi = mid - 1
                else -> return true
            }
        }
        return false
    }

    private fun clearFoldRegionsInternal() {
        foldRegions = emptyList()
        foldRegionsDocumentVersion = -1L
        foldRegionByStartLine = emptyMap()
        collapsedFoldStartLines = emptySet()
        applyFoldableDecorations(startLines = emptySet())
        onFoldDataChanged()
        clampScroll()
    }

    private fun onFoldDataChanged() {
        foldDataVersion += 1
        lineMapCache = null
    }

    private fun applyFoldableDecorations(startLines: Set<Int>) {
        // 先清理旧 foldable
        val oldFoldableLines = gutterDecorations
            .filterValues { it.foldable }
            .keys
            .toList()
        oldFoldableLines.forEach { line ->
            if (line in startLines) return@forEach
            val existing = gutterDecorations[line] ?: return@forEach
            val updated = existing.copy(foldable = false)
            if (updated.breakpoint || updated.bookmark || updated.hasDiagnostic) {
                gutterDecorations[line] = updated
            } else {
                gutterDecorations.remove(line)
            }
        }

        // 再设置新 foldable
        startLines.forEach { line ->
            val existing = gutterDecorations[line] ?: GutterDecoration()
            gutterDecorations[line] = existing.copy(foldable = true)
        }
    }

    private fun revealLineIfFolded(docLine: Int) {
        if (!config.codeFolding) return
        if (foldRegionsDocumentVersion != textBuffer.version) return
        val lineCount = textBuffer.lineCount
        if (lineCount <= 0) return
        val safeLine = docLine.coerceIn(0, lineCount - 1)

        var guard = 0
        while (guard < 64) {
            val map = lineMap()
            if (safeLine >= map.docLineCount) return
            if (!map.hiddenDocLine[safeLine]) return
            val ownerStart = map.hiddenOwnerStartLine[safeLine]
            if (ownerStart < 0) return
            if (ownerStart !in collapsedFoldStartLines) return
            val region = foldRegionByStartLine[ownerStart]
            if (region != null && safeLine == region.endLine) return
            collapsedFoldStartLines = collapsedFoldStartLines - ownerStart
            onFoldDataChanged()
            guard++
        }
    }

    private fun resolveVisualLineForDocLine(docLine: Int): Int = visualLineForDocLine(docLine)

    internal fun visualLineCount(): Int = visualLineMap().visualLineCount.coerceAtLeast(0)

    private fun resolveVisibleIndexForDocLine(docLine: Int): Int {
        val map = lineMap()
        if (map.docLineCount <= 0) return -1
        val safeLine = docLine.coerceIn(0, map.docLineCount - 1)
        val direct = map.docToVisualLine[safeLine]
        if (direct >= 0) return direct
        val ownerStart = map.hiddenOwnerStartLine.getOrNull(safeLine) ?: -1
        if (ownerStart >= 0 && ownerStart < map.docLineCount) {
            val ownerVisual = map.docToVisualLine[ownerStart]
            if (ownerVisual >= 0) return ownerVisual
        }
        return -1
    }

    private fun resolveVisibleIndexForVisualLine(map: VisualLineMap, visualLine: Int): Int {
        val starts = map.firstVisualLineByVisibleIndex
        if (starts.isEmpty()) return 0
        val target = visualLine.coerceAtLeast(0)
        // 查找最后一个 start <= target 的索引
        var low = 0
        var high = starts.size - 1
        var result = 0
        while (low <= high) {
            val mid = (low + high) ushr 1
            val value = starts[mid]
            if (value <= target) {
                result = mid
                low = mid + 1
            } else {
                high = mid - 1
            }
        }
        return result.coerceIn(0, starts.lastIndex)
    }

    private fun visualLineMap(): VisualLineMap {
        val currentVersion = textBuffer.version
        val foldingEnabled = config.codeFolding && foldRegionsDocumentVersion == currentVersion
        val wordWrapEnabled = config.wordWrap
        val wrapColumns = if (wordWrapEnabled) {
            frozenWordWrapColumns ?: run {
                val safeCharWidth = charWidthPx.coerceAtLeast(1f)
                val safeViewportWidth = viewportWidthPx.coerceAtLeast(1f)
                (safeViewportWidth / safeCharWidth).toInt().coerceAtLeast(1)
            }
        } else {
            0
        }
        val tabSize = config.tabSize
        val docLineCount = textBuffer.lineCount.coerceAtLeast(0)

        val epoch = visualLineMapEpoch(
            currentVersion,
            foldDataVersion,
            foldingEnabled,
            wordWrapEnabled,
            wrapColumns,
            tabSize,
            docLineCount
        )
        val cached = visualLineMapCache
        if (cached != null && epoch == visualLineMapCacheEpoch) {
            return cached
        }

        val base = lineMap()
        val visibleDocLines = base.visualToDocLine
        val visibleCount = visibleDocLines.size
        if (visibleCount <= 0 || docLineCount <= 0) {
            val built = VisualLineMap(
                docLineCount = docLineCount,
                visibleDocLines = visibleDocLines,
                firstVisualLineByVisibleIndex = IntArray(0),
                visualLineCountByVisibleIndex = IntArray(0),
                visualLineCount = 0,
                wordWrapEnabled = false,
                wrapColumns = wrapColumns
            )
            visualLineMapCache = built
            visualLineMapCacheEpoch = epoch
            return built
        }

        val firstVisual = IntArray(visibleCount)
        val visualCounts = IntArray(visibleCount)
        var totalVisualLines = 0

        if (!wordWrapEnabled || wrapColumns <= 0) {
            for (i in 0 until visibleCount) {
                firstVisual[i] = i
                visualCounts[i] = 1
            }
            totalVisualLines = visibleCount
        } else {
            val safeWrapColumns = wrapColumns.coerceAtLeast(1)
            val counts = ensureDocSegmentCounts(
                wrapColumns = safeWrapColumns,
                tabSize = tabSize,
                docLineCount = docLineCount,
                textVersion = currentVersion
            )
            for (i in 0 until visibleCount) {
                firstVisual[i] = totalVisualLines
                val docLine = visibleDocLines[i].coerceIn(0, docLineCount - 1)
                val segments = if (docLine < counts.size) counts[docLine] else 1
                visualCounts[i] = segments
                totalVisualLines += segments
            }
        }

        val built = VisualLineMap(
            docLineCount = docLineCount,
            visibleDocLines = visibleDocLines,
            firstVisualLineByVisibleIndex = firstVisual,
            visualLineCountByVisibleIndex = visualCounts,
            visualLineCount = totalVisualLines.coerceAtLeast(0),
            wordWrapEnabled = wordWrapEnabled && wrapColumns > 0,
            wrapColumns = wrapColumns
        )
        visualLineMapCache = built
        visualLineMapCacheEpoch = epoch
        return built
    }

    /**
     * 返回按文档行索引的 wrap segmentCount。
     * 命中缓存直接返回；否则按 wrap/tabSize/docLineCount/textVersion 任一变化做全量重建。
     * 文本增量变化走 [applyTextChangeToDocSegmentCounts]（在 [onTextBufferChanged] 内调用）。
     */
    private fun ensureDocSegmentCounts(
        wrapColumns: Int,
        tabSize: Int,
        docLineCount: Int,
        textVersion: Long
    ): IntArray {
        val cached = docSegmentCounts
        if (cached != null &&
            docSegmentCountsWrapColumns == wrapColumns &&
            docSegmentCountsTabSize == tabSize &&
            docSegmentCountsVersion == textVersion &&
            cached.size == docLineCount
        ) {
            return cached
        }
        val arr = IntArray(docLineCount)
        for (i in 0 until docLineCount) {
            arr[i] = computeSegmentCountForLine(i, wrapColumns, tabSize, textVersion)
        }
        docSegmentCounts = arr
        docSegmentCountsWrapColumns = wrapColumns
        docSegmentCountsTabSize = tabSize
        docSegmentCountsVersion = textVersion
        return arr
    }

    private fun computeSegmentCountForLine(
        docLine: Int,
        wrapColumns: Int,
        tabSize: Int,
        textVersion: Long
    ): Int {
        val lineText = textBuffer.getLine(docLine)
        return wordWrapLayoutCache.getWrapLayout(
            line = docLine,
            lineText = lineText,
            textVersion = textVersion,
            wrapColumns = wrapColumns,
            tabSize = tabSize
        ).segmentCount
    }

    /**
     * 将 [TextChange] 增量应用到 [docSegmentCounts]：
     * - head [0, startLine) 原样拷贝；
     * - 编辑窗 [startLine, newChangedEndLine] 重算（调用 wordWrapLayoutCache，此时它已 applyTextChange 过）；
     * - tail (oldEnd, oldDocCount) 按 lineDelta 平移到 (newEnd, newDocCount)。
     *
     * 调用方必须在 [wordWrapLayoutCache] 的 applyTextChange 之后调，保证编辑窗内各行的 wrap layout
     * 可以正确重建（旧 cache entry 已被移除）。
     */
    private fun applyTextChangeToDocSegmentCounts(change: TextChange, newVersion: Long) {
        val cached = docSegmentCounts ?: return
        val wrapColumns = docSegmentCountsWrapColumns
        val tabSize = docSegmentCountsTabSize
        if (wrapColumns <= 0) {
            // 签名已经不再有效（wrapColumns 还没被初始化成合法值）。
            docSegmentCounts = null
            return
        }
        val oldDocCount = cached.size
        val startLine = change.startLine.coerceIn(0, oldDocCount)
        val oldEnd = change.endLine.coerceIn(startLine, oldDocCount - 1)
        val delta = change.lineDelta
        val newDocCount = oldDocCount + delta
        if (newDocCount <= 0) {
            docSegmentCounts = null
            return
        }
        val newEnd = (oldEnd + delta).coerceIn(startLine, newDocCount - 1)
        val arr = IntArray(newDocCount)
        // head: [0, startLine)
        val headLen = startLine.coerceAtMost(oldDocCount)
        if (headLen > 0) System.arraycopy(cached, 0, arr, 0, headLen)
        // tail: old [oldEnd+1, oldDocCount) → new [newEnd+1, newDocCount)
        val tailSrc = (oldEnd + 1).coerceAtMost(oldDocCount)
        val tailDst = (newEnd + 1).coerceIn(0, newDocCount)
        val tailLen = minOf(oldDocCount - tailSrc, newDocCount - tailDst).coerceAtLeast(0)
        if (tailLen > 0) System.arraycopy(cached, tailSrc, arr, tailDst, tailLen)
        // edited window: [startLine, newEnd] 重算
        var i = startLine
        val limit = newEnd.coerceAtMost(newDocCount - 1)
        while (i <= limit) {
            arr[i] = computeSegmentCountForLine(i, wrapColumns, tabSize, newVersion)
            i++
        }
        docSegmentCounts = arr
        docSegmentCountsVersion = newVersion
    }

    private fun lineMap(): LineMap {
        val foldingEnabled = config.codeFolding && foldRegionsDocumentVersion == textBuffer.version
        val docLineCount = textBuffer.lineCount
        val cached = lineMapCache
        if (
            cached != null &&
            lineMapCacheFoldDataVersion == foldDataVersion &&
            lineMapCacheFoldingEnabled == foldingEnabled &&
            cached.docLineCount == docLineCount
        ) {
            return cached
        }

        val safeDocLineCount = docLineCount.coerceAtLeast(0)
        val built = buildLineMap(
            docLineCount = safeDocLineCount,
            foldingEnabled = foldingEnabled
        )
        lineMapCache = built
        lineMapCacheFoldDataVersion = foldDataVersion
        lineMapCacheFoldingEnabled = foldingEnabled
        return built
    }

    private fun buildLineMap(docLineCount: Int, foldingEnabled: Boolean): LineMap {
        if (docLineCount <= 0) {
            return LineMap(
                docLineCount = 0,
                visualToDocLine = IntArray(0),
                docToVisualLine = IntArray(0),
                hiddenDocLine = BooleanArray(0),
                hiddenOwnerStartLine = IntArray(0)
            )
        }

        if (!foldingEnabled || collapsedFoldStartLines.isEmpty() || foldRegionByStartLine.isEmpty()) {
            return LineMap(
                docLineCount = docLineCount,
                visualToDocLine = IntArray(docLineCount) { it },
                docToVisualLine = IntArray(docLineCount) { it },
                hiddenDocLine = BooleanArray(docLineCount),
                hiddenOwnerStartLine = IntArray(docLineCount) { -1 }
            )
        }

        val hidden = BooleanArray(docLineCount)
        val hiddenOwner = IntArray(docLineCount) { -1 }

        // 只应用“可见的折叠起始行”对应的折叠，避免内层折叠被外层折叠覆盖时重复计算。
        val sortedStarts = collapsedFoldStartLines.toIntArray().apply { sort() }
        for (start in sortedStarts) {
            val region = foldRegionByStartLine[start] ?: continue
            val safeStart = start.coerceIn(0, docLineCount - 1)
            if (hidden[safeStart]) continue
            val safeEnd = region.endLine.coerceIn(safeStart, docLineCount - 1)
            if (safeEnd <= safeStart) continue

            for (line in (safeStart + 1)..safeEnd) {
                hidden[line] = true
                hiddenOwner[line] = safeStart
            }
        }

        var hiddenCount = 0
        for (i in 0 until docLineCount) {
            if (hidden[i]) hiddenCount++
        }
        val visualCount = (docLineCount - hiddenCount).coerceAtLeast(0)
        val visualToDoc = IntArray(visualCount)
        val docToVisual = IntArray(docLineCount) { -1 }

        var visualIndex = 0
        for (docLine in 0 until docLineCount) {
            if (hidden[docLine]) continue
            if (visualIndex >= visualCount) break
            visualToDoc[visualIndex] = docLine
            docToVisual[docLine] = visualIndex
            visualIndex++
        }

        return LineMap(
            docLineCount = docLineCount,
            visualToDocLine = visualToDoc,
            docToVisualLine = docToVisual,
            hiddenDocLine = hidden,
            hiddenOwnerStartLine = hiddenOwner
        )
    }

    private fun lineVisualColumnsForWidth(lineText: String): Int = TextScanKernel.measureVisualColumns(lineText, config.tabSize)

    internal fun isWordChar(c: Char): Boolean = TextScanKernel.isWordChar(c)

    internal fun completionQueryFromCursor(): String {
        val offset = cursorOffset.coerceIn(0, textBuffer.length)
        val position = textBuffer.offsetToPosition(offset)
        val lineText = textBuffer.getLine(position.line)
        val start = TextScanKernel.findWordPrefixStart(lineText, position.column)
        if (start >= position.column) return ""
        return lineText.substring(start, position.column)
    }

    private fun filterCompletionItems(
        items: List<EditorCompletionItem>,
        query: String
    ): List<EditorCompletionItem> {
        if (items.isEmpty()) return emptyList()
        if (query.isBlank()) {
            return items
                .distinctBy { it.completionDedupKey() }
                .sortedBy { kindSortPriority(it.kind) }
                .take(160)
        }

        val caseSensitive = config.completionCaseSensitive
        val exactMatch = ArrayList<EditorCompletionItem>(items.size)
        val exactPrefix = ArrayList<EditorCompletionItem>(items.size)
        val fuzzyPrefix = ArrayList<EditorCompletionItem>(items.size / 2)
        val contains = ArrayList<EditorCompletionItem>(items.size / 2)
        items.forEach { item ->
            val primaryCandidates = item.primaryCompletionCandidates()
            val aliasCandidates = item.aliasCompletionCandidates(caseSensitive)
            when {
                primaryCandidates.any { candidate ->
                    candidate.matchesCompletionQuery(query, caseSensitive)
                } ||
                    aliasCandidates.any { candidate ->
                        candidate.matchesCompletionQuery(query, caseSensitive)
                    } -> {
                    exactMatch.add(item)
                }

                primaryCandidates.any { candidate ->
                    candidate.startsWithCompletionPrefix(query, caseSensitive)
                } -> {
                    exactPrefix.add(item)
                }

                aliasCandidates.any { candidate ->
                    candidate.startsWithCompletionPrefix(query, caseSensitive)
                } -> {
                    fuzzyPrefix.add(item)
                }

                primaryCandidates.any { candidate ->
                    candidate.containsCompletionQuery(query, caseSensitive)
                } ||
                    aliasCandidates.any { candidate ->
                        candidate.containsCompletionQuery(query, caseSensitive)
                    } -> {
                    contains.add(item)
                }
            }
        }
        val relevanceComparator = completionComparator(query = query, caseSensitive = caseSensitive)
        return (
            exactMatch.sortedWith(relevanceComparator) +
                exactPrefix.sortedWith(relevanceComparator) +
                fuzzyPrefix.sortedWith(kindComparator) +
                contains.sortedWith(relevanceComparator)
            )
            .distinctBy { it.completionDedupKey() }
            .take(160)
    }

    private fun completionComparator(
        query: String,
        caseSensitive: Boolean
    ): Comparator<EditorCompletionItem> = compareByDescending<EditorCompletionItem> { it.label.matchesCompletionQuery(query, caseSensitive) }
        .thenByDescending { it.insertText.matchesCompletionQuery(query, caseSensitive) }
        .thenBy { completionPrefixPenalty(it, query, caseSensitive) }
        .thenBy { kindSortPriority(it.kind) }
        .thenBy { shortestCompletionLength(it, caseSensitive) }
        .thenBy(String.CASE_INSENSITIVE_ORDER) { it.label }
        .thenBy { it.label }

    private val kindComparator = compareBy<EditorCompletionItem> { kindSortPriority(it.kind) }

    private fun completionPrefixPenalty(
        item: EditorCompletionItem,
        query: String,
        caseSensitive: Boolean
    ): Int {
        val candidates = item.primaryCompletionCandidates() + item.aliasCompletionCandidates(caseSensitive)
        val matching = candidates.filter { candidate ->
            candidate.startsWithCompletionPrefix(query, caseSensitive)
        }
        if (matching.isEmpty()) return Int.MAX_VALUE
        return matching.minOf { candidate ->
            (candidate.length - query.length).coerceAtLeast(0)
        }
    }

    private fun shortestCompletionLength(
        item: EditorCompletionItem,
        caseSensitive: Boolean
    ): Int = (item.primaryCompletionCandidates() + item.aliasCompletionCandidates(caseSensitive)).asSequence()
        .minOfOrNull { it.length } ?: Int.MAX_VALUE

    private fun String?.matchesCompletionQuery(query: String, caseSensitive: Boolean): Boolean {
        val value = this ?: return false
        return if (caseSensitive) {
            value == query
        } else {
            value.equals(query, ignoreCase = true)
        }
    }

    private fun String?.startsWithCompletionPrefix(query: String, caseSensitive: Boolean): Boolean {
        val value = this ?: return false
        return if (caseSensitive) {
            value.startsWith(query)
        } else {
            value.startsWith(query, ignoreCase = true)
        }
    }

    private fun String?.containsCompletionQuery(query: String, caseSensitive: Boolean): Boolean {
        val value = this ?: return false
        return if (caseSensitive) {
            value.contains(query)
        } else {
            value.contains(query, ignoreCase = true)
        }
    }

    private fun kindSortPriority(kind: EditorCompletionKind): Int = when (kind) {
        EditorCompletionKind.VARIABLE, EditorCompletionKind.FIELD,
        EditorCompletionKind.PROPERTY, EditorCompletionKind.CONSTANT -> 0
        EditorCompletionKind.FUNCTION, EditorCompletionKind.METHOD,
        EditorCompletionKind.CONSTRUCTOR -> 1
        EditorCompletionKind.CLASS, EditorCompletionKind.INTERFACE,
        EditorCompletionKind.STRUCT, EditorCompletionKind.ENUM -> 2
        EditorCompletionKind.MODULE -> 3
        EditorCompletionKind.SNIPPET -> 4
        EditorCompletionKind.KEYWORD -> 5
        else -> 6
    }

    private fun EditorCompletionItem.primaryCompletionCandidates(): List<String> = buildList(2) {
        add(label)
        if (insertText != label) {
            add(insertText)
        }
    }

    private fun EditorCompletionItem.aliasCompletionCandidates(caseSensitive: Boolean): List<String> {
        if (caseSensitive) return emptyList()
        val alias = filterText?.takeIf { it.isNotBlank() } ?: return emptyList()
        if (alias == label || alias == insertText) return emptyList()
        return listOf(alias)
    }

    private fun EditorCompletionItem.completionDedupKey(): String {
        val normalizedLabel = label.lowercase()
        return when (kind) {
            EditorCompletionKind.METHOD,
            EditorCompletionKind.FUNCTION,
            EditorCompletionKind.CONSTRUCTOR -> buildString {
                append(normalizedLabel)
                append('\u0001')
                append(detail.orEmpty())
                append('\u0001')
                append(insertText)
                append('\u0001')
                append(
                    textEdit?.let { edit ->
                        "${edit.startLine}:${edit.startColumn}-${edit.endLine}:${edit.endColumn}:${edit.newText}"
                    }.orEmpty()
                )
                append('\u0001')
                append(
                    additionalTextEdits.joinToString(separator = "\u0002") { edit ->
                        "${edit.startLine}:${edit.startColumn}-${edit.endLine}:${edit.endColumn}:${edit.newText}"
                    }
                )
            }

            else -> normalizedLabel
        }
    }

    private inline fun <T> traceIfSlow(operation: String, block: () -> T): T {
        val startNs = System.nanoTime()
        val result = block()
        val durationMs = (System.nanoTime() - startNs) / 1_000_000L
        if (durationMs > SLOW_OPERATION_THRESHOLD_MS) {
            val now = System.nanoTime() / 1_000_000L
            if (now - lastSlowOperationLogAtMs >= SLOW_OPERATION_LOG_INTERVAL_MS) {
                lastSlowOperationLogAtMs = now
                Timber.tag("EditorPerf").w(
                    "Slow %s: %dms, cursorOffset=%d, lines=%d, length=%d",
                    operation,
                    durationMs,
                    cursorOffset,
                    textBuffer.lineCount,
                    textBuffer.length
                )
            }
        }
        return result
    }

    internal fun <T> traceSlowOperation(operation: String, block: () -> T): T = traceIfSlow(operation, block)

    internal fun emitTextChanged(reason: String) {
        textVersion = textBuffer.version
        emitEvent(
            EditorEvent.TextChanged(
                reason = reason,
                version = textBuffer.version,
                length = textBuffer.length
            )
        )
    }

    internal fun onTextBufferChanged(change: TextChange) {
        val currentVersion = textBuffer.version
        textVersion = currentVersion
        applyTextChangeToSemanticTokens(change)
        adjustFoldRegionsAfterTextChange(change, currentVersion)
        applyWidthSnapshotChange(change, currentVersion)
        wordWrapLayoutCache.applyTextChange(change, currentVersion)
        // 必须在 wordWrapLayoutCache.applyTextChange 之后调：该方法会移除编辑窗内的旧 wrap 条目，
        // applyTextChangeToDocSegmentCounts 的重算才会命中新 layout。
        applyTextChangeToDocSegmentCounts(change, currentVersion)
    }

    /**
     * 文本变化后调整折叠区间，避免折叠状态因版本不匹配而瞬间丢失。
     *
     * 核心策略：
     * - 单行编辑（无行数变化）：折叠区间的行号仍然有效，只需同步版本号。
     * - 多行编辑（有行增删）：编辑点之前的区间保持不变；编辑点之后的区间做行号平移；
     *   与编辑范围重叠的区间丢弃（等待 TreeSitter 重新计算）。
     *
     * 这样做的好处：
     * - 避免每次击键都触发"折叠全部展开 → 重新折叠"的布局抖动（558ms+卡顿）
     * - 用户在折叠区间附近编辑时体验平滑
     */
    private fun adjustFoldRegionsAfterTextChange(change: TextChange, currentVersion: Long) {
        if (!config.codeFolding) return
        if (foldRegionsDocumentVersion < 0L) return
        if (foldRegions.isEmpty()) {
            foldRegionsDocumentVersion = currentVersion
            return
        }

        val lineDelta = change.lineDelta

        if (lineDelta == 0) {
            foldRegionsDocumentVersion = currentVersion
            return
        }

        val editStartLine = change.startLine
        val editEndLine = change.endLine

        val adjustedRegions = ArrayList<FoldRegion>(foldRegions.size)
        val newCollapsed = HashSet<Int>()

        for (region in foldRegions) {
            val isCollapsed = region.startLine in collapsedFoldStartLines
            when {
                region.endLine < editStartLine -> {
                    adjustedRegions.add(region)
                    if (isCollapsed) {
                        newCollapsed.add(region.startLine)
                    }
                }
                region.startLine > editEndLine -> {
                    val newStart = region.startLine + lineDelta
                    val newEnd = region.endLine + lineDelta
                    if (newEnd > newStart && newStart >= 0) {
                        val shifted = FoldRegion(startLine = newStart, endLine = newEnd)
                        adjustedRegions.add(shifted)
                        if (isCollapsed) {
                            newCollapsed.add(newStart)
                        }
                    }
                }
                isCollapsed && editStartLine == region.endLine && editEndLine == region.endLine -> {
                    adjustedRegions.add(region)
                    newCollapsed.add(region.startLine)
                }
                isCollapsed && editStartLine == region.startLine && editEndLine == region.startLine -> {
                    val newEnd = region.endLine + lineDelta
                    if (newEnd > region.startLine) {
                        adjustedRegions.add(FoldRegion(startLine = region.startLine, endLine = newEnd))
                        newCollapsed.add(region.startLine)
                    }
                }
                // Overlapping regions: dropped, will be recomputed by TreeSitter
            }
        }

        foldRegions = adjustedRegions
        foldRegionByStartLine = adjustedRegions.associateBy { it.startLine }
        collapsedFoldStartLines = newCollapsed
        foldRegionsDocumentVersion = currentVersion
        applyFoldableDecorations(foldRegionByStartLine.keys)
        onFoldDataChanged()
    }

    internal fun emitEvent(event: EditorEvent) {
        _events.tryEmit(event)
    }

    private fun emitScrollChangedIfNeeded(oldX: Float, oldY: Float) {
        if (oldX == scrollOffsetXPx && oldY == scrollOffsetPx) return
        emitEvent(
            EditorEvent.ScrollChanged(
                offsetX = scrollOffsetXPx,
                offsetY = scrollOffsetPx
            )
        )
    }
}
