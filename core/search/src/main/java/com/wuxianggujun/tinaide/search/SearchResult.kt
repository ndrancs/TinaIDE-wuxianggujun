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
    val file: File,                                   // 匹配的文件
    val lineNumber: Int,                              // 行号（1-based）
    val lineContent: String,                          // 行内容
    val matchStart: Int,                              // 匹配起始位置（行内）
    val matchEnd: Int,                                // 匹配结束位置（行内）
    val contextBefore: List<String> = emptyList(),   // 匹配行之前的上下文
    val contextAfter: List<String> = emptyList(),    // 匹配行之后的上下文
    val isSelected: Boolean = true                    // 是否被选中（用于批量替换）
) : SearchResult {
    /**
     * 生成唯一标识符，用于选择状态管理
     */
    val uniqueKey: String
        get() = "${file.absolutePath}:$lineNumber:$matchStart"
}

