package com.wuxianggujun.tinaide.plugin.script.api

import java.io.File
import timber.log.Timber

class PluginWorkspaceFileAccess(
    private val projectRootProvider: () -> String?
) {
    companion object {
        const val DEFAULT_FIND_FILES_LIMIT = 200
        const val MAX_FIND_FILES_LIMIT = 1000

        private val skippedFindDirs = setOf(
            ".git",
            ".gradle",
            ".idea",
            ".cxx",
            "build",
            "node_modules",
        )
    }

    fun resolveSafePath(path: String): File? {
        val projectRoot = resolveProjectRoot() ?: return null
        val normalizedPath = normalizeRelativePath(path) ?: return null
        val targetFile = File(projectRoot, normalizedPath)
        val canonicalTarget = runCatching { targetFile.canonicalFile }.getOrNull() ?: return null
        val canonicalRoot = runCatching { projectRoot.canonicalFile }.getOrNull() ?: return null

        if (canonicalTarget == canonicalRoot || canonicalTarget.path.startsWith(canonicalRoot.path + File.separator)) {
            return canonicalTarget
        }

        Timber.w("Path escape attempt blocked: $path")
        return null
    }

    fun findFiles(pattern: String?, maxResults: Int = DEFAULT_FIND_FILES_LIMIT): List<String> {
        val projectRoot = resolveProjectRoot() ?: return emptyList()
        val normalizedPattern = normalizeGlobPattern(pattern) ?: return emptyList()
        val matcher = globToRegex(normalizedPattern)
        val limit = maxResults.coerceIn(1, MAX_FIND_FILES_LIMIT)
        val results = mutableListOf<String>()

        for (file in projectRoot.walkTopDown().onEnter { dir ->
            dir == projectRoot || dir.name !in skippedFindDirs
        }) {
            if (results.size >= limit) break
            if (!file.isFile) continue

            val relativePath = toRelativePath(projectRoot, file) ?: continue
            if (matcher.matches(relativePath)) {
                results += relativePath
            }
        }

        return results.sorted()
    }

    private fun resolveProjectRoot(): File? {
        val projectRoot = projectRootProvider()?.takeIf { it.isNotBlank() } ?: return null
        val rootFile = File(projectRoot)
        return rootFile.takeIf { it.exists() && it.isDirectory }
    }

    private fun normalizeRelativePath(path: String): String? {
        val normalized = path.trim().replace('\\', '/').removePrefix("/")
        if (normalized.isBlank()) return null
        if (!isSafeRelativePath(normalized)) return null
        return normalized
    }

    private fun normalizeGlobPattern(pattern: String?): String? {
        val normalized = pattern
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?.replace('\\', '/')
            ?.removePrefix("/")
            ?: "**/*"
        if (!isSafeRelativePath(normalized)) return null
        return if ('/' in normalized) normalized else "**/$normalized"
    }

    private fun isSafeRelativePath(path: String): Boolean {
        if (path.startsWith("/")) return false
        return path.split('/').none { segment -> segment == ".." }
    }

    private fun toRelativePath(projectRoot: File, file: File): String? {
        val canonicalRoot = runCatching { projectRoot.canonicalFile }.getOrNull() ?: return null
        val canonicalFile = runCatching { file.canonicalFile }.getOrNull() ?: return null
        if (!canonicalFile.path.startsWith(canonicalRoot.path + File.separator)) return null
        return canonicalFile.path
            .removePrefix(canonicalRoot.path + File.separator)
            .replace('\\', '/')
    }

    private fun globToRegex(pattern: String): Regex {
        val builder = StringBuilder("^")
        var index = 0
        while (index < pattern.length) {
            val char = pattern[index]
            when (char) {
                '*' -> {
                    val isDoubleStar = index + 1 < pattern.length && pattern[index + 1] == '*'
                    val isDoubleStarSlash = isDoubleStar &&
                        index + 2 < pattern.length &&
                        pattern[index + 2] == '/'
                    when {
                        isDoubleStarSlash -> {
                            builder.append("(?:.*/)?")
                            index += 3
                        }
                        isDoubleStar -> {
                            builder.append(".*")
                            index += 2
                        }
                        else -> {
                            builder.append("[^/]*")
                            index++
                        }
                    }
                }
                '?' -> {
                    builder.append("[^/]")
                    index++
                }
                '/', '-' -> {
                    builder.append(char)
                    index++
                }
                else -> {
                    if (char in "\\.[]{}()+-^$|") {
                        builder.append('\\')
                    }
                    builder.append(char)
                    index++
                }
            }
        }
        builder.append('$')
        return Regex(builder.toString())
    }
}
