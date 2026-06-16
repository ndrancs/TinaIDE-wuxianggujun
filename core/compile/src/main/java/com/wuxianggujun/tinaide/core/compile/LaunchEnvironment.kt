package com.wuxianggujun.tinaide.core.compile

import com.wuxianggujun.tinaide.core.util.NativeExecutableRunner.shellQuotePosix

/**
 * 统一收口运行时额外环境变量的校验与序列化逻辑。
 */
object LaunchEnvironment {
    private const val PATH_SEPARATOR = ":"
    private val variableNamePattern = Regex("[A-Za-z_][A-Za-z0-9_]*")

    fun sanitized(environment: Map<String, String>): Map<String, String> {
        if (environment.isEmpty()) return emptyMap()
        return environment.entries
            .asSequence()
            .filter { (key, _) -> variableNamePattern.matches(key) }
            .sortedBy { it.key }
            .associate { (key, value) -> key to value }
    }

    fun withPrependedPath(
        environment: Map<String, String>,
        variableName: String,
        paths: Iterable<String>,
    ): Map<String, String> {
        val normalized = sanitized(environment).toMutableMap()
        if (!variableNamePattern.matches(variableName)) {
            return normalized
        }

        val mergedPaths = linkedSetOf<String>()
        paths.forEachPathPart { mergedPaths += it }
        normalized[variableName]?.split(PATH_SEPARATOR)?.forEachPathPart { mergedPaths += it }

        if (mergedPaths.isEmpty()) {
            normalized.remove(variableName)
        } else {
            normalized[variableName] = mergedPaths.joinToString(PATH_SEPARATOR)
        }
        return sanitized(normalized)
    }

    fun buildShellPrefix(environment: Map<String, String>): String {
        val normalized = sanitized(environment)
        if (normalized.isEmpty()) return ""
        return normalized.entries
            .joinToString(separator = " ", postfix = " ") { (key, value) ->
                "$key=${shellQuotePosix(value)}"
            }
    }

    private inline fun Iterable<String>.forEachPathPart(action: (String) -> Unit) {
        forEach { raw ->
            raw.trim()
                .takeIf { it.isNotBlank() }
                ?.let(action)
        }
    }
}
