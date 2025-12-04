package com.wuxianggujun.tinaide.lsp.model

/**
 * Native 层 Completion Item
 */
data class CompletionItem(
    val label: String,
    val detail: String,
    val insertText: String,
    val documentation: String,
    val kind: Int,
    val deprecated: Boolean
)

/**
 * Native 层 Completion 结果
 */
data class CompletionResult(
    val items: List<CompletionItem>,
    val isIncomplete: Boolean
)
