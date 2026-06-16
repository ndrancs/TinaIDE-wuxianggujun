package com.wuxianggujun.tinaide.search

import com.wuxianggujun.tinaide.core.lang.CxxFileSupport
import com.wuxianggujun.tinaide.core.lang.ProjectPathFilters
import java.io.File
import java.nio.charset.Charset
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext

/**
 * 项目级搜索引擎
 * 支持跨文件文本搜索，复用 CppProjectScanner 的跳过目录策略
 */
class ProjectSearchEngine(
    private val projectPath: String
) {
    companion object {
        // 二进制文件扩展名
        private val BINARY_EXTENSIONS = setOf(
            "exe", "dll", "so", "dylib", "a", "lib", "o", "obj",
            "class", "jar", "war", "ear", "dex", "apk", "aab",
            "zip", "tar", "gz", "bz2", "xz", "7z", "rar",
            "png", "jpg", "jpeg", "gif", "bmp", "ico", "webp", "svg",
            "mp3", "mp4", "avi", "mkv", "mov", "wav", "flac",
            "pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx",
            "ttf", "otf", "woff", "woff2", "eot",
            "db", "sqlite", "sqlite3"
        )

        // 文本文件扩展名（优先搜索）
        private val TEXT_EXTENSIONS: Set<String> =
            CxxFileSupport.editorRelatedExtensions + setOf(
                "java", "kt", "kts", "scala", "groovy",
                "js", "ts", "jsx", "tsx", "mjs", "cjs",
                "py", "rb", "php", "go", "rs", "swift",
                "html", "htm", "css", "scss", "sass", "less",
                "xml", "json", "yaml", "yml", "toml", "ini", "conf", "cfg",
                "md", "txt", "rst", "adoc",
                "sh", "bash", "zsh", "fish", "ps1", "bat", "cmd",
                "sql", "graphql", "proto",
                "cmake", "make", "makefile", "gradle"
            )
    }

    /**
     * 执行搜索，返回 Flow 以支持流式结果
     */
    fun searchFlow(
        query: String,
        options: ProjectSearchOptions = ProjectSearchOptions()
    ): Flow<ProjectSearchResult> = flow {
        if (query.isEmpty()) return@flow

        val projectDir = File(projectPath)
        if (!projectDir.exists() || !projectDir.isDirectory) return@flow

        var resultCount = 0
        val pattern = if (options.useRegex) {
            try {
                if (options.caseSensitive) Regex(query) else Regex(query, RegexOption.IGNORE_CASE)
            } catch (e: Exception) {
                null
            }
        } else {
            null
        }

        projectDir.walkTopDown()
            .onEnter { dir ->
                if (dir == projectDir) return@onEnter true
                !ProjectPathFilters.shouldSkipSearchDirectory(dir.name)
            }
            .forEach { file ->
                currentCoroutineContext().ensureActive()
                if (resultCount >= options.maxResults) return@forEach

                if (!file.isFile) return@forEach
                if (!shouldSearchFile(file, options)) return@forEach

                val results = searchInFile(file, query, pattern, options)
                for (result in results) {
                    if (resultCount >= options.maxResults) break
                    emit(result)
                    resultCount++
                }
            }
    }.flowOn(Dispatchers.IO)

    /**
     * 执行搜索，返回完整结果列表
     */
    suspend fun search(
        query: String,
        options: ProjectSearchOptions = ProjectSearchOptions()
    ): List<ProjectSearchResult> = withContext(Dispatchers.IO) {
        if (query.isEmpty()) return@withContext emptyList()

        val projectDir = File(projectPath)
        if (!projectDir.exists() || !projectDir.isDirectory) {
            return@withContext emptyList()
        }

        val results = mutableListOf<ProjectSearchResult>()
        val pattern = if (options.useRegex) {
            try {
                if (options.caseSensitive) Regex(query) else Regex(query, RegexOption.IGNORE_CASE)
            } catch (e: Exception) {
                null
            }
        } else {
            null
        }

        projectDir.walkTopDown()
            .onEnter { dir ->
                ensureActive()
                if (dir == projectDir) return@onEnter true
                !ProjectPathFilters.shouldSkipSearchDirectory(dir.name)
            }
            .forEach { file ->
                ensureActive()
                if (results.size >= options.maxResults) return@forEach

                if (!file.isFile) return@forEach
                if (!shouldSearchFile(file, options)) return@forEach

                val fileResults = searchInFile(file, query, pattern, options)
                val remaining = options.maxResults - results.size
                results.addAll(fileResults.take(remaining))
            }

        results
    }

    private fun shouldSearchFile(file: File, options: ProjectSearchOptions): Boolean {
        // 检查文件大小
        if (file.length() > options.maxFileSize) return false

        val ext = file.extension.lowercase()

        // 检查是否为二进制文件
        if (BINARY_EXTENSIONS.contains(ext)) return false

        // 如果指定了文件扩展名过滤
        options.fileExtensions?.let { extensions ->
            if (!extensions.contains(ext)) return false
        }

        // 检查包含模式
        if (options.includePatterns.isNotEmpty()) {
            val relativePath = file.absolutePath.removePrefix(projectPath).removePrefix(java.io.File.separator)
            val matchesInclude = options.includePatterns.any { pattern ->
                matchGlobPattern(relativePath, pattern) || matchGlobPattern(file.name, pattern)
            }
            if (!matchesInclude) return false
        }

        // 检查排除模式
        if (options.excludePatterns.isNotEmpty()) {
            val relativePath = file.absolutePath.removePrefix(projectPath).removePrefix(java.io.File.separator)
            val matchesExclude = options.excludePatterns.any { pattern ->
                matchGlobPattern(relativePath, pattern) || matchGlobPattern(file.name, pattern)
            }
            if (matchesExclude) return false
        }

        return true
    }

    /**
     * 简单的 glob 模式匹配
     * 支持 * (匹配任意字符) 和 ? (匹配单个字符)
     */
    private fun matchGlobPattern(text: String, pattern: String): Boolean {
        val regexPattern = pattern
            .replace(".", "\\.")
            .replace("*", ".*")
            .replace("?", ".")
        return try {
            Regex(regexPattern, RegexOption.IGNORE_CASE).matches(text)
        } catch (e: Exception) {
            false
        }
    }

    private fun searchInFile(
        file: File,
        query: String,
        pattern: Regex?,
        options: ProjectSearchOptions
    ): List<ProjectSearchResult> {
        val results = mutableListOf<ProjectSearchResult>()

        try {
            val lines = file.readLines(Charset.defaultCharset())

            lines.forEachIndexed { index, line ->
                val lineNumber = index + 1

                // 计算前导空格的偏移量，用于调整匹配位置
                val trimmedLine = line.trim()
                val leadingSpaces = line.indexOf(trimmedLine.firstOrNull() ?: ' ').coerceAtLeast(0)

                // 收集上下文行
                val contextBefore = if (options.contextLines > 0) {
                    (maxOf(0, index - options.contextLines) until index)
                        .map { lines[it].trim() }
                } else {
                    emptyList()
                }

                val contextAfter = if (options.contextLines > 0) {
                    ((index + 1)..minOf(lines.lastIndex, index + options.contextLines))
                        .map { lines[it].trim() }
                } else {
                    emptyList()
                }

                if (pattern != null) {
                    // 正则搜索
                    pattern.findAll(line).forEach { match ->
                        val matchText = match.value
                        // 全词匹配检查
                        if (!options.wholeWord || isWholeWord(line, match.range.first, match.range.last + 1)) {
                            results.add(
                                ProjectSearchResult(
                                    file = file,
                                    lineNumber = lineNumber,
                                    lineContent = trimmedLine,
                                    matchStart = match.range.first - leadingSpaces,
                                    matchEnd = match.range.last + 1 - leadingSpaces,
                                    contextBefore = contextBefore,
                                    contextAfter = contextAfter
                                )
                            )
                        }
                    }
                } else {
                    // 普通文本搜索
                    val searchLine = if (options.caseSensitive) line else line.lowercase()
                    val searchQuery = if (options.caseSensitive) query else query.lowercase()

                    var startIndex = 0
                    while (true) {
                        val foundIndex = searchLine.indexOf(searchQuery, startIndex)
                        if (foundIndex < 0) break

                        // 全词匹配检查
                        if (!options.wholeWord || isWholeWord(line, foundIndex, foundIndex + query.length)) {
                            results.add(
                                ProjectSearchResult(
                                    file = file,
                                    lineNumber = lineNumber,
                                    lineContent = trimmedLine,
                                    matchStart = foundIndex - leadingSpaces,
                                    matchEnd = foundIndex + query.length - leadingSpaces,
                                    contextBefore = contextBefore,
                                    contextAfter = contextAfter
                                )
                            )
                        }
                        startIndex = foundIndex + 1
                    }
                }
            }
        } catch (e: Exception) {
            // 忽略无法读取的文件（可能是二进制或编码问题）
        }

        return results
    }

    /**
     * 检查匹配是否为完整单词（边界检测）
     */
    private fun isWholeWord(line: String, start: Int, end: Int): Boolean {
        // 检查前边界
        if (start > 0) {
            val charBefore = line[start - 1]
            if (charBefore.isLetterOrDigit() || charBefore == '_') {
                return false
            }
        }
        // 检查后边界
        if (end < line.length) {
            val charAfter = line[end]
            if (charAfter.isLetterOrDigit() || charAfter == '_') {
                return false
            }
        }
        return true
    }

    /**
     * 获取搜索结果的文件分组
     */
    fun groupByFile(results: List<ProjectSearchResult>): Map<File, List<ProjectSearchResult>> = results.groupBy { it.file }
}
