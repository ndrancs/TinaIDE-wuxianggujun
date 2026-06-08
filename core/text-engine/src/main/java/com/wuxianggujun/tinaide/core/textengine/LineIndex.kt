package com.wuxianggujun.tinaide.core.textengine

/**
 * 行号索引：维护每行起始偏移。
 *
 * Android 上优先走 native kernel；本地 JVM 单测或 native 不可用时自动回退到 Kotlin 实现。
 */
class LineIndex : AutoCloseable {
    private val backend: LineIndexBackend = NativeLineIndexBackend.createOrNull() ?: KotlinLineIndexBackend()

    val lineCount: Int
        get() = backend.lineCount

    fun clear() {
        backend.clear()
    }

    fun rebuild(text: String) {
        backend.rebuild(text)
    }

    fun getLineStart(line: Int): Int = backend.getLineStart(line)

    fun getLineEnd(line: Int, textLength: Int): Int = backend.getLineEnd(line, textLength)

    fun offsetToLine(offset: Int): Int = backend.offsetToLine(offset)

    fun positionToOffset(line: Int, column: Int, textLength: Int): Int = backend.positionToOffset(line, column, textLength)

    /**
     * 增量更新索引。
     */
    fun applyChange(startOffset: Int, oldText: String, newText: String) {
        backend.applyChange(startOffset, oldText, newText)
    }

    override fun close() {
        backend.close()
    }

    @Suppress("deprecation")
    protected fun finalize() {
        close()
    }
}

private interface LineIndexBackend : AutoCloseable {
    val lineCount: Int
    fun clear()
    fun rebuild(text: String)
    fun getLineStart(line: Int): Int
    fun getLineEnd(line: Int, textLength: Int): Int
    fun offsetToLine(offset: Int): Int
    fun positionToOffset(line: Int, column: Int, textLength: Int): Int
    fun applyChange(startOffset: Int, oldText: String, newText: String)
}

private class NativeLineIndexBackend private constructor(
    private var nativeHandle: Long
) : LineIndexBackend {
    companion object {
        fun createOrNull(): NativeLineIndexBackend? {
            if (!NativeLineIndexKernel.isAvailable()) return null
            return runCatching {
                NativeLineIndexBackend(NativeLineIndexKernel.nativeCreate())
            }.getOrNull()
        }
    }

    override val lineCount: Int
        get() = NativeLineIndexKernel.nativeGetLineCount(requireHandle())

    override fun clear() {
        NativeLineIndexKernel.nativeClear(requireHandle())
    }

    override fun rebuild(text: String) {
        NativeLineIndexKernel.nativeRebuild(requireHandle(), text)
    }

    override fun getLineStart(line: Int): Int = NativeLineIndexKernel.nativeGetLineStart(requireHandle(), line)

    override fun getLineEnd(line: Int, textLength: Int): Int = NativeLineIndexKernel.nativeGetLineEnd(requireHandle(), line, textLength)

    override fun offsetToLine(offset: Int): Int = NativeLineIndexKernel.nativeOffsetToLine(requireHandle(), offset)

    override fun positionToOffset(line: Int, column: Int, textLength: Int): Int = NativeLineIndexKernel.nativePositionToOffset(requireHandle(), line, column, textLength)

    override fun applyChange(startOffset: Int, oldText: String, newText: String) {
        NativeLineIndexKernel.nativeApplyChange(requireHandle(), startOffset, oldText, newText)
    }

    override fun close() {
        val handle = nativeHandle
        if (handle == 0L) return
        nativeHandle = 0L
        NativeLineIndexKernel.nativeDestroy(handle)
    }

    private fun requireHandle(): Long {
        check(nativeHandle != 0L) { "NativeLineIndexBackend is already closed" }
        return nativeHandle
    }

    @Suppress("deprecation")
    protected fun finalize() {
        close()
    }
}

private class KotlinLineIndexBackend : LineIndexBackend {
    private val lineStarts = mutableListOf(0)
    private val cachedPositions = mutableListOf<CachedPosition>()
    private val maxCacheCount = 64

    private data class CachedPosition(
        var line: Int,
        var offset: Int
    )

    override val lineCount: Int
        get() = lineStarts.size

    override fun clear() {
        lineStarts.clear()
        lineStarts.add(0)
        cachedPositions.clear()
    }

    override fun rebuild(text: String) {
        clear()
        text.forEachIndexed { index, char ->
            if (char == '\n') {
                lineStarts.add(index + 1)
            }
        }
    }

    override fun getLineStart(line: Int): Int {
        require(line in 0 until lineCount) { "Invalid line: $line" }
        return lineStarts[line]
    }

    override fun getLineEnd(line: Int, textLength: Int): Int {
        require(line in 0 until lineCount) { "Invalid line: $line" }
        return if (line + 1 < lineCount) {
            (lineStarts[line + 1] - 1).coerceAtLeast(lineStarts[line])
        } else {
            textLength
        }
    }

    override fun offsetToLine(offset: Int): Int {
        val safeOffset = offset.coerceAtLeast(0)
        val nearest = findNearestCache(safeOffset)
        val line = if (nearest != null) {
            if (nearest.offset <= safeOffset) {
                findLineForward(nearest, safeOffset)
            } else {
                findLineBackward(nearest, safeOffset)
            }
        } else {
            binarySearchLine(safeOffset)
        }.coerceIn(0, lineStarts.lastIndex)
        addCache(line, safeOffset)
        return line
    }

    override fun positionToOffset(line: Int, column: Int, textLength: Int): Int {
        val start = getLineStart(line)
        val end = getLineEnd(line, textLength)
        return (start + column).coerceIn(start, end)
    }

    override fun applyChange(startOffset: Int, oldText: String, newText: String) {
        val startLine = offsetToLine(startOffset)
        val oldNewlineCount = oldText.count { it == '\n' }
        val newNewlineCount = newText.count { it == '\n' }
        val delta = newText.length - oldText.length
        val oldEndOffset = startOffset + oldText.length
        val lineDelta = newNewlineCount - oldNewlineCount

        if (cachedPositions.isNotEmpty()) {
            val iterator = cachedPositions.iterator()
            while (iterator.hasNext()) {
                val cache = iterator.next()
                if (cache.offset in startOffset..oldEndOffset) {
                    iterator.remove()
                    continue
                }
                if (cache.offset > oldEndOffset) {
                    cache.offset += delta
                    cache.line = (cache.line + lineDelta).coerceAtLeast(0)
                }
            }
            cachedPositions.removeAll { it.line !in 0 until lineStarts.size || it.offset < 0 }
        }

        val removeFrom = (startLine + 1).coerceAtMost(lineStarts.size)
        val removeTo = (startLine + 1 + oldNewlineCount).coerceAtMost(lineStarts.size)
        if (removeFrom < removeTo) {
            lineStarts.subList(removeFrom, removeTo).clear()
        }

        // newNewlineCount 已经在上面（@183）算好；这里直接复用，避免再扫一遍。
        // 绝大多数键击 newNewlineCount == 0，直接跳过 list 构造，不再每次都分配一个空 ArrayList。
        if (newNewlineCount > 0) {
            val newLineStarts = ArrayList<Int>(newNewlineCount)
            for (i in newText.indices) {
                if (newText[i] == '\n') {
                    newLineStarts.add(startOffset + i + 1)
                }
            }
            lineStarts.addAll(removeFrom, newLineStarts)
        }

        val shiftFrom = removeFrom + newNewlineCount
        for (i in shiftFrom until lineStarts.size) {
            lineStarts[i] += delta
        }

        if (lineStarts.isEmpty()) {
            lineStarts.add(0)
        }
        cachedPositions.removeAll { it.line !in 0 until lineStarts.size || it.offset < 0 }
        addCache(startLine.coerceIn(0, lineStarts.lastIndex), startOffset.coerceAtLeast(0))
    }

    override fun close() = Unit

    private fun binarySearchLine(offset: Int): Int {
        val index = lineStarts.binarySearch(offset)
        if (index >= 0) return index.coerceAtMost(lineStarts.lastIndex)
        val insertionPoint = -index - 1
        return (insertionPoint - 1).coerceIn(0, lineStarts.lastIndex)
    }

    private fun findNearestCache(offset: Int): CachedPosition? {
        if (cachedPositions.isEmpty()) return null
        var nearestIndex = -1
        var minDistance = Long.MAX_VALUE
        cachedPositions.forEachIndexed { index, cached ->
            val distance = kotlin.math.abs(cached.offset.toLong() - offset.toLong())
            if (distance < minDistance) {
                minDistance = distance
                nearestIndex = index
            }
        }
        if (nearestIndex < 0) return null
        val position = cachedPositions.removeAt(nearestIndex)
        cachedPositions.add(position)
        return position
    }

    private fun findLineForward(start: CachedPosition, targetOffset: Int): Int {
        var line = start.line.coerceIn(0, lineStarts.lastIndex)
        while (line < lineStarts.lastIndex && lineStarts[line + 1] <= targetOffset) {
            line++
        }
        return line
    }

    private fun findLineBackward(start: CachedPosition, targetOffset: Int): Int {
        var line = start.line.coerceIn(0, lineStarts.lastIndex)
        while (line > 0 && lineStarts[line] > targetOffset) {
            line--
        }
        return line
    }

    private fun addCache(line: Int, offset: Int) {
        val existingIndex = cachedPositions.indexOfFirst { it.offset == offset }
        if (existingIndex >= 0) {
            val existing = cachedPositions.removeAt(existingIndex)
            existing.line = line
            cachedPositions.add(existing)
            return
        }
        cachedPositions.add(
            CachedPosition(
                line = line.coerceIn(0, lineStarts.lastIndex),
                offset = offset.coerceAtLeast(0)
            )
        )
        if (cachedPositions.size > maxCacheCount) {
            cachedPositions.removeAt(0)
        }
    }
}
