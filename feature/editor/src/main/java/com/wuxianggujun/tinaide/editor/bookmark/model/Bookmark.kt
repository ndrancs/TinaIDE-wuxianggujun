package com.wuxianggujun.tinaide.editor.bookmark.model

/**
 * 书签（按行标记）
 *
 * 约定：
 * - line 为 0-based（与 CodeEditor/EditorMotionEvent 一致）
 * - filePath 使用绝对路径
 */
data class Bookmark(
    val filePath: String,
    val line: Int,
    val note: String = "",
    val createdAt: Long = System.currentTimeMillis()
)
