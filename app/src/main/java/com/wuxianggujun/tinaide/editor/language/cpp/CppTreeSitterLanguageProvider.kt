package com.wuxianggujun.tinaide.editor.language.cpp

import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.util.Log
import com.itsaky.androidide.treesitter.cpp.TSLanguageCpp
import com.wuxianggujun.tinaide.editor.EditorDocumentExtras
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
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.max
import kotlinx.coroutines.runBlocking

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
        val prefixLength = computePrefixLength(content, position)
        val completionResult = runBlocking {
            NativeLspService.requestCompletionAsync(
                fileUri = Uri.fromFile(File(filePath)).toString(),
                line = position.line,
                character = position.column
            )
        } ?: return false
        publisher.checkCancelled()
        val items = completionResult.items.map {
            it.toCompletionItem(prefixLength)
        }
        if (items.isEmpty()) {
            return false
        }
        publisher.addItems(items)
        publisher.updateList(true)
        return true
    }

    private fun computePrefixLength(content: ContentReference, position: CharPosition): Int {
        val lineText = runCatching { content.getLine(position.line) }.getOrNull() ?: return 0
        val caret = position.column.coerceIn(0, lineText.length)
        var cursor = caret - 1
        while (cursor >= 0 && isIdentifierChar(lineText[cursor])) {
            cursor--
        }
        val start = max(0, cursor + 1)
        return caret - start
    }

    private fun isIdentifierChar(ch: Char): Boolean {
        return ch == '_' || ch == '$' || ch.isLetterOrDigit()
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
