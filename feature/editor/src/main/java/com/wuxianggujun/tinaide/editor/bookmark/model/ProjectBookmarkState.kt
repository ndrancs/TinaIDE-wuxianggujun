package com.wuxianggujun.tinaide.editor.bookmark.model

/**
 * 项目级书签持久化结构
 */
data class ProjectBookmarkState(
    val bookmarks: List<Bookmark> = emptyList()
) {
    fun normalized(): ProjectBookmarkState {
        val normalizedBookmarks = bookmarks
            .filter { it.filePath.isNotBlank() && it.line >= 0 }
            .distinctBy { it.filePath to it.line }
            .sortedWith(compareBy<Bookmark>({ it.filePath }, { it.line }))
        return copy(bookmarks = normalizedBookmarks)
    }
}
