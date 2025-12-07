package com.wuxianggujun.tinaide.editor.language.cmake

import android.content.Context
import com.wuxianggujun.tinaide.treesitter.TSLanguageCMake
import io.github.rosemoe.sora.editor.ts.LocalsCaptureSpec
import io.github.rosemoe.sora.editor.ts.TsLanguage
import io.github.rosemoe.sora.editor.ts.TsLanguageSpec
import io.github.rosemoe.sora.editor.ts.TsThemeBuilder
import io.github.rosemoe.sora.lang.styling.TextStyle.makeStyle
import io.github.rosemoe.sora.lang.styling.textStyle
import io.github.rosemoe.sora.widget.schemes.EditorColorScheme
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Provides Tree-sitter powered syntax highlighting for CMake files.
 */
object CMakeTreeSitterLanguageProvider {

    private const val TAG = "CMakeTreeSitterLanguage"
    private const val HIGHLIGHTS = "tree-sitter-queries/cmake/highlights.scm"
    private const val BLOCKS = "tree-sitter-queries/cmake/blocks.scm"
    private const val BRACKETS = "tree-sitter-queries/cmake/brackets.scm"
    private const val LOCALS = "tree-sitter-queries/cmake/locals.scm"

    private val nativeLoaded = AtomicBoolean(false)
    @Volatile
    private var cachedSources: QuerySources? = null

    fun create(context: Context): TsLanguage {
        ensureNativeLibraries()
        val sources = ensureSources(context.applicationContext)
        val spec = CMakeLanguageSpec(
            highlightScmSource = sources.highlights,
            codeBlocksScmSource = sources.blocks,
            bracketsScmSource = sources.brackets,
            localsScmSource = sources.locals
        )
        return TsLanguage(spec, tab = true) { applyCMakeTheme() }
    }

    private fun ensureNativeLibraries() {
        if (nativeLoaded.compareAndSet(false, true)) {
            try {
                System.loadLibrary("android-tree-sitter")
            } catch (_: UnsatisfiedLinkError) {
                // Already loaded by another language
            }
            // CMake parser is bundled in native_compiler
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

private class CMakeLanguageSpec(
    highlightScmSource: String,
    codeBlocksScmSource: String,
    bracketsScmSource: String,
    localsScmSource: String
) : TsLanguageSpec(
    TSLanguageCMake.getInstance(),
    highlightScmSource,
    codeBlocksScmSource,
    bracketsScmSource,
    localsScmSource,
    CMakeLocalsCaptureSpec
)

private object CMakeLocalsCaptureSpec : LocalsCaptureSpec() {
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
        return false
    }
}

private fun TsThemeBuilder.applyCMakeTheme() {
    textStyle(EditorColorScheme.COMMENT, italic = true) applyTo "comment"
    textStyle(EditorColorScheme.KEYWORD, bold = true) applyTo "keyword"
    makeStyle(EditorColorScheme.LITERAL) applyTo arrayOf(
        "string",
        "string.escape",
        "string.special",
        "constant",
        "constant.builtin"
    )
    makeStyle(EditorColorScheme.IDENTIFIER_VAR) applyTo arrayOf(
        "variable",
        "variable.builtin"
    )
    makeStyle(EditorColorScheme.FUNCTION_NAME) applyTo arrayOf(
        "function",
        "function.builtin"
    )
    makeStyle(EditorColorScheme.OPERATOR) applyTo "operator"
}
