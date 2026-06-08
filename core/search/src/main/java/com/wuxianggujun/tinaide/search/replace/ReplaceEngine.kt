package com.wuxianggujun.tinaide.search.replace

import com.wuxianggujun.tinaide.search.ProjectSearchResult
import java.io.File
import java.nio.charset.Charset
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 替换引擎
 * 负责生成替换预览和执行替换操作
 */
class ReplaceEngine {

    /**
     * 生成单文件替换预览
     */
    suspend fun generatePreview(
        file: File,
        searchQuery: String,
        replacement: String,
        matches: List<ProjectSearchResult>,
        options: ReplaceOptions
    ): ReplacePreview = withContext(Dispatchers.IO) {
        val originalContent = file.readText(Charset.defaultCharset())
        val lines = originalContent.lines().toMutableList()

        val replacements = mutableListOf<ReplacementItem>()

        // 编译正则表达式（如果使用正则模式）
        val regex = if (options.useRegex) {
            try {
                if (options.caseSensitive) Regex(searchQuery) else Regex(searchQuery, RegexOption.IGNORE_CASE)
            } catch (e: Exception) {
                null
            }
        } else {
            null
        }

        // 按行号分组匹配结果
        val matchesByLine = matches
            .filter { it.file.absolutePath == file.absolutePath }
            .groupBy { it.lineNumber }

        // 处理每一行的替换
        matchesByLine.forEach { (lineNumber, lineMatches) ->
            val lineIndex = lineNumber - 1
            if (lineIndex < 0 || lineIndex >= lines.size) return@forEach

            val originalLine = lines[lineIndex]

            // 按匹配位置倒序排列（从后向前替换，避免索引偏移）
            val sortedMatches = lineMatches.sortedByDescending { it.matchStart }

            sortedMatches.forEach { match ->
                val matchText = if (match.matchStart >= 0 && match.matchEnd <= originalLine.length) {
                    originalLine.substring(match.matchStart, match.matchEnd)
                } else {
                    searchQuery
                }

                val actualReplacement = processReplacement(
                    matchText = matchText,
                    replacement = replacement,
                    options = options,
                    regex = regex,
                    originalLine = originalLine,
                    matchStart = match.matchStart
                )

                replacements.add(
                    ReplacementItem(
                        lineNumber = lineNumber,
                        originalText = matchText,
                        newText = actualReplacement,
                        matchStart = match.matchStart,
                        matchEnd = match.matchEnd
                    )
                )
            }
        }

        // 生成替换后的内容
        val newContent = generateReplacedContent(originalContent, replacements, searchQuery, replacement, options)

        ReplacePreview(
            file = file,
            originalContent = originalContent,
            newContent = newContent,
            replacements = replacements.sortedWith(compareBy({ it.lineNumber }, { it.matchStart }))
        )
    }

    /**
     * 生成替换后的完整内容
     */
    private fun generateReplacedContent(
        originalContent: String,
        replacements: List<ReplacementItem>,
        searchQuery: String,
        replacement: String,
        options: ReplaceOptions
    ): String {
        if (replacements.isEmpty()) return originalContent

        val lines = originalContent.lines().toMutableList()

        // 按行号分组，每行内按位置倒序处理
        val replacementsByLine = replacements
            .filter { it.isSelected }
            .groupBy { it.lineNumber }

        replacementsByLine.forEach { (lineNumber, lineReplacements) ->
            val lineIndex = lineNumber - 1
            if (lineIndex < 0 || lineIndex >= lines.size) return@forEach

            var line = lines[lineIndex]

            // 按位置倒序替换（从后向前）
            lineReplacements.sortedByDescending { it.matchStart }.forEach { rep ->
                if (rep.matchStart >= 0 && rep.matchEnd <= line.length) {
                    line = line.substring(0, rep.matchStart) + rep.newText + line.substring(rep.matchEnd)
                }
            }

            lines[lineIndex] = line
        }

        return lines.joinToString("\n")
    }

    /**
     * 处理替换文本（支持正则捕获组、保留大小写等）
     */
    private fun processReplacement(
        matchText: String,
        replacement: String,
        options: ReplaceOptions,
        regex: Regex? = null,
        originalLine: String = "",
        matchStart: Int = 0
    ): String {
        var result = replacement

        // 处理正则捕获组（如果启用）
        if (options.useRegex && options.useRegexGroups && regex != null) {
            // 在原始行中找到匹配，获取捕获组
            val matchResult = regex.find(originalLine, matchStart)
            if (matchResult != null && matchResult.range.first == matchStart) {
                // 替换 $0 为整个匹配
                result = result.replace("\$0", matchResult.value)

                // 替换 $1, $2, ... $9 为对应的捕获组
                matchResult.groupValues.forEachIndexed { index, groupValue ->
                    if (index > 0) {
                        result = result.replace("\$$index", groupValue)
                    }
                }

                // 支持 ${name} 命名捕获组
                matchResult.groups.forEach { group ->
                    if (group != null) {
                        // Kotlin 的 MatchGroup 不直接支持命名组，但可以通过索引访问
                    }
                }
            } else {
                // 回退：简单替换 $0
                result = result.replace("\$0", matchText)
            }
        }

        // 保留大小写
        if (options.preserveCase && !options.useRegex) {
            result = preserveCase(matchText, result)
        }

        return result
    }

    /**
     * 保留原始文本的大小写模式
     */
    private fun preserveCase(original: String, replacement: String): String {
        if (original.isEmpty() || replacement.isEmpty()) return replacement

        return when {
            // 全大写
            original.all { it.isUpperCase() || !it.isLetter() } -> replacement.uppercase()
            // 全小写
            original.all { it.isLowerCase() || !it.isLetter() } -> replacement.lowercase()
            // 首字母大写
            original.first().isUpperCase() && original.drop(1).all { it.isLowerCase() || !it.isLetter() } ->
                replacement.replaceFirstChar { it.uppercase() }
            // 其他情况保持原样
            else -> replacement
        }
    }

    /**
     * 执行单文件替换
     */
    suspend fun replaceInFile(
        preview: ReplacePreview,
        createBackup: Boolean = true
    ): ReplaceResult = withContext(Dispatchers.IO) {
        try {
            val file = preview.file

            // 创建备份
            val backupPath = if (createBackup) {
                val backupFile = File(file.parent, "${file.name}.bak")
                file.copyTo(backupFile, overwrite = true)
                backupFile.absolutePath
            } else {
                null
            }

            // 写入新内容
            file.writeText(preview.newContent, Charset.defaultCharset())

            ReplaceResult.Success(
                file = file,
                replacedCount = preview.replacementCount,
                backupPath = backupPath
            )
        } catch (e: Exception) {
            ReplaceResult.Failure(
                file = preview.file,
                error = e.message ?: "Unknown error"
            )
        }
    }

    /**
     * 从备份恢复文件
     */
    suspend fun restoreFromBackup(backupPath: String, targetFile: File): Boolean = withContext(Dispatchers.IO) {
        try {
            val backupFile = File(backupPath)
            if (backupFile.exists()) {
                backupFile.copyTo(targetFile, overwrite = true)
                backupFile.delete()
                true
            } else {
                false
            }
        } catch (e: Exception) {
            false
        }
    }
}
