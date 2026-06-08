package com.wuxianggujun.tinaide.core.editorview

internal data class ComposingRange(
    val start: Int,
    val end: Int
)

internal fun mapImeSelectionToDocument(
    start: Int,
    end: Int,
    documentLength: Int
): Pair<Int, Int> {
    val safeDocumentLength = documentLength.coerceAtLeast(0)
    return start.coerceIn(0, safeDocumentLength) to end.coerceIn(0, safeDocumentLength)
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

private fun normalizeRange(start: Int, end: Int): Pair<Int, Int> = if (start <= end) {
    start to end
} else {
    end to start
}
