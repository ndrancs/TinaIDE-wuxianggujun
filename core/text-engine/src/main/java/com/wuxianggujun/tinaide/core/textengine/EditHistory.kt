package com.wuxianggujun.tinaide.core.textengine

sealed interface EditOperation {
    data class Insert(val offset: Int, val text: String) : EditOperation
    data class Delete(val offset: Int, val text: String) : EditOperation

    /**
     * 原子替换。undo 一次恢复原文、redo 一次重放替换——不再需要用户按两下 Ctrl+Z
     * 才能完全撤销一次 find-replace / 格式化 / snippet 替换。
     */
    data class Replace(
        val offset: Int,
        val oldText: String,
        val newText: String
    ) : EditOperation
}

interface EditHistory {
    fun canUndo(): Boolean
    fun canRedo(): Boolean
    fun record(operation: EditOperation)
    fun undo(): EditOperation?
    fun redo(): EditOperation?
    fun clear()
}

class DefaultEditHistory(
    private val maxHistorySize: Int = 1000,
    private val mergeWindowMs: Long = 300L,
    private val nowMs: () -> Long = System::currentTimeMillis
) : EditHistory {

    private val undoStack = ArrayDeque<EditOperation>()
    private val redoStack = ArrayDeque<EditOperation>()

    // 合并状态：仅当 record 之间间隔 <= mergeWindowMs 且位置相邻时，
    // 连续的 Insert/Delete 会被合并成一条；undo/redo/Replace/clear 均会打断。
    private var lastRecordTimeMs: Long = Long.MIN_VALUE
    private var mergeChainActive: Boolean = false

    override fun canUndo(): Boolean = undoStack.isNotEmpty()

    override fun canRedo(): Boolean = redoStack.isNotEmpty()

    override fun record(operation: EditOperation) {
        val now = nowMs()
        redoStack.clear()

        val merged = if (mergeChainActive && (now - lastRecordTimeMs) <= mergeWindowMs) {
            tryMergeWithTop(operation)
        } else {
            false
        }

        if (!merged) {
            undoStack.addLast(operation)
            while (undoStack.size > maxHistorySize) {
                undoStack.removeFirst()
            }
        }

        // Replace 是原子的，本身不参与输入流合并；遇到它就断链。
        mergeChainActive = operation !is EditOperation.Replace
        lastRecordTimeMs = now
    }

    private fun tryMergeWithTop(operation: EditOperation): Boolean {
        val previous = undoStack.lastOrNull() ?: return false
        when (operation) {
            is EditOperation.Insert -> {
                if (previous !is EditOperation.Insert) return false
                // 顺序输入：new.offset == prev.offset + prev.text.length。
                if (operation.offset != previous.offset + previous.text.length) return false
                if (containsMergeBoundary(previous.text) || containsMergeBoundary(operation.text)) return false
                undoStack.removeLast()
                undoStack.addLast(
                    EditOperation.Insert(
                        offset = previous.offset,
                        text = previous.text + operation.text
                    )
                )
                return true
            }

            is EditOperation.Delete -> {
                if (previous !is EditOperation.Delete) return false
                if (containsMergeBoundary(previous.text) || containsMergeBoundary(operation.text)) return false
                // Backspace：new.offset + new.text.length == prev.offset。
                if (operation.offset + operation.text.length == previous.offset) {
                    undoStack.removeLast()
                    undoStack.addLast(
                        EditOperation.Delete(
                            offset = operation.offset,
                            text = operation.text + previous.text
                        )
                    )
                    return true
                }
                // Delete 键：new.offset == prev.offset。
                if (operation.offset == previous.offset) {
                    undoStack.removeLast()
                    undoStack.addLast(
                        EditOperation.Delete(
                            offset = previous.offset,
                            text = previous.text + operation.text
                        )
                    )
                    return true
                }
                return false
            }

            is EditOperation.Replace -> return false
        }
    }

    // 回车 / Tab 等视觉边界上停止合并，对齐 IntelliJ / Sora 的直觉：
    // 敲完一行后的 undo 应该回到上一行末，而不是一口气吞掉整段。
    private fun containsMergeBoundary(text: String): Boolean {
        for (i in text.indices) {
            val ch = text[i]
            if (ch == '\n' || ch == '\r' || ch == '\t') return true
        }
        return false
    }

    override fun undo(): EditOperation? {
        val operation = undoStack.removeLastOrNull() ?: return null
        redoStack.addLast(operation)
        mergeChainActive = false
        return operation
    }

    override fun redo(): EditOperation? {
        val operation = redoStack.removeLastOrNull() ?: return null
        undoStack.addLast(operation)
        mergeChainActive = false
        return operation
    }

    override fun clear() {
        undoStack.clear()
        redoStack.clear()
        mergeChainActive = false
        lastRecordTimeMs = Long.MIN_VALUE
    }
}
