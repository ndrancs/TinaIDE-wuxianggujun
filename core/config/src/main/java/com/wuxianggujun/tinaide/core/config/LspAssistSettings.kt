package com.wuxianggujun.tinaide.core.config

/**
 * LSP 辅助能力设置（对齐 CLion 体验）。
 *
 * 设计目标：
 * - 用 UI 层提示（signature help / inlay hints）替代“把参数名插入代码”
 * - 设置可热更新（StateFlow），对已打开编辑器即时生效
 */
data class LspAssistSettings(
    /** 是否启用 LSP 签名帮助窗口。 */
    val signatureHelpEnabled: Boolean,
    /** 是否启用 LSP Inlay Hints（行内提示）。 */
    val inlayHintsEnabled: Boolean,
    /** 是否启用 LSP Semantic Tokens（语义高亮）。 */
    val semanticTokensEnabled: Boolean,
)
