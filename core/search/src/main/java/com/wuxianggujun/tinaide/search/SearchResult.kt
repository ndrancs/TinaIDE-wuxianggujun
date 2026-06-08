package com.wuxianggujun.tinaide.search

import java.io.File

sealed interface SearchResult

data class CodeSearchResult(
    val range: SearchRange
) : SearchResult

data class HexSearchResult(
    val offset: Long
) : SearchResult

/**
 * 项目级搜索结果
 */
data class ProjectSearchResult(
    val file: File,
    val lineNumber: Int,
    val lineContent: String,
    val matchStart: Int,
    val matchEnd: Int,
    val contextBefore: List<String> = emptyList(),
    val contextAfter: List<String> = emptyList(),
    val isSelected: Boolean = true
) : SearchResult {
    /**
     * 生成唯一标识符，用于选择状态管理
     */
    val uniqueKey: String
        get() = "${file.absolutePath}:$lineNumber:$matchStart"
}
