package com.wuxianggujun.tinaide.core.lsp

/**
 * 统一管理 IDE 当前选用的 LSP 管线。
 *
 * Stage3 起直接切换为 Native-only，不再保留 Legacy Java 实现。
 * 若未来需要灰度，可重新扩展此处逻辑，但当前始终返回 true。
 */
object LspConfig {

    /**
     * Stage3 起默认满量程使用 Native LSP。
     * 不再提供 Legacy 切换，以免意外降级到已删除的 Java 管线。
     */
    const val useNativeClient: Boolean = true
}
