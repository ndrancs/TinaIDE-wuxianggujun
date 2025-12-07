package com.wuxianggujun.tinaide.editor.language.c

import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.util.Log
import com.wuxianggujun.tinaide.treesitter.languages.TSLanguageCpp
import com.wuxianggujun.tinaide.editor.EditorDocumentExtras
import com.wuxianggujun.tinaide.core.lsp.NativeLspRequestBridge
import com.wuxianggujun.tinaide.core.lsp.NativeLspResultCache
import com.wuxianggujun.tinaide.core.lsp.NativeLspDocumentBridge
import com.wuxianggujun.tinaide.lsp.model.CompletionItem as NativeCompletionItem
import com.wuxianggujun.tinaide.lsp.model.CompletionResult
import io.github.rosemoe.sora.editor.ts.LocalsCaptureSpec
import io.github.rosemoe.sora.editor.ts.TsLanguage
import io.github.rosemoe.sora.editor.ts.TsLanguageSpec
import io.github.rosemoe.sora.editor.ts.TsThemeBuilder
import io.github.rosemoe.sora.lang.completion.CompletionItemKind
import io.github.rosemoe.sora.lang.completion.CompletionPublisher
import io.github.rosemoe.sora.lang.completion.SimpleCompletionItem
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
 * Provides Tree-sitter powered syntax highlighting for C sources.
 * Uses TSLanguageCpp parser (C++ is a superset of C) with C-specific queries.
 */
object CTreeSitterLanguageProvider {

    private const val TAG = "CTreeSitterLanguage"
    private const val HIGHLIGHTS = "tree-sitter-queries/c/highlights.scm"
    private const val BLOCKS = "tree-sitter-queries/c/blocks.scm"
    private const val BRACKETS = "tree-sitter-queries/c/brackets.scm"
    private const val LOCALS = "tree-sitter-queries/c/locals.scm"

    private val nativeLoaded = AtomicBoolean(false)
    @Volatile
    private var cachedSources: QuerySources? = null

    fun create(context: Context): TsLanguage {
        ensureNativeLibraries()
        val sources = ensureSources(context.applicationContext)
        val spec = CLanguageSpec(
            highlightScmSource = sources.highlights,
            codeBlocksScmSource = sources.blocks,
            bracketsScmSource = sources.brackets,
            localsScmSource = sources.locals
        )
        return NativeAwareCLanguage(spec) { applyCTheme() }
    }

    private fun ensureNativeLibraries() {
        if (nativeLoaded.compareAndSet(false, true)) {
            try {
                System.loadLibrary("android-tree-sitter")
            } catch (_: UnsatisfiedLinkError) {
                // Already loaded by another language
            }
            // Use C++ parser for C (C++ is superset of C)
            System.loadLibrary("tree-sitter-cpp")
        }
    }

    private fun ensureSources(context: Context): QuerySources {
        return cachedSources ?: synchronized(this) {
            cachedSources ?: QuerySources(
                highlights = readAsset(context, HIGHLIGHTS),
                blocks = readAssetOrEmpty(context, BLOCKS),
                brackets = readAssetOrEmpty(context, BRACKETS),
                locals = readAssetOrEmpty(context, LOCALS)
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

    private fun readAssetOrEmpty(context: Context, path: String): String {
        return try {
            context.assets.open(path).bufferedReader().use { it.readText() }
        } catch (_: IOException) {
            ""
        }
    }

    private data class QuerySources(
        val highlights: String,
        val blocks: String,
        val brackets: String,
        val locals: String
    )
}

private class NativeAwareCLanguage(
    spec: TsLanguageSpec,
    themeDescription: TsThemeBuilder.() -> Unit
) : TsLanguage(spec, tab = true, themeDescription = themeDescription) {

    override fun requireAutoComplete(
        content: ContentReference,
        position: CharPosition,
        publisher: CompletionPublisher,
        extraArguments: Bundle
    ) {
        if (!CNativeCompletionDispatcher.publish(content, position, publisher, extraArguments)) {
            super.requireAutoComplete(content, position, publisher, extraArguments)
        }
    }
}

private class CLanguageSpec(
    highlightScmSource: String,
    codeBlocksScmSource: String,
    bracketsScmSource: String,
    localsScmSource: String
) : TsLanguageSpec(
    TSLanguageCpp.getInstance(),  // Use C++ parser for C
    highlightScmSource,
    codeBlocksScmSource,
    bracketsScmSource,
    localsScmSource,
    CLocalsCaptureSpec
)

private object CLocalsCaptureSpec : LocalsCaptureSpec() {
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

private fun TsThemeBuilder.applyCTheme() {
    textStyle(EditorColorScheme.COMMENT, italic = true) applyTo "comment"
    textStyle(EditorColorScheme.KEYWORD, bold = true) applyTo "keyword"
    makeStyle(EditorColorScheme.LITERAL) applyTo arrayOf(
        "string",
        "string.escape",
        "number",
        "constant",
        "constant.builtin",
        "constant.macro"
    )
    makeStyle(EditorColorScheme.IDENTIFIER_NAME) applyTo arrayOf(
        "type",
        "type.builtin"
    )
    makeStyle(EditorColorScheme.IDENTIFIER_VAR) applyTo arrayOf(
        "variable",
        "variable.parameter",
        "property"
    )
    makeStyle(EditorColorScheme.FUNCTION_NAME) applyTo arrayOf(
        "function",
        "function.macro"
    )
    makeStyle(EditorColorScheme.OPERATOR) applyTo "operator"
    makeStyle(EditorColorScheme.ATTRIBUTE_NAME) applyTo arrayOf(
        "keyword.directive",
        "label"
    )
}

private object CNativeCompletionDispatcher {

    private const val TAG = "CNativeCompletion"

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
        val documentVersion = NativeLspDocumentBridge.currentVersion(filePath) ?: -1
        Log.d(
            TAG,
            "Completion request -> file=$filePath line=${position.line} col=${position.column} key=$key prefix='${completionContext.prefix}'"
        )

        val caretIndex = position.column.coerceIn(0, completionContext.lineText.length)
        val allowEmptyPrefix = hasMemberAccessTrigger(completionContext.lineText, caretIndex)
        if (completionContext.replacementLength == 0 && !allowEmptyPrefix) {
            Log.d(TAG, "Empty prefix, skip completion for key=$key")
            return false
        }
        val scopeSignature = if (allowEmptyPrefix) "member-access" else ""

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
                it.toCompletionItem(prefixLength)
            }

            try {
                publisher.checkCancelled()
            } catch (cancelled: CancellationException) {
                Log.d(TAG, "Publisher cancelled before completion applied for key=$key$marker")
                return@deliver false
            }

            val preview = items.take(5).joinToString { item -> item.label.toString() }
            Log.d(
                TAG,
                "Completion result$marker -> file=$filePath line=${position.line} col=${position.column} items=${items.size} preview=$preview"
            )
            publisher.addItems(items)
            publisher.updateList(true)
            return@deliver true
        }

        var contextCache = NativeLspResultCache.getCompletion(
            filePath = filePath,
            line = position.line,
            identifierStart = identifierStart,
            identifierSnapshot = identifierPrefixSnapshot,
            scopeSignature = scopeSignature,
            documentVersion = documentVersion
        )
        val cachedResult = contextCache
        if (cachedResult != null) {
            val delivered = deliverResult(cachedResult, " [cache]", false, true)
            if (delivered) {
                return true
            }
            Log.d(TAG, "Cache miss after prefix filter for key=$key, requesting fresh completion")
        }

        fun deliverFallback(marker: String): Boolean {
            contextCache?.let {
                if (deliverResult(it, marker, false, false)) {
                    return true
                }
            }
            val latest = NativeLspResultCache.getLastCompletion(filePath)
            if (latest != null && deliverResult(latest, "$marker[last]", false, false)) {
                return true
            }
            return false
        }

        NativeLspRequestBridge.requestCompletion(
            filePath = filePath,
            line = position.line,
            column = position.column,
            workDir = workDir
        ) { completionResult ->
            if (completionResult == null) {
                Log.w(TAG, "Completion result empty for key=$key, trying fallback")
                if (!deliverFallback(" [fallback]")) {
                    Log.w(TAG, "Fallback completion also unavailable for key=$key")
                    publisher.updateList(false)
                }
                return@requestCompletion
            }
            if (completionResult.items.isNotEmpty()) {
                NativeLspResultCache.putCompletion(
                    filePath = filePath,
                    line = position.line,
                    identifierStart = identifierStart,
                    identifierSnapshot = identifierPrefixSnapshot,
                    scopeSignature = scopeSignature,
                    documentVersion = documentVersion,
                    result = completionResult
                )
            }
            deliverResult(completionResult, "", true, true)
        }
        return true
    }

    private data class CompletionContext(
        val replacementLength: Int,
        val prefix: String,
        val lineText: String,
        val linePrefix: String,
        val filterPrefix: String,
        val identifierStart: Int
    )

    private fun computeCompletionContext(
        content: ContentReference,
        position: CharPosition
    ): CompletionContext {
        val lineText = runCatching { content.getLine(position.line) }.getOrNull()
            ?: return CompletionContext(0, "", "", "", "", position.column)
        val caret = position.column.coerceIn(0, lineText.length)
        var identifierStart = caret
        while (identifierStart > 0 && isIdentifierChar(lineText[identifierStart - 1])) {
            identifierStart--
        }
        val prefix = lineText.substring(identifierStart, caret)
        val linePrefix = lineText.substring(0, identifierStart.coerceIn(0, lineText.length))
        val filterPrefix = prefix.takeIf { it.isNotBlank() } ?: ""
        return CompletionContext(
            replacementLength = caret - identifierStart,
            prefix = prefix,
            lineText = lineText,
            linePrefix = linePrefix,
            filterPrefix = filterPrefix,
            identifierStart = identifierStart
        )
    }

    private fun hasMemberAccessTrigger(lineText: String, caret: Int): Boolean {
        if (caret <= 0 || lineText.isEmpty()) {
            return false
        }
        val prev = lineText[caret - 1]
        return when (prev) {
            '.' -> true
            '>' -> caret >= 2 && lineText[caret - 2] == '-'
            else -> false
        }
    }

    private fun isIdentifierChar(ch: Char): Boolean {
        return ch == '_' || ch.isLetterOrDigit()
    }

    private fun buildKey(filePath: String, position: CharPosition): String {
        return "$filePath:${position.line}:${position.column}"
    }

    private fun NativeCompletionItem.toCompletionItem(prefixLength: Int): SimpleCompletionItem {
        val commit = insertText.ifBlank { label }
        val description = detail
            .takeIf { it.isNotBlank() }
            ?: documentation.takeIf { it.isNotBlank() }
            ?: ""
        val safeLabel = label.ifBlank { commit }
        val mappedKind = mapKind(kind)
        return SimpleCompletionItem(safeLabel, description, prefixLength, commit).apply {
            kind(mappedKind)
            filterText = safeLabel.toString()
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
