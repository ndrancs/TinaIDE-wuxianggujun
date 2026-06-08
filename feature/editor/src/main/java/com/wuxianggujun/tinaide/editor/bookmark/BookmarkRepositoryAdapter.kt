package com.wuxianggujun.tinaide.editor.bookmark

import com.wuxianggujun.tinaide.core.editor.BookmarkInfo
import com.wuxianggujun.tinaide.core.editor.IBookmarkRepository
import com.wuxianggujun.tinaide.editor.bookmark.model.Bookmark
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/**
 * BookmarkRepository 适配器
 *
 * 将 feature:editor 层的 BookmarkRepository 适配为 core:common 层的 IBookmarkRepository 接口
 *
 * 架构说明：
 * - 内部委托给 BookmarkRepository（feature:editor 层）
 * - 对外暴露 IBookmarkRepository 接口（core:common 层）
 * - 负责类型转换（Bookmark ↔ BookmarkInfo）
 */
class BookmarkRepositoryAdapter(
    private val delegate: BookmarkRepository
) : IBookmarkRepository {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val flowCache = ConcurrentHashMap<String, StateFlow<List<BookmarkInfo>>>()

    override fun bookmarksFlow(projectPath: String): StateFlow<List<BookmarkInfo>> = flowCache.computeIfAbsent(projectPath) {
        delegate.bookmarksFlow(projectPath)
            .map { bookmarks -> bookmarks.map { it.toBookmarkInfo() } }
            .stateIn(scope, SharingStarted.Eagerly, emptyList())
    }

    override suspend fun prefetch(projectPath: String) {
        delegate.prefetch(projectPath)
    }

    override suspend fun toggle(projectPath: String, filePath: String, line: Int): BookmarkInfo? = delegate.toggle(projectPath, filePath, line)?.toBookmarkInfo()

    override suspend fun remove(projectPath: String, filePath: String, line: Int): Boolean = delegate.remove(projectPath, filePath, line)

    override suspend fun updateNote(projectPath: String, filePath: String, line: Int, note: String): Boolean = delegate.updateNote(projectPath, filePath, line, note)

    override suspend fun findNext(projectPath: String, currentFilePath: String, currentLine: Int): BookmarkInfo? = delegate.findNext(projectPath, currentFilePath, currentLine)?.toBookmarkInfo()

    override suspend fun findPrevious(projectPath: String, currentFilePath: String, currentLine: Int): BookmarkInfo? = delegate.findPrevious(projectPath, currentFilePath, currentLine)?.toBookmarkInfo()

    override suspend fun clearAll(projectPath: String) {
        delegate.clearAll(projectPath)
    }

    override suspend fun pruneMissingFiles(projectPath: String): Int = delegate.pruneMissingFiles(projectPath)
}

// ========== 类型转换扩展函数 ==========

/**
 * 将内部 Bookmark 转换为接口 BookmarkInfo
 */
private fun Bookmark.toBookmarkInfo(): BookmarkInfo = BookmarkInfo(
    filePath = filePath,
    line = line,
    note = note,
    createdAt = createdAt
)
