package com.wuxianggujun.tinaide.search

/**
 * 项目级搜索选项
 */
data class ProjectSearchOptions(
    val caseSensitive: Boolean = false,
    val useRegex: Boolean = false,
    val wholeWord: Boolean = false,
    val fileExtensions: Set<String>? = null,
    val maxResults: Int = 1000,
    val maxFileSize: Long = 1024 * 1024,
    val contextLines: Int = 0,
    val includePatterns: List<String> = emptyList(),
    val excludePatterns: List<String> = emptyList()
)
