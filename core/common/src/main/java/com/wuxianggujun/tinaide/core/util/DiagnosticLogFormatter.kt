package com.wuxianggujun.tinaide.core.util

/**
 * 统一 native / 构建诊断日志的 `key=value` 输出，便于跨模块搜索与比对。
 */
object DiagnosticLogFormatter {

    fun format(prefix: String, vararg fields: Pair<String, Any?>): String = buildString {
        append(prefix)
        if (fields.isNotEmpty()) {
            append(": ")
            fields.forEachIndexed { index, (key, value) ->
                if (index > 0) append(", ")
                append(key).append('=').append(value ?: "<null>")
            }
        }
    }
}
