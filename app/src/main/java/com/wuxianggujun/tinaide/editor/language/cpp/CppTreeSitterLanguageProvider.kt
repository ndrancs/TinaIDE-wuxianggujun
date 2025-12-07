package com.wuxianggujun.tinaide.editor.language.cpp

import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.util.Log
import com.wuxianggujun.tinaide.treesitter.languages.TSLanguageCpp
import com.wuxianggujun.tinaide.editor.EditorDocumentExtras
import com.wuxianggujun.tinaide.lsp.LspRequestDispatcher
import com.wuxianggujun.tinaide.lsp.LspResultCache
import com.wuxianggujun.tinaide.lsp.LspDocumentSync
import com.wuxianggujun.tinaide.lsp.model.CompletionItem as NativeCompletionItem
import com.wuxianggujun.tinaide.lsp.model.CompletionResult
import io.github.rosemoe.sora.editor.ts.LocalsCaptureSpec
import io.github.rosemoe.sora.editor.ts.TsLanguage
import io.github.rosemoe.sora.editor.ts.TsLanguageSpec
import io.github.rosemoe.sora.editor.ts.TsThemeBuilder
import io.github.rosemoe.sora.lang.completion.CompletionItemKind
import io.github.rosemoe.sora.lang.completion.CompletionPublisher
import io.github.rosemoe.sora.lang.completion.SimpleCompletionItem
import io.github.rosemoe.sora.lang.completion.CompletionItem
import io.github.rosemoe.sora.lang.completion.filterCompletionItems
import io.github.rosemoe.sora.lang.styling.TextStyle.makeStyle
import io.github.rosemoe.sora.lang.styling.textStyle
import io.github.rosemoe.sora.text.CharPosition
import io.github.rosemoe.sora.text.ContentReference
import io.github.rosemoe.sora.widget.schemes.EditorColorScheme
import java.io.File
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException

/**
 * Provides Tree-sitter powered syntax highlighting for C/C++ sources.
 */
object CppTreeSitterLanguageProvider {

    private const val TAG = "CppTreeSitterLanguage"
    private const val HIGHLIGHTS = "tree-sitter-queries/cpp/highlights.scm"
    private const val BLOCKS = "tree-sitter-queries/cpp/blocks.scm"
    private const val BRACKETS = "tree-sitter-queries/cpp/brackets.scm"
    private const val LOCALS = "tree-sitter-queries/cpp/locals.scm"

    private val nativeLoaded = AtomicBoolean(false)
    @Volatile
    private var cachedSources: QuerySources? = null

    fun create(context: Context): TsLanguage {
        ensureNativeLibraries()
        val sources = ensureSources(context.applicationContext)
        val spec = CppLanguageSpec(
            highlightScmSource = sources.highlights,
            codeBlocksScmSource = sources.blocks,
            bracketsScmSource = sources.brackets,
            localsScmSource = sources.locals
        )
        return NativeAwareCppLanguage(spec) { applyCppTheme() }
    }

    private fun ensureNativeLibraries() {
        if (nativeLoaded.compareAndSet(false, true)) {
            // Tree-sitter core and C++ parser are bundled in native_compiler
            try {
                System.loadLibrary("native_compiler")
            } catch (_: UnsatisfiedLinkError) {
                // Already loaded
            }
        }
    }

    private fun ensureSources(context: Context): QuerySources {
        return cachedSources ?: synchronized(this) {
            cachedSources ?: QuerySources(
                highlights = readAsset(context, HIGHLIGHTS),
                blocks = readAsset(context, BLOCKS),
                brackets = readAsset(context, BRACKETS),
                locals = readAsset(context, LOCALS)
            ).also { cachedSources = it }
        }
    }

    private fun readAsset(context: Context, path: String): String {
        return try {
            context.assets.open(path).bufferedReader().use { it.readText() }
        } catch (ioe: IOException) {
            throw IllegalStateException("Missing Tree-sitter asset: $path", ioe)
        }
    }

    private data class QuerySources(
        val highlights: String,
        val blocks: String,
        val brackets: String,
        val locals: String
    )
}

private class NativeAwareCppLanguage(
    spec: TsLanguageSpec,
    themeDescription: TsThemeBuilder.() -> Unit
) : TsLanguage(spec, tab = true, themeDescription = themeDescription) {

    override fun requireAutoComplete(
        content: ContentReference,
        position: CharPosition,
        publisher: CompletionPublisher,
        extraArguments: Bundle
    ) {
        if (!CppNativeCompletionDispatcher.publish(content, position, publisher, extraArguments)) {
            super.requireAutoComplete(content, position, publisher, extraArguments)
        }
    }
}

private class CppLanguageSpec(
    highlightScmSource: String,
    codeBlocksScmSource: String,
    bracketsScmSource: String,
    localsScmSource: String
) : TsLanguageSpec(
    TSLanguageCpp.getInstance(),
    highlightScmSource,
    codeBlocksScmSource,
    bracketsScmSource,
    localsScmSource,
    CppLocalsCaptureSpec
)

private object CppLocalsCaptureSpec : LocalsCaptureSpec() {
    override fun isDefinitionCapture(captureName: String): Boolean {
        return captureName.startsWith("local.definition")
    }

    override fun isReferenceCapture(captureName: String): Boolean {
        return captureName == "local.reference"
    }

    override fun isScopeCapture(captureName: String): Boolean {
        return captureName == "local.scope"
    }

    override fun isMembersScopeCapture(captureName: String): Boolean {
        return captureName == "local.scope.members"
    }
}

private fun TsThemeBuilder.applyCppTheme() {
    textStyle(EditorColorScheme.COMMENT, italic = true) applyTo "comment"
    textStyle(EditorColorScheme.KEYWORD, bold = true) applyTo "keyword"
    makeStyle(EditorColorScheme.LITERAL) applyTo arrayOf(
        "string",
        "number",
        "constant",
        "boolean"
    )
    makeStyle(EditorColorScheme.IDENTIFIER_NAME) applyTo arrayOf(
        "type",
        "type.builtin",
        "namespace",
        "class",
        "struct"
    )
    makeStyle(EditorColorScheme.IDENTIFIER_VAR) applyTo arrayOf(
        "variable",
        "variable.builtin",
        "field",
        "property",
        "constant.macro"
    )
    makeStyle(EditorColorScheme.FUNCTION_NAME) applyTo arrayOf(
        "function",
        "function.method",
        "constructor",
        "destructor"
    )
    makeStyle(EditorColorScheme.OPERATOR) applyTo "operator"
}

private object CppNativeCompletionDispatcher {

    private const val TAG = "CppNativeCompletion"
    private const val DEEP_COMPLETION_TIMEOUT_MS = 3_000L

    fun publish(
        content: ContentReference,
        position: CharPosition,
        publisher: CompletionPublisher,
        extras: Bundle
    ): Boolean {
        val filePath = extras.getString(EditorDocumentExtras.KEY_FILE_PATH) ?: return false
        val workDir = extras.getString(EditorDocumentExtras.KEY_PROJECT_PATH)
            ?: File(filePath).parent
        val completionContext = computeCompletionContext(content, position)
        val prefixLength = completionContext.replacementLength
        val filterPrefix = completionContext.filterPrefix
        val identifierStart = completionContext.identifierStart
        val identifierPrefixSnapshot = completionContext.linePrefix
        val fileUri = Uri.fromFile(File(filePath)).toString()
        val key = buildKey(filePath, position)
        val documentVersion = LspDocumentSync.currentVersion(filePath) ?: -1
        Log.d(
            TAG,
            "Completion request -> file=$filePath line=${position.line} col=${position.column} " +
            "key=$key scopePrefix='${completionContext.scopePrefix}' filterPrefix='${completionContext.filterPrefix}' " +
            "hasTrigger=${completionContext.hasTrigger} replacementLen=${completionContext.replacementLength}"
        )

        // 允许空前缀的情况：有作用域前缀（如 std::）或有触发符（如 . -> ::）
        val allowEmptyPrefix = completionContext.scopePrefix.isNotEmpty() || completionContext.hasTrigger
        if (completionContext.replacementLength == 0 && !allowEmptyPrefix) {
            Log.d(TAG, "Empty prefix and no trigger, skip completion for key=$key")
            return false
        }
        val scopeSignature = when {
            completionContext.scopePrefix.isNotEmpty() -> "scope:${completionContext.scopePrefix}"
            allowEmptyPrefix -> "member-access"
            else -> ""
        }
        val needsExtendedTimeout = completionContext.scopePrefix.isNotEmpty() || allowEmptyPrefix
        val timeoutOverrideMs = if (needsExtendedTimeout) DEEP_COMPLETION_TIMEOUT_MS else null

        val deliverResult: (CompletionResult, String, Boolean, Boolean) -> Boolean = deliver@{ completionResult, marker, clearOnEmpty, enforceFilter ->
            val normalizedPrefix = filterPrefix.lowercase()
            val filteredItems = when {
                !enforceFilter || normalizedPrefix.isEmpty() -> completionResult.items
                else -> completionResult.items.filter { item ->
                    val labelText = item.label.trimStart()
                    val commitText = item.insertText.ifBlank { labelText }.trimStart()
                    labelText.startsWith(normalizedPrefix, ignoreCase = true) ||
                        commitText.startsWith(normalizedPrefix, ignoreCase = true)
                }
            }
            if (filteredItems.isEmpty()) {
                Log.d(TAG, "Filtered completion empty for key=$key$marker prefix='$filterPrefix'")
                if (clearOnEmpty) {
                    publisher.updateList(false)
                }
                return@deliver false
            }
            val items = filteredItems.map {
                it.toCompletionItem(prefixLength, completionContext.scopePrefix)
            }

            try {
                publisher.checkCancelled()
            } catch (cancelled: CancellationException) {
                Log.d(TAG, "Publisher cancelled before completion applied for key=$key$marker")
                return@deliver false
            }

            // 使用 sora-editor 内置的过滤和高亮功能
            val filteredAndScoredItems = filterCompletionItems(content, position, items)
            
            if (filteredAndScoredItems.isEmpty()) {
                Log.d(TAG, "Filtered completion empty after scoring for key=$key$marker")
                if (clearOnEmpty) {
                    publisher.updateList(false)
                }
                return@deliver false
            }

            val preview = filteredAndScoredItems.take(5).joinToString { item: CompletionItem -> item.label.toString() }
            Log.d(
                TAG,
                "Completion result$marker -> file=$filePath line=${position.line} col=${position.column} items=${filteredAndScoredItems.size} preview=$preview"
            )
            publisher.addItems(filteredAndScoredItems)
            publisher.updateList(true)
            return@deliver true
        }

        // 使用 filterPrefix 的第一个字符作为缓存 key 的一部分，避免不同前缀使用相同缓存
        val cachePrefix = filterPrefix.firstOrNull()?.lowercaseChar()?.toString() ?: ""
        val cacheSnapshot = identifierPrefixSnapshot + cachePrefix
        
        var contextCache = LspResultCache.getCompletion(
            filePath = filePath,
            line = position.line,
            identifierStart = identifierStart,
            identifierSnapshot = cacheSnapshot,
            scopeSignature = scopeSignature,
            documentVersion = documentVersion
        )
        val cachedResult = contextCache
        if (cachedResult != null && !cachedResult.isIncomplete) {
            // 只有当结果是完整的时候才使用缓存
            val delivered = deliverResult(cachedResult, " [cache]", false, true)
            if (delivered) {
                return true
            }
            Log.d(TAG, "Cache miss after prefix filter for key=$key, requesting fresh completion")
        }

        fun deliverFallback(marker: String): Boolean {
            // 不使用 fallback，因为缓存的结果可能是针对不同前缀的
            return false
        }

        // 检测触发字符
        val triggerChar = detectTriggerCharacter(completionContext.lineText, completionContext.identifierStart)
        
        LspRequestDispatcher.requestCompletion(
            filePath = filePath,
            line = position.line,
            column = position.column,
            workDir = workDir,
            onResult = { completionResult ->
                if (completionResult == null) {
                    Log.w(TAG, "Completion result empty for key=$key, trying fallback")
                    if (!deliverFallback(" [fallback]")) {
                        Log.w(TAG, "Fallback completion also unavailable for key=$key")
                        publisher.updateList(false)
                    }
                    return@requestCompletion
                }
                // 只缓存完整的结果，不完整的结果不缓存（因为可能缺少某些前缀的补全项）
                if (completionResult.items.isNotEmpty() && !completionResult.isIncomplete) {
                    LspResultCache.putCompletion(
                        filePath = filePath,
                        line = position.line,
                        identifierStart = identifierStart,
                        identifierSnapshot = cacheSnapshot,
                        scopeSignature = scopeSignature,
                        documentVersion = documentVersion,
                        result = completionResult
                    )
                }
                deliverResult(completionResult, "", true, true)
            },
            timeoutMs = timeoutOverrideMs,
            triggerCharacter = triggerChar
        )
        return true
    }

    private data class CompletionContext(
        val replacementLength: Int,
        val scopePrefix: String,
        val lineText: String,
        val linePrefix: String,
        val filterPrefix: String,
        val identifierStart: Int,
        val hasTrigger: Boolean
    )

    private fun computeCompletionContext(
        content: ContentReference,
        position: CharPosition
    ): CompletionContext {
        val lineText = runCatching { content.getLine(position.line) }.getOrNull()
            ?: return CompletionContext(0, "", "", "", "", position.column, false)
        val caret = position.column.coerceIn(0, lineText.length)
        var identifierStart = caret
        while (identifierStart > 0 && isIdentifierChar(lineText[identifierStart - 1])) {
            identifierStart--
        }
        // 检查 identifierStart 位置之前是否有触发符（在标识符开始之前检查）
        val hasTrigger = hasMemberAccessTrigger(lineText, identifierStart)
        val scopeStart = findScopeStart(lineText, identifierStart)
        val scopePrefix = lineText.substring(scopeStart, identifierStart)
        val linePrefix = lineText.substring(0, identifierStart.coerceIn(0, lineText.length))
        val filterPrefix = lineText.substring(identifierStart, caret).takeIf { it.isNotBlank() } ?: ""
        return CompletionContext(
            replacementLength = caret - identifierStart,
            scopePrefix = scopePrefix,
            lineText = lineText,
            linePrefix = linePrefix,
            filterPrefix = filterPrefix,
            identifierStart = identifierStart,
            hasTrigger = hasTrigger
        )
    }
    
    private fun hasMemberAccessTrigger(lineText: String, identifierStart: Int): Boolean {
        if (identifierStart <= 0 || lineText.isEmpty()) {
            return false
        }
        val prev = lineText[identifierStart - 1]
        return when (prev) {
            '.' -> true
            '>' -> identifierStart >= 2 && lineText[identifierStart - 2] == '-'
            ':' -> identifierStart >= 2 && lineText[identifierStart - 2] == ':'
            else -> false
        }
    }
    
    /**
     * 检测触发字符，用于告诉 clangd 这是一个触发字符补全请求
     * clangd 支持的触发字符: . < > : " / *
     */
    private fun detectTriggerCharacter(lineText: String, identifierStart: Int): String? {
        if (identifierStart <= 0 || lineText.isEmpty()) {
            return null
        }
        val prev = lineText[identifierStart - 1]
        return when (prev) {
            '.' -> "."
            '>' -> if (identifierStart >= 2 && lineText[identifierStart - 2] == '-') ">" else null
            ':' -> if (identifierStart >= 2 && lineText[identifierStart - 2] == ':') ":" else null
            '<' -> "<"
            '"' -> "\""
            '/' -> "/"
            '*' -> "*"
            else -> null
        }
    }

    private fun isIdentifierChar(ch: Char): Boolean {
        return ch == '_' || ch == '$' || ch.isLetterOrDigit()
    }

    private fun findScopeStart(lineText: String, identifierStart: Int): Int {
        var scopeCursor = identifierStart
        while (scopeCursor >= 2) {
            val firstColon = scopeCursor - 1
            val secondColon = scopeCursor - 2
            if (lineText[firstColon] == ':' && lineText[secondColon] == ':') {
                var prefixCursor = secondColon
                while (prefixCursor > 0 && isIdentifierChar(lineText[prefixCursor - 1])) {
                    prefixCursor--
                }
                if (prefixCursor == secondColon) {
                    // 遇到 :: 但左侧没有标识符，停止
                    break
                }
                scopeCursor = prefixCursor
            } else {
                break
            }
        }
        return scopeCursor
    }

    private fun buildKey(filePath: String, position: CharPosition): String {
        return "$filePath:${position.line}:${position.column}"
    }

    private fun NativeCompletionItem.toCompletionItem(
        prefixLength: Int,
        scopePrefix: String
    ): SimpleCompletionItem {
        // 去掉 clangd 返回的 label 前导空格，避免 filterCompletionItems 计算匹配索引时崩溃
        // clangd 返回的 label 格式如 " string"、" cout" 等，带有前导空格
        val trimmedLabel = label.trimStart()
        val commit = insertText.ifBlank { trimmedLabel }
        val description = detail
            .takeIf { it.isNotBlank() }
            ?: documentation.takeIf { it.isNotBlank() }
            ?: ""
        val safeLabel = trimmedLabel.ifBlank { commit }
        val mappedKind = mapKind(kind)
        return SimpleCompletionItem(safeLabel, description, prefixLength, commit).apply {
            kind(mappedKind)
            val scopedFilter = scopePrefix + safeLabel.toString()
            filterText = if (scopePrefix.isNotEmpty()) scopedFilter else safeLabel.toString()
            sortText = safeLabel.toString()
            if (deprecated) {
                desc("$description (deprecated)")
            }
        }
    }

    private fun mapKind(kind: Int): CompletionItemKind {
        return when (kind) {
            2 -> CompletionItemKind.Method
            3 -> CompletionItemKind.Function
            4 -> CompletionItemKind.Constructor
            5 -> CompletionItemKind.Field
            6 -> CompletionItemKind.Variable
            7 -> CompletionItemKind.Class
            8 -> CompletionItemKind.Interface
            9 -> CompletionItemKind.Module
            10 -> CompletionItemKind.Property
            11 -> CompletionItemKind.Unit
            12 -> CompletionItemKind.Value
            13 -> CompletionItemKind.Enum
            14 -> CompletionItemKind.Keyword
            15 -> CompletionItemKind.Snippet
            16 -> CompletionItemKind.Color
            17 -> CompletionItemKind.File
            18 -> CompletionItemKind.Reference
            19 -> CompletionItemKind.EnumMember
            20 -> CompletionItemKind.Constant
            21 -> CompletionItemKind.Struct
            22 -> CompletionItemKind.Event
            23 -> CompletionItemKind.Operator
            24 -> CompletionItemKind.TypeParameter
            else -> CompletionItemKind.Identifier
        }
    }

}
