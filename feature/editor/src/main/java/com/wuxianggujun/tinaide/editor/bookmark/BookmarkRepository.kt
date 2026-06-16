package com.wuxianggujun.tinaide.editor.bookmark

import com.wuxianggujun.tinaide.editor.bookmark.model.Bookmark
import kotlinx.coroutines.flow.StateFlow

/**
 * 书签仓储（项目级）
 *
 * 对外提供稳定 API，隐藏缓存/持久化细节。
 */
interface BookmarkRepository {
    fun bookmarksFlow(projectPath: String): StateFlow<List<Bookmark>>

    suspend fun prefetch(projectPath: String)

    suspend fun toggle(projectPath: String, filePath: String, line: Int): Bookmark?

    suspend fun remove(projectPath: String, filePath: String, line: Int): Boolean

    suspend fun updateNote(projectPath: String, filePath: String, line: Int, note: String): Boolean

    suspend fun findNext(projectPath: String, currentFilePath: String, currentLine: Int): Bookmark?

    suspend fun findPrevious(projectPath: String, currentFilePath: String, currentLine: Int): Bookmark?

    suspend fun clearAll(projectPath: String)

    suspend fun pruneMissingFiles(projectPath: String): Int
}
