package com.wuxianggujun.tinaide.lsp.model

/**
 * 代表 LSP diagnostics 项，与 Native 结构保持一致。
 */
data class DiagnosticItem(
    val startLine: Int,
    val startCharacter: Int,
    val endLine: Int,
    val endCharacter: Int,
    val severity: Int,
    val message: String,
    val source: String?,
    val code: String?
)

enum class DiagnosticSeverity(val protocolValue: Int) {
    Error(1),
    Warning(2),
    Information(3),
    Hint(4);

    companion object {
        fun fromProtocol(value: Int): DiagnosticSeverity = when (value) {
            Error.protocolValue -> Error
            Warning.protocolValue -> Warning
            Information.protocolValue -> Information
            Hint.protocolValue -> Hint
            else -> Warning
        }
    }
}
