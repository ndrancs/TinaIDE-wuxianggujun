package com.wuxianggujun.tinaide.core.editorview

import com.wuxianggujun.tinaide.core.textengine.TextBuffer

internal data class ComposingRange(
    val start: Int,
    val end: Int
)

internal data class ImeDeleteRange(
    val start: Int,
    val end: Int
) {
    val isEmpty: Boolean
        get() = start >= end
}

internal data class ImeExtractedTextWindow(
    val startOffset: Int,
    val endOffset: Int,
    val documentLength: Int,
    val textVersion: Long
) {
    val length: Int
        get() = (endOffset - startOffset).coerceAtLeast(0)

    val coversDocument: Boolean
        get() = startOffset <= 0 && endOffset >= documentLength

    fun isCurrent(documentLength: Int, textVersion: Long): Boolean {
        return this.documentLength == documentLength &&
            this.textVersion == textVersion &&
            startOffset in 0..documentLength &&
            endOffset in startOffset..documentLength
    }
}

internal fun extractedTextSelectionOffset(
    documentOffset: Int,
    windowStartOffset: Int,
    windowLength: Int
): Int = (documentOffset - windowStartOffset).coerceIn(0, windowLength.coerceAtLeast(0))

internal fun isFullExtractedTextWindowSelection(
    start: Int,
    end: Int,
    window: ImeExtractedTextWindow?
): Boolean {
    if (window == null || window.coversDocument || window.length <= 0) return false
    return (start == 0 && end == window.length) ||
        (start == window.length && end == 0)
}

internal fun mapImeSelectionToDocument(
    start: Int,
    end: Int,
    documentLength: Int
): Pair<Int, Int> {
    val safeDocumentLength = documentLength.coerceAtLeast(0)
    return start.coerceIn(0, safeDocumentLength) to end.coerceIn(0, safeDocumentLength)
}

internal fun imeDeleteSurroundingCharRange(
    cursorOffset: Int,
    beforeLength: Int,
    afterLength: Int,
    documentLength: Int
): ImeDeleteRange? {
    val safeDocumentLength = documentLength.coerceAtLeast(0)
    val safeCursor = cursorOffset.coerceIn(0, safeDocumentLength)
    val start = (safeCursor - beforeLength.coerceAtLeast(0)).coerceAtLeast(0)
    val end = (safeCursor + afterLength.coerceAtLeast(0)).coerceAtMost(safeDocumentLength)
    return ImeDeleteRange(start, end).takeUnless { it.isEmpty }
}

internal fun imeDeleteSurroundingCodePointRange(
    textBuffer: TextBuffer,
    cursorOffset: Int,
    beforeLength: Int,
    afterLength: Int
): ImeDeleteRange? {
    val safeCursor = cursorOffset.coerceIn(0, textBuffer.length)
    val start = offsetBeforeByCodePoints(
        textBuffer = textBuffer,
        cursorOffset = safeCursor,
        codePointCount = beforeLength.coerceAtLeast(0)
    )
    val end = offsetAfterByCodePoints(
        textBuffer = textBuffer,
        cursorOffset = safeCursor,
        codePointCount = afterLength.coerceAtLeast(0)
    )
    return ImeDeleteRange(start, end).takeUnless { it.isEmpty }
}

internal fun resolveEditRange(
    selectionStart: Int,
    selectionEnd: Int,
    composingRange: ComposingRange?
): Pair<Int, Int> {
    val normalizedSelection = normalizeRange(selectionStart, selectionEnd)
    val composing = composingRange ?: return normalizedSelection
    return normalizeRange(composing.start, composing.end)
}

internal fun normalizeComposingRange(
    start: Int,
    end: Int,
    documentLength: Int
): ComposingRange? {
    val normalized = normalizeRange(start, end)
    if (normalized.first >= normalized.second) return null
    val clampedStart = normalized.first.coerceIn(0, documentLength)
    val clampedEnd = normalized.second.coerceIn(0, documentLength)
    if (clampedStart >= clampedEnd) return null
    return ComposingRange(clampedStart, clampedEnd)
}

internal fun nextComposingRange(
    editStart: Int,
    replacementLength: Int,
    keepComposing: Boolean
): ComposingRange? {
    if (!keepComposing) return null
    val safeLength = replacementLength.coerceAtLeast(0)
    if (safeLength == 0) return null
    return ComposingRange(
        start = editStart.coerceAtLeast(0),
        end = (editStart + safeLength).coerceAtLeast(editStart.coerceAtLeast(0))
    )
}

private fun offsetBeforeByCodePoints(
    textBuffer: TextBuffer,
    cursorOffset: Int,
    codePointCount: Int
): Int {
    var offset = cursorOffset.coerceIn(0, textBuffer.length)
    repeat(codePointCount) {
        if (offset <= 0) return 0
        offset -= 1
        val current = textBuffer.charAt(offset)
        if (
            current?.let(Character::isLowSurrogate) == true &&
            offset > 0 &&
            textBuffer.charAt(offset - 1)?.let(Character::isHighSurrogate) == true
        ) {
            offset -= 1
        }
    }
    return offset
}

private fun offsetAfterByCodePoints(
    textBuffer: TextBuffer,
    cursorOffset: Int,
    codePointCount: Int
): Int {
    var offset = cursorOffset.coerceIn(0, textBuffer.length)
    repeat(codePointCount) {
        if (offset >= textBuffer.length) return textBuffer.length
        val current = textBuffer.charAt(offset)
        offset += 1
        if (
            current?.let(Character::isHighSurrogate) == true &&
            offset < textBuffer.length &&
            textBuffer.charAt(offset)?.let(Character::isLowSurrogate) == true
        ) {
            offset += 1
        }
    }
    return offset
}

private fun normalizeRange(start: Int, end: Int): Pair<Int, Int> = if (start <= end) {
    start to end
} else {
    end to start
}
