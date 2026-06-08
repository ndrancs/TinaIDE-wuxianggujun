package com.wuxianggujun.tinaide.core.textengine

/**
 * 纯文本扫描内核：Android 上优先走 native kernel，本地 JVM 单测自动回退 Kotlin。
 */
object TextScanKernel {
    private val backend: TextScanBackend by lazy(LazyThreadSafetyMode.PUBLICATION) {
        NativeTextScanBackend.createOrNull() ?: KotlinTextScanBackend()
    }

    fun hasActiveSignatureHelpContext(
        textBuffer: TextBuffer,
        cursorOffset: Int
    ): Boolean {
        val safeCursor = cursorOffset.coerceIn(0, textBuffer.length)
        if (safeCursor <= 0) return false
        val scanStart = (safeCursor - SIGNATURE_HELP_CONTEXT_SCAN_LIMIT).coerceAtLeast(0)
        return hasActiveSignatureHelpContext(textBuffer.substring(scanStart, safeCursor))
    }

    fun hasActiveSignatureHelpContext(textBeforeCursor: String): Boolean {
        if (textBeforeCursor.isEmpty()) return false
        return backend.hasActiveSignatureHelpContext(textBeforeCursor)
    }

    fun computeBracketInfo(
        startDepth: Int,
        lineText: String
    ): List<BracketScanResult> {
        if (lineText.isEmpty()) return emptyList()
        return backend.computeBracketInfo(startDepth.coerceAtLeast(0), lineText)
    }

    fun advanceBracketDepth(startDepth: Int, lineText: String): Int {
        if (lineText.isEmpty()) return startDepth.coerceAtLeast(0)
        return backend.advanceBracketDepth(startDepth.coerceAtLeast(0), lineText)
    }

    fun advanceBracketDepth(startDepth: Int, lineText: String, endColumn: Int): Int {
        val safeStartDepth = startDepth.coerceAtLeast(0)
        if (lineText.isEmpty()) return safeStartDepth

        val safeEndColumn = endColumn.coerceIn(0, lineText.length)
        if (safeEndColumn <= 0) return safeStartDepth
        if (safeEndColumn >= lineText.length) {
            return backend.advanceBracketDepth(safeStartDepth, lineText)
        }
        return backend.advanceBracketDepth(safeStartDepth, lineText, safeEndColumn)
    }

    fun computeLineBoundaryBracketDepths(startDepth: Int, text: String): IntArray {
        val safeStartDepth = startDepth.coerceAtLeast(0)
        if (text.isEmpty()) return intArrayOf(safeStartDepth, safeStartDepth)
        return backend.computeLineBoundaryBracketDepths(safeStartDepth, text)
    }

    fun findMatchingBracket(text: String, cursorOffset: Int): BracketPairMatchResult? {
        if (text.isEmpty()) return null
        val safeCursor = cursorOffset.coerceIn(0, text.length)
        return backend.findMatchingBracket(text, safeCursor)
    }

    fun findBracketPairs(text: String): List<BracketPairScanResult> {
        if (text.isEmpty()) return emptyList()
        return backend.findBracketPairs(text)
    }

    fun findBracketGuideSpans(
        text: String,
        visibleStartLine: Int,
        visibleEndLine: Int,
        includeOpenSpansAtEnd: Boolean
    ): List<BracketGuideSpanScanResult> {
        if (text.isEmpty()) return emptyList()
        val safeVisibleStart = visibleStartLine.coerceAtLeast(0)
        val safeVisibleEnd = visibleEndLine.coerceAtLeast(safeVisibleStart)
        return backend.findBracketGuideSpans(
            text = text,
            visibleStartLine = safeVisibleStart,
            visibleEndLine = safeVisibleEnd,
            includeOpenSpansAtEnd = includeOpenSpansAtEnd
        )
    }

    fun scanBracketSnapshot(
        startDepth: Int,
        text: String,
        visibleStartLine: Int,
        visibleEndLine: Int,
        includeOpenSpansAtEnd: Boolean
    ): BracketSnapshotScanResult {
        if (text.isEmpty()) return BracketSnapshotScanResult()
        val safeVisibleStart = visibleStartLine.coerceAtLeast(0)
        val safeVisibleEnd = visibleEndLine.coerceAtLeast(safeVisibleStart)
        return backend.scanBracketSnapshot(
            startDepth = startDepth.coerceAtLeast(0),
            text = text,
            visibleStartLine = safeVisibleStart,
            visibleEndLine = safeVisibleEnd,
            includeOpenSpansAtEnd = includeOpenSpansAtEnd
        )
    }

    fun findWordBounds(lineText: String, column: Int): WordBounds? {
        if (lineText.isEmpty()) return null
        val safeColumn = column.coerceIn(0, lineText.length)
        return backend.findWordBounds(lineText, safeColumn)
    }

    fun findWordPrefixStart(lineText: String, column: Int): Int {
        if (lineText.isEmpty()) return 0
        val safeColumn = column.coerceIn(0, lineText.length)
        return backend.findWordPrefixStart(lineText, safeColumn)
    }

    fun findWholeWordMatches(lineText: String, word: String): IntArray {
        if (lineText.isEmpty() || word.isEmpty() || word.length > lineText.length) return IntArray(0)
        return backend.findWholeWordMatches(lineText, word)
    }

    fun findWhitespaceMarkers(lineText: String, boundaryOnly: Boolean): IntArray {
        if (lineText.isEmpty()) return IntArray(0)
        return backend.findWhitespaceMarkers(lineText, boundaryOnly)
    }

    fun findTabColumns(lineText: String): IntArray {
        if (lineText.isEmpty() || lineText.indexOf('\t') < 0) return IntArray(0)
        return backend.findTabColumns(lineText)
    }

    fun measureVisualColumns(lineText: String, tabSize: Int): Int {
        if (lineText.isEmpty()) return 0
        return backend.measureVisualColumns(lineText, tabSize.coerceAtLeast(1))
    }

    fun measureVisualColumns(lineText: String, tabSize: Int, endColumn: Int): Int {
        if (lineText.isEmpty()) return 0
        val safeTabSize = tabSize.coerceAtLeast(1)
        val safeEndColumn = endColumn.coerceIn(0, lineText.length)
        if (safeEndColumn <= 0) return 0
        if (safeEndColumn >= lineText.length) {
            return backend.measureVisualColumns(lineText, safeTabSize)
        }
        return backend.measureVisualColumns(lineText, safeTabSize, safeEndColumn)
    }

    fun findWrapSegmentStarts(lineText: String, wrapColumns: Int, tabSize: Int): IntArray {
        if (lineText.isEmpty()) return intArrayOf(0)
        return backend.findWrapSegmentStarts(
            lineText = lineText,
            wrapColumns = wrapColumns.coerceAtLeast(1),
            tabSize = tabSize.coerceAtLeast(1)
        )
    }

    fun buildVisualColumnPrefix(lineText: String, tabSize: Int): IntArray {
        if (lineText.isEmpty()) return intArrayOf(0)
        return backend.buildVisualColumnPrefix(lineText, tabSize.coerceAtLeast(1))
    }

    fun scanLineWhitespace(lineText: String, tabSize: Int): LineWhitespaceInfo {
        if (lineText.isEmpty()) {
            return LineWhitespaceInfo(
                leadingWhitespaceEnd = 0,
                leadingIndentColumns = 0,
                trailingWhitespaceStart = 0,
                outdentRemoveCount = 0
            )
        }
        return backend.scanLineWhitespace(lineText, tabSize.coerceAtLeast(1))
    }

    fun whitespaceMarkerColumn(marker: Int): Int = marker ushr 1

    fun whitespaceMarkerIsTab(marker: Int): Boolean = (marker and WHITESPACE_MARKER_TAB_FLAG) != 0

    fun isWordChar(char: Char): Boolean = char == '_' || char.isLetterOrDigit()
}

data class BracketScanResult(
    val column: Int,
    val depth: Int,
    val isOpen: Boolean
)

data class BracketPairMatchResult(
    val openOffset: Int,
    val closeOffset: Int
)

data class BracketPairScanResult(
    val openOffset: Int,
    val closeOffset: Int,
    val depth: Int
)

data class BracketGuideSpanScanResult(
    val openLine: Int,
    val openColumn: Int,
    val closeLine: Int,
    val closeColumn: Int,
    val depth: Int,
    val openEnded: Boolean
)

data class VisibleLineBracketScanResult(
    val line: Int,
    val column: Int,
    val depth: Int,
    val isOpen: Boolean
)

data class BracketSnapshotScanResult(
    val pairs: List<BracketPairScanResult> = emptyList(),
    val guides: List<BracketGuideSpanScanResult> = emptyList(),
    val visibleLineBrackets: List<VisibleLineBracketScanResult> = emptyList(),
    val lineBoundaryDepths: IntArray = IntArray(0)
)

data class WordBounds(
    val start: Int,
    val end: Int
)

data class LineWhitespaceInfo(
    val leadingWhitespaceEnd: Int,
    val leadingIndentColumns: Int,
    val trailingWhitespaceStart: Int,
    val outdentRemoveCount: Int
)

private const val SIGNATURE_HELP_CONTEXT_SCAN_LIMIT = 32_768
private const val WHITESPACE_MARKER_TAB_FLAG = 1

private val SIGNATURE_HELP_CONTROL_KEYWORDS = setOf("if", "for", "while", "when", "catch")
private val SIGNATURE_HELP_DECLARATION_KEYWORDS = setOf(
    "fun",
    "class",
    "interface",
    "object",
    "typealias",
    "val",
    "var",
    "constructor",
    "init",
    "get",
    "set"
)
private val SIGNATURE_HELP_NON_CALL_TERMINALS = SIGNATURE_HELP_CONTROL_KEYWORDS +
    SIGNATURE_HELP_DECLARATION_KEYWORDS +
    setOf("else", "do", "try")

private interface TextScanBackend {
    fun hasActiveSignatureHelpContext(textBeforeCursor: String): Boolean
    fun computeBracketInfo(startDepth: Int, lineText: String): List<BracketScanResult>
    fun advanceBracketDepth(startDepth: Int, lineText: String): Int
    fun advanceBracketDepth(startDepth: Int, lineText: String, endColumn: Int): Int
    fun computeLineBoundaryBracketDepths(startDepth: Int, text: String): IntArray
    fun findMatchingBracket(text: String, cursorOffset: Int): BracketPairMatchResult?
    fun findBracketPairs(text: String): List<BracketPairScanResult>
    fun findBracketGuideSpans(
        text: String,
        visibleStartLine: Int,
        visibleEndLine: Int,
        includeOpenSpansAtEnd: Boolean
    ): List<BracketGuideSpanScanResult>
    fun scanBracketSnapshot(
        startDepth: Int,
        text: String,
        visibleStartLine: Int,
        visibleEndLine: Int,
        includeOpenSpansAtEnd: Boolean
    ): BracketSnapshotScanResult
    fun findWordBounds(lineText: String, column: Int): WordBounds?
    fun findWordPrefixStart(lineText: String, column: Int): Int
    fun findWholeWordMatches(lineText: String, word: String): IntArray
    fun findWhitespaceMarkers(lineText: String, boundaryOnly: Boolean): IntArray
    fun findTabColumns(lineText: String): IntArray
    fun measureVisualColumns(lineText: String, tabSize: Int): Int
    fun measureVisualColumns(lineText: String, tabSize: Int, endColumn: Int): Int
    fun findWrapSegmentStarts(lineText: String, wrapColumns: Int, tabSize: Int): IntArray
    fun buildVisualColumnPrefix(lineText: String, tabSize: Int): IntArray
    fun scanLineWhitespace(lineText: String, tabSize: Int): LineWhitespaceInfo
}

private class NativeTextScanBackend private constructor() : TextScanBackend {
    companion object {
        fun createOrNull(): NativeTextScanBackend? {
            if (!NativeTextScanKernel.isAvailable()) return null
            return NativeTextScanBackend()
        }
    }

    override fun hasActiveSignatureHelpContext(textBeforeCursor: String): Boolean = NativeTextScanKernel.nativeHasActiveSignatureHelpContext(textBeforeCursor)

    override fun computeBracketInfo(
        startDepth: Int,
        lineText: String
    ): List<BracketScanResult> {
        val raw = NativeTextScanKernel.nativeComputeBracketInfo(startDepth, lineText)
        if (raw.isEmpty()) return emptyList()

        val result = ArrayList<BracketScanResult>(raw.size / 3)
        var index = 0
        while (index + 2 < raw.size) {
            result += BracketScanResult(
                column = raw[index],
                depth = raw[index + 1],
                isOpen = raw[index + 2] != 0
            )
            index += 3
        }
        return result
    }

    override fun advanceBracketDepth(startDepth: Int, lineText: String): Int = NativeTextScanKernel.nativeAdvanceBracketDepth(startDepth, lineText)

    override fun advanceBracketDepth(startDepth: Int, lineText: String, endColumn: Int): Int = NativeTextScanKernel.nativeAdvanceBracketDepthPrefix(startDepth, lineText, endColumn)

    override fun computeLineBoundaryBracketDepths(startDepth: Int, text: String): IntArray = NativeTextScanKernel.nativeComputeLineBoundaryBracketDepths(startDepth, text)

    override fun findMatchingBracket(text: String, cursorOffset: Int): BracketPairMatchResult? {
        val raw = NativeTextScanKernel.nativeFindMatchingBracket(text, cursorOffset)
        if (raw.size < 2) return null
        return BracketPairMatchResult(openOffset = raw[0], closeOffset = raw[1])
    }

    override fun findBracketPairs(text: String): List<BracketPairScanResult> {
        val raw = NativeTextScanKernel.nativeFindBracketPairs(text)
        if (raw.isEmpty()) return emptyList()

        val result = ArrayList<BracketPairScanResult>(raw.size / 3)
        var index = 0
        while (index + 2 < raw.size) {
            result += BracketPairScanResult(
                openOffset = raw[index],
                closeOffset = raw[index + 1],
                depth = raw[index + 2]
            )
            index += 3
        }
        return result
    }

    override fun findBracketGuideSpans(
        text: String,
        visibleStartLine: Int,
        visibleEndLine: Int,
        includeOpenSpansAtEnd: Boolean
    ): List<BracketGuideSpanScanResult> {
        val raw = NativeTextScanKernel.nativeFindBracketGuideSpans(
            text = text,
            visibleStartLine = visibleStartLine,
            visibleEndLine = visibleEndLine,
            includeOpenSpansAtEnd = includeOpenSpansAtEnd
        )
        if (raw.isEmpty()) return emptyList()

        val result = ArrayList<BracketGuideSpanScanResult>(raw.size / 6)
        var index = 0
        while (index + 5 < raw.size) {
            result += BracketGuideSpanScanResult(
                openLine = raw[index],
                openColumn = raw[index + 1],
                closeLine = raw[index + 2],
                closeColumn = raw[index + 3],
                depth = raw[index + 4],
                openEnded = raw[index + 5] != 0
            )
            index += 6
        }
        return result
    }

    override fun scanBracketSnapshot(
        startDepth: Int,
        text: String,
        visibleStartLine: Int,
        visibleEndLine: Int,
        includeOpenSpansAtEnd: Boolean
    ): BracketSnapshotScanResult {
        val raw = NativeTextScanKernel.nativeScanBracketSnapshot(
            startDepth = startDepth,
            text = text,
            visibleStartLine = visibleStartLine,
            visibleEndLine = visibleEndLine,
            includeOpenSpansAtEnd = includeOpenSpansAtEnd
        )
        if (raw.size < 4) return BracketSnapshotScanResult()

        val pairCount = raw[0].coerceAtLeast(0)
        val guideCount = raw[1].coerceAtLeast(0)
        val lineBracketCount = raw[2].coerceAtLeast(0)
        val lineBoundaryDepthCount = raw[3].coerceAtLeast(0)
        val expectedSize = 4 +
            pairCount * 3 +
            guideCount * 6 +
            lineBracketCount * 4 +
            lineBoundaryDepthCount
        if (raw.size < expectedSize) return BracketSnapshotScanResult()

        var index = 4
        val pairs = ArrayList<BracketPairScanResult>(pairCount)
        repeat(pairCount) {
            pairs += BracketPairScanResult(
                openOffset = raw[index],
                closeOffset = raw[index + 1],
                depth = raw[index + 2]
            )
            index += 3
        }

        val guides = ArrayList<BracketGuideSpanScanResult>(guideCount)
        repeat(guideCount) {
            guides += BracketGuideSpanScanResult(
                openLine = raw[index],
                openColumn = raw[index + 1],
                closeLine = raw[index + 2],
                closeColumn = raw[index + 3],
                depth = raw[index + 4],
                openEnded = raw[index + 5] != 0
            )
            index += 6
        }

        val lineBrackets = ArrayList<VisibleLineBracketScanResult>(lineBracketCount)
        repeat(lineBracketCount) {
            lineBrackets += VisibleLineBracketScanResult(
                line = raw[index],
                column = raw[index + 1],
                depth = raw[index + 2],
                isOpen = raw[index + 3] != 0
            )
            index += 4
        }

        val lineBoundaryDepths = IntArray(lineBoundaryDepthCount)
        repeat(lineBoundaryDepthCount) { boundaryIndex ->
            lineBoundaryDepths[boundaryIndex] = raw[index]
            index++
        }

        return BracketSnapshotScanResult(
            pairs = pairs,
            guides = guides,
            visibleLineBrackets = lineBrackets,
            lineBoundaryDepths = lineBoundaryDepths
        )
    }

    override fun findWordBounds(lineText: String, column: Int): WordBounds? {
        val raw = NativeTextScanKernel.nativeFindWordBounds(lineText, column)
        if (raw.size < 2) return null
        return WordBounds(start = raw[0], end = raw[1])
    }

    override fun findWordPrefixStart(lineText: String, column: Int): Int = NativeTextScanKernel.nativeFindWordPrefixStart(lineText, column)

    override fun findWholeWordMatches(lineText: String, word: String): IntArray = NativeTextScanKernel.nativeFindWholeWordMatches(lineText, word)

    override fun findWhitespaceMarkers(lineText: String, boundaryOnly: Boolean): IntArray = NativeTextScanKernel.nativeFindWhitespaceMarkers(lineText, boundaryOnly)

    override fun findTabColumns(lineText: String): IntArray = NativeTextScanKernel.nativeFindTabColumns(lineText)

    override fun measureVisualColumns(lineText: String, tabSize: Int): Int = NativeTextScanKernel.nativeMeasureVisualColumns(lineText, tabSize)

    override fun measureVisualColumns(lineText: String, tabSize: Int, endColumn: Int): Int = NativeTextScanKernel.nativeMeasureVisualColumnsPrefix(lineText, tabSize, endColumn)

    override fun findWrapSegmentStarts(lineText: String, wrapColumns: Int, tabSize: Int): IntArray = NativeTextScanKernel.nativeFindWrapSegmentStarts(lineText, wrapColumns, tabSize)

    override fun buildVisualColumnPrefix(lineText: String, tabSize: Int): IntArray = NativeTextScanKernel.nativeBuildVisualColumnPrefix(lineText, tabSize)

    override fun scanLineWhitespace(lineText: String, tabSize: Int): LineWhitespaceInfo {
        val raw = NativeTextScanKernel.nativeScanLineWhitespace(lineText, tabSize)
        if (raw.size < 4) {
            return LineWhitespaceInfo(
                leadingWhitespaceEnd = 0,
                leadingIndentColumns = 0,
                trailingWhitespaceStart = lineText.length,
                outdentRemoveCount = 0
            )
        }
        return LineWhitespaceInfo(
            leadingWhitespaceEnd = raw[0],
            leadingIndentColumns = raw[1],
            trailingWhitespaceStart = raw[2],
            outdentRemoveCount = raw[3]
        )
    }
}

private class KotlinTextScanBackend : TextScanBackend {
    override fun hasActiveSignatureHelpContext(textBeforeCursor: String): Boolean {
        val delimiterStack = mutableListOf<SignatureHelpDelimiter>()
        val significantTokens = mutableListOf<SignatureHelpScanToken>()
        var inSingleQuote = false
        var inDoubleQuote = false
        var inRawString = false
        var inLineComment = false
        var blockCommentDepth = 0
        var escaped = false

        var index = 0
        while (index < textBeforeCursor.length) {
            val current = textBeforeCursor[index]
            val next = textBeforeCursor.getOrNull(index + 1)
            val third = textBeforeCursor.getOrNull(index + 2)

            if (inLineComment) {
                if (current == '\n' || current == '\r') inLineComment = false
                index++
                continue
            }
            if (blockCommentDepth > 0) {
                when {
                    current == '/' && next == '*' -> {
                        blockCommentDepth++
                        index += 2
                        continue
                    }

                    current == '*' && next == '/' -> {
                        blockCommentDepth--
                        index += 2
                        continue
                    }
                }
                index++
                continue
            }
            if (inRawString) {
                if (current == '"' && next == '"' && third == '"') {
                    inRawString = false
                    index += 3
                    continue
                }
                index++
                continue
            }
            if (escaped) {
                escaped = false
                index++
                continue
            }
            if (inSingleQuote) {
                if (current == '\\') {
                    escaped = true
                    index++
                    continue
                }
                if (current == '\'') inSingleQuote = false
                index++
                continue
            }
            if (inDoubleQuote) {
                if (current == '\\') {
                    escaped = true
                    index++
                    continue
                }
                if (current == '"') inDoubleQuote = false
                index++
                continue
            }

            when {
                current == '/' && next == '/' -> {
                    inLineComment = true
                    index += 2
                    continue
                }

                current == '/' && next == '*' -> {
                    blockCommentDepth = 1
                    index += 2
                    continue
                }

                current == '"' && next == '"' && third == '"' -> {
                    inRawString = true
                    index += 3
                    continue
                }

                current.isSignatureHelpIdentifierChar() -> {
                    val identifierStart = index
                    index++
                    while (
                        index < textBeforeCursor.length &&
                        textBeforeCursor[index].isSignatureHelpIdentifierChar()
                    ) {
                        index++
                    }
                    significantTokens += SignatureHelpScanToken.Identifier(
                        textBeforeCursor.substring(identifierStart, index)
                    )
                    continue
                }

                else -> when (current) {
                    '\'' -> inSingleQuote = true
                    '"' -> inDoubleQuote = true
                    '(' -> delimiterStack += SignatureHelpDelimiter(
                        kind = resolveSignatureHelpParenKind(significantTokens)
                    )
                    ')' -> {
                        val kind = popLastSignatureHelpParen(delimiterStack)
                        significantTokens += SignatureHelpScanToken.ParenClose(
                            kind = kind ?: SignatureHelpParenKind.Other
                        )
                    }
                    '{' -> delimiterStack += SignatureHelpDelimiter(
                        kind = if (startsSignatureHelpTrailingLambda(significantTokens)) {
                            SignatureHelpContextKind.TrailingLambda
                        } else {
                            SignatureHelpContextKind.OtherBrace
                        }
                    )
                    '}' -> popLastSignatureHelpBrace(delimiterStack)
                    else -> if (!current.isWhitespace()) {
                        significantTokens += SignatureHelpScanToken.Symbol(current)
                    }
                }
            }
            index++
        }

        return delimiterStack.any { delimiter ->
            when (delimiter.kind) {
                SignatureHelpContextKind.CallParen,
                SignatureHelpContextKind.TrailingLambda -> true
                SignatureHelpContextKind.ControlParen,
                SignatureHelpContextKind.OtherParen,
                SignatureHelpContextKind.OtherBrace -> false
            }
        }
    }

    override fun computeBracketInfo(
        startDepth: Int,
        lineText: String
    ): List<BracketScanResult> {
        val result = ArrayList<BracketScanResult>(4)
        var depth = startDepth

        for (column in lineText.indices) {
            when (lineText[column]) {
                '(', '[', '{' -> {
                    result += BracketScanResult(column = column, depth = depth, isOpen = true)
                    depth++
                }
                ')', ']', '}' -> {
                    depth--
                    result += BracketScanResult(
                        column = column,
                        depth = depth.coerceAtLeast(0),
                        isOpen = false
                    )
                }
            }
        }

        return result
    }

    override fun advanceBracketDepth(startDepth: Int, lineText: String): Int {
        var depth = startDepth
        for (column in lineText.indices) {
            when (lineText[column]) {
                '(', '[', '{' -> depth++
                ')', ']', '}' -> depth = (depth - 1).coerceAtLeast(0)
            }
        }
        return depth
    }

    override fun advanceBracketDepth(startDepth: Int, lineText: String, endColumn: Int): Int {
        var depth = startDepth
        val safeEndColumn = endColumn.coerceIn(0, lineText.length)
        for (column in 0 until safeEndColumn) {
            when (lineText[column]) {
                '(', '[', '{' -> depth++
                ')', ']', '}' -> depth = (depth - 1).coerceAtLeast(0)
            }
        }
        return depth
    }

    override fun computeLineBoundaryBracketDepths(startDepth: Int, text: String): IntArray {
        var result = IntArray(8)
        var count = 0
        var depth = startDepth.coerceAtLeast(0)

        result[count++] = depth
        for (ch in text) {
            when (ch) {
                '(', '[', '{' -> depth++
                ')', ']', '}' -> depth = (depth - 1).coerceAtLeast(0)
            }
            if (ch == '\n') {
                if (count >= result.size) {
                    result = result.copyOf(result.size * 2)
                }
                result[count++] = depth
            }
        }
        if (count >= result.size) {
            result = result.copyOf(result.size * 2)
        }
        result[count++] = depth
        return result.copyOf(count)
    }

    override fun findMatchingBracket(text: String, cursorOffset: Int): BracketPairMatchResult? {
        val atCursor = text.getOrNull(cursorOffset)
        val beforeCursor = text.getOrNull(cursorOffset - 1)
        return when {
            atCursor != null && atCursor.isOpenBracket() ->
                findForwardMatchingBracket(text, cursorOffset, atCursor)
            beforeCursor != null && beforeCursor.isCloseBracket() ->
                findBackwardMatchingBracket(text, cursorOffset - 1, beforeCursor)
            atCursor != null && atCursor.isCloseBracket() ->
                findBackwardMatchingBracket(text, cursorOffset, atCursor)
            beforeCursor != null && beforeCursor.isOpenBracket() ->
                findForwardMatchingBracket(text, cursorOffset - 1, beforeCursor)
            else -> null
        }
    }

    override fun findBracketPairs(text: String): List<BracketPairScanResult> {
        val result = ArrayList<BracketPairScanResult>(8)
        val stack = ArrayList<KotlinOpenBracketRecord>(8)

        for (offset in text.indices) {
            when (val ch = text[offset]) {
                '(', '[', '{' -> stack += KotlinOpenBracketRecord(
                    offset = offset,
                    bracket = ch,
                    depth = stack.size
                )
                ')', ']', '}' -> {
                    if (stack.isEmpty()) continue
                    val open = stack.last()
                    if (open.bracket != matchingOpenBracket(ch)) continue
                    stack.removeAt(stack.lastIndex)
                    result += BracketPairScanResult(
                        openOffset = open.offset,
                        closeOffset = offset,
                        depth = open.depth
                    )
                }
            }
        }

        return result
    }

    override fun findBracketGuideSpans(
        text: String,
        visibleStartLine: Int,
        visibleEndLine: Int,
        includeOpenSpansAtEnd: Boolean
    ): List<BracketGuideSpanScanResult> {
        val result = ArrayList<BracketGuideSpanScanResult>(8)
        val stack = ArrayList<KotlinGuideOpenBracketRecord>(8)
        val safeVisibleStart = visibleStartLine.coerceAtLeast(0)
        val safeVisibleEnd = visibleEndLine.coerceAtLeast(safeVisibleStart)
        var line = 0
        var column = 0

        for (ch in text) {
            when (ch) {
                '(', '[', '{' -> stack += KotlinGuideOpenBracketRecord(
                    line = line,
                    column = column,
                    bracket = ch,
                    depth = stack.size
                )
                ')', ']', '}' -> {
                    if (stack.isNotEmpty()) {
                        val open = stack.last()
                        if (open.bracket == matchingOpenBracket(ch)) {
                            stack.removeAt(stack.lastIndex)
                            if (line > open.line &&
                                line >= safeVisibleStart &&
                                open.line <= safeVisibleEnd
                            ) {
                                result += BracketGuideSpanScanResult(
                                    openLine = open.line,
                                    openColumn = open.column,
                                    closeLine = line,
                                    closeColumn = column,
                                    depth = open.depth,
                                    openEnded = false
                                )
                            }
                        }
                    }
                }
            }

            if (ch == '\n') {
                line++
                column = 0
            } else {
                column++
            }
        }

        if (includeOpenSpansAtEnd) {
            stack.forEach { open ->
                if (safeVisibleEnd > open.line && open.line <= safeVisibleEnd) {
                    result += BracketGuideSpanScanResult(
                        openLine = open.line,
                        openColumn = open.column,
                        closeLine = safeVisibleEnd,
                        closeColumn = -1,
                        depth = open.depth,
                        openEnded = true
                    )
                }
            }
        }

        return result
    }

    override fun scanBracketSnapshot(
        startDepth: Int,
        text: String,
        visibleStartLine: Int,
        visibleEndLine: Int,
        includeOpenSpansAtEnd: Boolean
    ): BracketSnapshotScanResult {
        val pairs = ArrayList<BracketPairScanResult>(8)
        val guides = ArrayList<BracketGuideSpanScanResult>(8)
        val visibleLineBrackets = ArrayList<VisibleLineBracketScanResult>(8)
        val lineBoundaryDepths = ArrayList<Int>(8)
        val stack = ArrayList<KotlinSnapshotOpenBracketRecord>(8)
        val safeVisibleStart = visibleStartLine.coerceAtLeast(0)
        val safeVisibleEnd = visibleEndLine.coerceAtLeast(safeVisibleStart)
        val visibleRange = safeVisibleStart..safeVisibleEnd
        var rainbowDepth = startDepth.coerceAtLeast(0)
        var line = 0
        var column = 0

        lineBoundaryDepths += rainbowDepth

        for (offset in text.indices) {
            when (val ch = text[offset]) {
                '(', '[', '{' -> {
                    if (line in visibleRange) {
                        visibleLineBrackets += VisibleLineBracketScanResult(
                            line = line,
                            column = column,
                            depth = rainbowDepth,
                            isOpen = true
                        )
                    }
                    stack += KotlinSnapshotOpenBracketRecord(
                        offset = offset,
                        line = line,
                        column = column,
                        bracket = ch,
                        depth = startDepth + stack.size
                    )
                    rainbowDepth++
                }

                ')', ']', '}' -> {
                    rainbowDepth = (rainbowDepth - 1).coerceAtLeast(0)
                    if (line in visibleRange) {
                        visibleLineBrackets += VisibleLineBracketScanResult(
                            line = line,
                            column = column,
                            depth = rainbowDepth,
                            isOpen = false
                        )
                    }
                    if (stack.isNotEmpty()) {
                        val open = stack.last()
                        if (open.bracket == matchingOpenBracket(ch)) {
                            stack.removeAt(stack.lastIndex)
                            pairs += BracketPairScanResult(
                                openOffset = open.offset,
                                closeOffset = offset,
                                depth = open.depth
                            )
                            if (line > open.line &&
                                line >= safeVisibleStart &&
                                open.line <= safeVisibleEnd
                            ) {
                                guides += BracketGuideSpanScanResult(
                                    openLine = open.line,
                                    openColumn = open.column,
                                    closeLine = line,
                                    closeColumn = column,
                                    depth = open.depth,
                                    openEnded = false
                                )
                            }
                        }
                    }
                }
            }

            if (text[offset] == '\n') {
                line++
                column = 0
                lineBoundaryDepths += rainbowDepth
            } else {
                column++
            }
        }

        lineBoundaryDepths += rainbowDepth

        if (includeOpenSpansAtEnd) {
            stack.forEach { open ->
                if (safeVisibleEnd > open.line && open.line <= safeVisibleEnd) {
                    guides += BracketGuideSpanScanResult(
                        openLine = open.line,
                        openColumn = open.column,
                        closeLine = safeVisibleEnd,
                        closeColumn = -1,
                        depth = open.depth,
                        openEnded = true
                    )
                }
            }
        }

        return BracketSnapshotScanResult(
            pairs = pairs,
            guides = guides,
            visibleLineBrackets = visibleLineBrackets,
            lineBoundaryDepths = lineBoundaryDepths.toIntArray()
        )
    }

    override fun findWordBounds(lineText: String, column: Int): WordBounds? {
        val pivotIndex = when {
            column < lineText.length && TextScanKernel.isWordChar(lineText[column]) -> column
            column > 0 && TextScanKernel.isWordChar(lineText[column - 1]) -> column - 1
            else -> return null
        }

        var start = pivotIndex
        var end = pivotIndex + 1
        while (start > 0 && TextScanKernel.isWordChar(lineText[start - 1])) {
            start--
        }
        while (end < lineText.length && TextScanKernel.isWordChar(lineText[end])) {
            end++
        }
        return WordBounds(start = start, end = end)
    }

    override fun findWordPrefixStart(lineText: String, column: Int): Int {
        var start = column.coerceIn(0, lineText.length)
        while (start > 0 && TextScanKernel.isWordChar(lineText[start - 1])) {
            start--
        }
        return start
    }

    override fun findWholeWordMatches(lineText: String, word: String): IntArray {
        if (word.isEmpty() || word.length > lineText.length) return IntArray(0)

        val result = ArrayList<Int>(4)
        var searchFrom = 0
        while (searchFrom <= lineText.length - word.length) {
            val index = lineText.indexOf(word, searchFrom)
            if (index < 0) break

            val beforeIndex = index - 1
            val afterIndex = index + word.length
            val isWordBoundaryStart = beforeIndex < 0 || !TextScanKernel.isWordChar(lineText[beforeIndex])
            val isWordBoundaryEnd = afterIndex >= lineText.length || !TextScanKernel.isWordChar(lineText[afterIndex])
            if (isWordBoundaryStart && isWordBoundaryEnd) {
                result += index
            }
            searchFrom = index + 1
        }
        return result.toIntArray()
    }

    override fun findWhitespaceMarkers(lineText: String, boundaryOnly: Boolean): IntArray {
        if (lineText.isEmpty()) return IntArray(0)

        if (!boundaryOnly) {
            val result = ArrayList<Int>(lineText.length / 4)
            for (column in lineText.indices) {
                when (lineText[column]) {
                    ' ' -> result += encodeWhitespaceMarker(column, isTab = false)
                    '\t' -> result += encodeWhitespaceMarker(column, isTab = true)
                }
            }
            return result.toIntArray()
        }

        var leadingEnd = 0
        while (leadingEnd < lineText.length && lineText[leadingEnd].isRenderableWhitespace()) {
            leadingEnd++
        }
        if (leadingEnd == 0) return IntArray(0)
        if (leadingEnd == lineText.length) {
            return IntArray(lineText.length) { column ->
                encodeWhitespaceMarker(column, isTab = lineText[column] == '\t')
            }
        }

        var trailingStart = lineText.length
        while (trailingStart > leadingEnd && lineText[trailingStart - 1].isRenderableWhitespace()) {
            trailingStart--
        }

        val result = IntArray(leadingEnd + (lineText.length - trailingStart))
        var outIndex = 0
        for (column in 0 until leadingEnd) {
            result[outIndex++] = encodeWhitespaceMarker(column, isTab = lineText[column] == '\t')
        }
        for (column in trailingStart until lineText.length) {
            result[outIndex++] = encodeWhitespaceMarker(column, isTab = lineText[column] == '\t')
        }
        return result
    }

    override fun findTabColumns(lineText: String): IntArray {
        if (lineText.indexOf('\t') < 0) return IntArray(0)

        var result = IntArray(4)
        var count = 0
        for (index in lineText.indices) {
            if (lineText[index] != '\t') continue
            if (count >= result.size) {
                result = result.copyOf(result.size * 2)
            }
            result[count++] = index
        }
        return result.copyOf(count)
    }

    override fun measureVisualColumns(lineText: String, tabSize: Int): Int {
        if (lineText.indexOf('\t') < 0) return lineText.length

        val safeTabSize = tabSize.coerceAtLeast(1)
        var visualColumns = 0
        for (ch in lineText) {
            visualColumns += if (ch == '\t') {
                safeTabSize - (visualColumns % safeTabSize)
            } else {
                1
            }
        }
        return visualColumns
    }

    override fun measureVisualColumns(lineText: String, tabSize: Int, endColumn: Int): Int {
        val safeEndColumn = endColumn.coerceIn(0, lineText.length)
        if (safeEndColumn <= 0) return 0
        if (lineText.indexOf('\t') < 0) return safeEndColumn

        val safeTabSize = tabSize.coerceAtLeast(1)
        var visualColumns = 0
        for (index in 0 until safeEndColumn) {
            visualColumns += if (lineText[index] == '\t') {
                safeTabSize - (visualColumns % safeTabSize)
            } else {
                1
            }
        }
        return visualColumns
    }

    override fun findWrapSegmentStarts(lineText: String, wrapColumns: Int, tabSize: Int): IntArray {
        val length = lineText.length
        if (length <= 0) return intArrayOf(0)
        val safeWrapColumns = wrapColumns.coerceAtLeast(1)
        val safeTabSize = tabSize.coerceAtLeast(1)

        if (lineText.indexOf('\t') < 0 && length <= safeWrapColumns) {
            return intArrayOf(0)
        }

        var starts = IntArray(8)
        var count = 0
        starts[count++] = 0

        var segmentStart = 0
        var visualColumn = 0
        var index = 0
        while (index < length) {
            val step = if (lineText[index] == '\t') {
                safeTabSize - (visualColumn % safeTabSize)
            } else {
                1
            }

            if (index > segmentStart && visualColumn + step > safeWrapColumns) {
                if (count >= starts.size) {
                    starts = starts.copyOf(starts.size * 2)
                }
                starts[count++] = index
                segmentStart = index
                visualColumn = 0
                continue
            }

            visualColumn += step
            index++

            if (visualColumn >= safeWrapColumns && index < length) {
                if (count >= starts.size) {
                    starts = starts.copyOf(starts.size * 2)
                }
                starts[count++] = index
                segmentStart = index
                visualColumn = 0
            }
        }
        return starts.copyOf(count)
    }

    override fun buildVisualColumnPrefix(lineText: String, tabSize: Int): IntArray {
        val prefix = IntArray(lineText.length + 1)
        if (lineText.isEmpty()) return prefix

        val safeTabSize = tabSize.coerceAtLeast(1)
        var visualColumns = 0
        for (index in lineText.indices) {
            visualColumns += if (lineText[index] == '\t') {
                safeTabSize - (visualColumns % safeTabSize)
            } else {
                1
            }
            prefix[index + 1] = visualColumns
        }
        return prefix
    }

    override fun scanLineWhitespace(lineText: String, tabSize: Int): LineWhitespaceInfo {
        val safeTabSize = tabSize.coerceAtLeast(1)
        var leadingWhitespaceEnd = 0
        var leadingIndentColumns = 0
        var countingIndentColumns = true

        while (leadingWhitespaceEnd < lineText.length) {
            val ch = lineText[leadingWhitespaceEnd]
            if (!ch.isWhitespace()) break
            when (ch) {
                ' ' -> if (countingIndentColumns) {
                    leadingIndentColumns++
                }
                '\t' -> if (countingIndentColumns) {
                    leadingIndentColumns += safeTabSize - (leadingIndentColumns % safeTabSize)
                }
                else -> countingIndentColumns = false
            }
            leadingWhitespaceEnd++
        }

        var trailingWhitespaceStart = lineText.length
        while (trailingWhitespaceStart > 0 && lineText[trailingWhitespaceStart - 1].isWhitespace()) {
            trailingWhitespaceStart--
        }

        val outdentRemoveCount = when (lineText.firstOrNull()) {
            '\t' -> 1
            ' ' -> {
                var spaces = 0
                val max = minOf(safeTabSize, lineText.length)
                while (spaces < max && lineText[spaces] == ' ') {
                    spaces++
                }
                spaces
            }
            else -> 0
        }

        return LineWhitespaceInfo(
            leadingWhitespaceEnd = leadingWhitespaceEnd,
            leadingIndentColumns = leadingIndentColumns,
            trailingWhitespaceStart = trailingWhitespaceStart,
            outdentRemoveCount = outdentRemoveCount
        )
    }
}

private fun encodeWhitespaceMarker(column: Int, isTab: Boolean): Int = (column shl 1) or if (isTab) WHITESPACE_MARKER_TAB_FLAG else 0

private fun Char.isRenderableWhitespace(): Boolean = this == ' ' || this == '\t'

private enum class SignatureHelpContextKind {
    CallParen,
    ControlParen,
    OtherParen,
    TrailingLambda,
    OtherBrace
}

private data class SignatureHelpDelimiter(
    val kind: SignatureHelpContextKind
)

private data class KotlinOpenBracketRecord(
    val offset: Int,
    val bracket: Char,
    val depth: Int
)

private data class KotlinGuideOpenBracketRecord(
    val line: Int,
    val column: Int,
    val bracket: Char,
    val depth: Int
)

private data class KotlinSnapshotOpenBracketRecord(
    val offset: Int,
    val line: Int,
    val column: Int,
    val bracket: Char,
    val depth: Int
)

private enum class SignatureHelpParenKind {
    Call,
    Control,
    Other
}

private sealed interface SignatureHelpScanToken {
    data class Identifier(val text: String) : SignatureHelpScanToken
    data class Symbol(val value: Char) : SignatureHelpScanToken
    data class ParenClose(val kind: SignatureHelpParenKind) : SignatureHelpScanToken
}

private fun findForwardMatchingBracket(
    text: String,
    openOffset: Int,
    openChar: Char
): BracketPairMatchResult? {
    val closeChar = matchingCloseBracket(openChar) ?: return null
    var depth = 0
    for (offset in openOffset until text.length) {
        when (text[offset]) {
            openChar -> depth++
            closeChar -> {
                depth--
                if (depth == 0) {
                    return BracketPairMatchResult(openOffset = openOffset, closeOffset = offset)
                }
            }
        }
    }
    return null
}

private fun findBackwardMatchingBracket(
    text: String,
    closeOffset: Int,
    closeChar: Char
): BracketPairMatchResult? {
    val openChar = matchingOpenBracket(closeChar) ?: return null
    var depth = 0
    for (offset in closeOffset downTo 0) {
        when (text[offset]) {
            closeChar -> depth++
            openChar -> {
                depth--
                if (depth == 0) {
                    return BracketPairMatchResult(openOffset = offset, closeOffset = closeOffset)
                }
            }
        }
    }
    return null
}

private fun Char.isOpenBracket(): Boolean = this == '(' || this == '[' || this == '{'

private fun Char.isCloseBracket(): Boolean = this == ')' || this == ']' || this == '}'

private fun matchingCloseBracket(ch: Char): Char? = when (ch) {
    '(' -> ')'
    '[' -> ']'
    '{' -> '}'
    else -> null
}

private fun matchingOpenBracket(ch: Char): Char? = when (ch) {
    ')' -> '('
    ']' -> '['
    '}' -> '{'
    else -> null
}

private fun resolveSignatureHelpParenKind(
    tokens: List<SignatureHelpScanToken>
): SignatureHelpContextKind = when {
    endsWithSignatureHelpCallableExpression(tokens) -> SignatureHelpContextKind.CallParen
    endsWithSignatureHelpControlKeyword(tokens) -> SignatureHelpContextKind.ControlParen
    else -> SignatureHelpContextKind.OtherParen
}

private fun endsWithSignatureHelpControlKeyword(
    tokens: List<SignatureHelpScanToken>
): Boolean {
    val lastIdentifier = tokens.lastOrNull() as? SignatureHelpScanToken.Identifier ?: return false
    return lastIdentifier.text in SIGNATURE_HELP_CONTROL_KEYWORDS
}

private fun startsSignatureHelpTrailingLambda(
    tokens: List<SignatureHelpScanToken>
): Boolean {
    val lastToken = tokens.lastOrNull() ?: return false
    return when (lastToken) {
        is SignatureHelpScanToken.ParenClose -> lastToken.kind == SignatureHelpParenKind.Call
        is SignatureHelpScanToken.Identifier,
        is SignatureHelpScanToken.Symbol -> endsWithSignatureHelpCallableExpression(tokens)
    }
}

private fun endsWithSignatureHelpCallableExpression(
    tokens: List<SignatureHelpScanToken>
): Boolean {
    if (tokens.isEmpty()) return false
    val index = skipTrailingSignatureHelpTypeArguments(tokens, tokens.lastIndex)
    val token = tokens.getOrNull(index) ?: return false

    return when (token) {
        is SignatureHelpScanToken.ParenClose -> token.kind == SignatureHelpParenKind.Call
        is SignatureHelpScanToken.Identifier -> {
            if (token.text in SIGNATURE_HELP_NON_CALL_TERMINALS) return false
            val chainStart = findSignatureHelpCallChainStart(tokens, index)
            !isSignatureHelpDeclarationContext(tokens, chainStart)
        }
        is SignatureHelpScanToken.Symbol -> false
    }
}

private fun skipTrailingSignatureHelpTypeArguments(
    tokens: List<SignatureHelpScanToken>,
    startIndex: Int
): Int {
    var index = startIndex
    val closingToken = tokens.getOrNull(index) as? SignatureHelpScanToken.Symbol ?: return index
    if (closingToken.value != '>') return index

    var depth = 0
    while (index >= 0) {
        when (val token = tokens[index]) {
            is SignatureHelpScanToken.Symbol -> when (token.value) {
                '>' -> depth++
                '<' -> {
                    depth--
                    if (depth == 0) {
                        return index - 1
                    }
                }
            }
            else -> Unit
        }
        index--
    }
    return startIndex
}

private fun findSignatureHelpCallChainStart(
    tokens: List<SignatureHelpScanToken>,
    identifierIndex: Int
): Int {
    var chainStart = identifierIndex
    var cursor = identifierIndex - 1

    while (cursor >= 0) {
        val dotToken = tokens.getOrNull(cursor) as? SignatureHelpScanToken.Symbol ?: break
        if (dotToken.value != '.') break
        cursor--
        val safeCallToken = tokens.getOrNull(cursor) as? SignatureHelpScanToken.Symbol
        if (safeCallToken?.value == '?') {
            cursor--
        }
        cursor = skipTrailingSignatureHelpTypeArguments(tokens, cursor)
        if (tokens.getOrNull(cursor) !is SignatureHelpScanToken.Identifier) {
            break
        }
        chainStart = cursor
        cursor--
    }

    return chainStart
}

private fun isSignatureHelpDeclarationContext(
    tokens: List<SignatureHelpScanToken>,
    chainStart: Int
): Boolean {
    val prefix = tokens.getOrNull(chainStart - 1)
    if (prefix is SignatureHelpScanToken.Identifier &&
        prefix.text in SIGNATURE_HELP_DECLARATION_KEYWORDS
    ) {
        return true
    }

    val colon = prefix as? SignatureHelpScanToken.Symbol
    if (colon?.value == ':') {
        val owner = tokens.getOrNull(chainStart - 2) as? SignatureHelpScanToken.Identifier
        if (owner?.text in setOf("class", "interface", "object")) {
            return true
        }
    }

    return false
}

private fun popLastSignatureHelpParen(
    stack: MutableList<SignatureHelpDelimiter>
): SignatureHelpParenKind? {
    for (index in stack.lastIndex downTo 0) {
        when (val kind = stack[index].kind) {
            SignatureHelpContextKind.CallParen -> {
                stack.removeAt(index)
                return SignatureHelpParenKind.Call
            }
            SignatureHelpContextKind.ControlParen -> {
                stack.removeAt(index)
                return SignatureHelpParenKind.Control
            }
            SignatureHelpContextKind.OtherParen -> {
                stack.removeAt(index)
                return SignatureHelpParenKind.Other
            }
            SignatureHelpContextKind.TrailingLambda,
            SignatureHelpContextKind.OtherBrace -> Unit
        }
    }
    return null
}

private fun popLastSignatureHelpBrace(
    stack: MutableList<SignatureHelpDelimiter>
) {
    for (index in stack.lastIndex downTo 0) {
        when (stack[index].kind) {
            SignatureHelpContextKind.TrailingLambda,
            SignatureHelpContextKind.OtherBrace -> {
                stack.removeAt(index)
                return
            }
            SignatureHelpContextKind.CallParen,
            SignatureHelpContextKind.ControlParen,
            SignatureHelpContextKind.OtherParen -> Unit
        }
    }
}

private fun Char.isSignatureHelpIdentifierChar(): Boolean = isLetterOrDigit() || this == '_'
