package com.wuxianggujun.tinaide.search

data class SearchOptions(
    val caseSensitive: Boolean = false,
    val useRegex: Boolean = false,
    val wholeWord: Boolean = false
)
