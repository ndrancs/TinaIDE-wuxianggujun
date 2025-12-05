package com.wuxianggujun.tinaide.editor.language.cpp

import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.util.Log
import com.itsaky.androidide.treesitter.cpp.TSLanguageCpp
import com.wuxianggujun.tinaide.editor.EditorDocumentExtras
import com.wuxianggujun.tinaide.core.lsp.NativeLspDocumentBridge
import com.wuxianggujun.tinaide.lsp.NativeLspService
import com.wuxianggujun.tinaide.lsp.model.CompletionItem as NativeCompletionItem
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
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.max
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
            try {
                System.loadLibrary("android-tree-sitter")
            } catch (_: UnsatisfiedLinkError) {
                // Already loaded by another language
            }
            System.loadLibrary("tree-sitter-cpp")
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
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val completionJobs = ConcurrentHashMap<String, Job>()

    fun publish(
        content: ContentReference,
        position: CharPosition,
        publisher: CompletionPublisher,
        extras: Bundle
    ): Boolean {
        val filePath = extras.getString(EditorDocumentExtras.KEY_FILE_PATH) ?: return false
        val workDir = extras.getString(EditorDocumentExtras.KEY_PROJECT_PATH)
            ?: File(filePath).parent
        if (!ensureNativeClient(workDir)) {
            Log.w(TAG, "Native client unavailable for completion ($filePath)")
            return false
        }
        val completionContext = computeCompletionContext(content, position)
        val prefixLength = completionContext.replacementLength
        val fileUri = Uri.fromFile(File(filePath)).toString()
        val key = buildKey(filePath, position)
        Log.d(
            TAG,
            "Completion request -> file=$filePath line=${position.line} col=${position.column} key=$key prefix='${completionContext.scopePrefix}'"
        )

        completionJobs[key]?.cancel()
        completionJobs[key] = scope.launch {
            try {
                runCatching {
                    NativeLspDocumentBridge.flushPendingSync(filePath)
                }.onFailure {
                    Log.w(TAG, "Failed to flush pending sync before completion", it)
                }
                val completionResult = NativeLspService.requestCompletionAsync(
                    fileUri = fileUri,
                    line = position.line,
                    character = position.column
                )

                if (completionResult == null) {
                    Log.d(TAG, "Completion result empty for key=$key")
                    return@launch
                }

                val items = completionResult.items.map {
                    it.toCompletionItem(prefixLength, completionContext.scopePrefix)
                }
                if (items.isEmpty()) {
                    Log.d(TAG, "No completion items returned for key=$key")
                    return@launch
                }

                withContext(Dispatchers.Main) {
                    try {
                        publisher.checkCancelled()
                    } catch (cancelled: CancellationException) {
                        Log.d(TAG, "Publisher cancelled before completion applied for key=$key")
                        return@withContext
                    }
                    if (items.isNotEmpty()) {
                        val preview = items.take(5).joinToString { item -> item.label.toString() }
                        Log.d(
                            TAG,
                            "Completion result -> file=$filePath line=${position.line} col=${position.column} items=${items.size} preview=$preview"
                        )
                    }
                    publisher.addItems(items)
                    publisher.updateList(true)
                }
            } catch (cancelled: CancellationException) {
                Log.d(TAG, "Completion coroutine cancelled for key=$key")
            } catch (t: Throwable) {
                Log.e(TAG, "Completion request failed for key=$key", t)
            } finally {
                completionJobs.remove(key)
            }
        }
        return true
    }

    private data class CompletionContext(
        val replacementLength: Int,
        val scopePrefix: String
    )

    private fun computeCompletionContext(
        content: ContentReference,
        position: CharPosition
    ): CompletionContext {
        val lineText = runCatching { content.getLine(position.line) }.getOrNull() ?: return CompletionContext(0, "")
        val caret = position.column.coerceIn(0, lineText.length)
        var identifierStart = caret
        while (identifierStart > 0 && isIdentifierChar(lineText[identifierStart - 1])) {
            identifierStart--
        }
        val scopeStart = findScopeStart(lineText, identifierStart)
        val scopePrefix = lineText.substring(scopeStart, identifierStart)
        return CompletionContext(
            replacementLength = caret - identifierStart,
            scopePrefix = scopePrefix
        )
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
        val commit = insertText.ifBlank { label }
        val description = detail
            .takeIf { it.isNotBlank() }
            ?: documentation.takeIf { it.isNotBlank() }
            ?: ""
        val safeLabel = label.ifBlank { commit }
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

    private fun ensureNativeClient(workDir: String?): Boolean {
        if (NativeLspService.nativeIsInitialized()) {
            return true
        }
        val resolvedWorkDir = workDir ?: "/"
        val initialized = NativeLspService.initialize(workDir = resolvedWorkDir)
        if (!initialized) {
            Log.w(TAG, "Failed to initialize NativeLspService for $resolvedWorkDir")
        }
        return initialized
    }
}
