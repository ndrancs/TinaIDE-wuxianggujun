package com.wuxianggujun.tinaide.search.replace

/**
 * 替换选项
 */
data class ReplaceOptions(
    val caseSensitive: Boolean = false,
    val useRegex: Boolean = false,
    val wholeWord: Boolean = false,
    val preserveCase: Boolean = false,
    val useRegexGroups: Boolean = false
)
