package com.wuxianggujun.tinaide.core.compile

/**
 * 统一的构建诊断解析器（纯 Kotlin，无 Android 依赖）。
 *
 * 目标：
 * - 统一解析 GCC/Clang 常见输出格式
 * - 统一解析部分链接器错误（undefined reference / multiple definition）
 * - 输出统一的 [BuildDiagnostic]，供各构建策略复用
 */
object BuildDiagnosticParser {

    private val gccClangWithColumn = Regex(
        """^(.+?):(\d+):(\d+):\s*(fatal error|error|warning|note):\s*(.+)$"""
    )

    // 兼容没有 column 的输出：file:line: error: message
    private val gccClangNoColumn = Regex(
        """^(.+?):(\d+):\s*(fatal error|error|warning|note):\s*(.+)$"""
    )

    // 典型链接器错误：file: undefined reference to `symbol'
    private val linkerError = Regex(
        """^(.+?):\s*(undefined reference to|multiple definition of)\s*[`'](.+?)[`'].*$"""
    )

    fun parse(output: String): List<BuildDiagnostic> {
        if (output.isBlank()) return emptyList()
        return parseLines(output.lineSequence())
    }

    fun parseLines(lines: Sequence<String>): List<BuildDiagnostic> = lines
        .mapNotNull { parseLine(it.trimEnd()) }
        .toList()

    private fun parseLine(line: String): BuildDiagnostic? {
        if (line.isBlank()) return null

        gccClangWithColumn.matchEntire(line)?.let { match ->
            return BuildDiagnostic(
                file = match.groupValues[1],
                line = match.groupValues[2].toIntOrNull() ?: 0,
                column = match.groupValues[3].toIntOrNull() ?: 0,
                severity = toSeverity(match.groupValues[4]),
                message = match.groupValues[5]
            )
        }

        gccClangNoColumn.matchEntire(line)?.let { match ->
            return BuildDiagnostic(
                file = match.groupValues[1],
                line = match.groupValues[2].toIntOrNull() ?: 0,
                column = 0,
                severity = toSeverity(match.groupValues[3]),
                message = match.groupValues[4]
            )
        }

        linkerError.matchEntire(line)?.let { match ->
            val kind = match.groupValues[2]
            val symbol = match.groupValues[3]
            return BuildDiagnostic(
                file = match.groupValues[1],
                line = 0,
                column = 0,
                severity = BuildDiagnostic.Severity.ERROR,
                message = "$kind '$symbol'"
            )
        }

        return null
    }

    private fun toSeverity(raw: String): BuildDiagnostic.Severity = when (raw.lowercase()) {
        "warning" -> BuildDiagnostic.Severity.WARNING
        "note" -> BuildDiagnostic.Severity.NOTE
        // "fatal error"/"error" 都视为 ERROR
        else -> BuildDiagnostic.Severity.ERROR
    }
}
