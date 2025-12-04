package com.wuxianggujun.tinaide.lsp.model

/**
 * Native 层 Hover 结果
 */
data class HoverResult(
    val content: String,
    val startLine: Int,
    val startCharacter: Int,
    val endLine: Int,
    val endCharacter: Int
)
