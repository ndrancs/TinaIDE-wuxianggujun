package com.wuxianggujun.tinaide.core.treesitter

import android.content.Context
import com.itsaky.androidide.treesitter.TSLanguage
import com.itsaky.androidide.treesitter.TSParser
import com.itsaky.androidide.treesitter.TSQuery
import com.wuxianggujun.tinaide.core.textengine.TextChange
import java.io.File
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write
import timber.log.Timber

class TreeSitterHighlighter private constructor(
    private val language: TSLanguage,
    private val parser: TSParser,
    private val query: TSQuery,
    private val captureTypeByIndex: Array<HighlightType>
) : SyntaxHighlighter {
    private val lifecycleLock = ReentrantReadWriteLock()
    private val disposed = AtomicBoolean(false)
    private val closeExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "TreeSitterHighlighterDispose").apply { isDaemon = true }
    }
    private val state = IncrementalTreeSitterHighlightState(
        parser = parser,
        query = query,
        captureTypeByIndex = captureTypeByIndex,
        onClosed = {
            runCatching { query.close() }
            runCatching { parser.close() }
        }
    )

    override fun highlight(text: String, visibleRange: IntRange): List<HighlightSpan> {
        if (disposed.get() || text.isEmpty() || visibleRange.isEmpty()) return emptyList()
        return lifecycleLock.read {
            if (disposed.get() || !query.canAccess()) {
                return@read emptyList()
            }

            state.readSnapshot(text)?.let { snapshot ->
                return@read runCatching {
                    snapshot.accessTree { tree ->
                        captureHighlightSpans(
                            query = query,
                            captureTypeByIndex = captureTypeByIndex,
                            rootNode = tree.rootNode,
                            textLength = text.length,
                            visibleRange = visibleRange
                        )
                    }
                }.onFailure { error ->
                    Timber.tag("TreeSitter").d(error, "Snapshot highlight pass failed")
                }.getOrDefault(emptyList())
            }

            val compatibilityParser = runCatching { TSParser.create() }
                .onFailure { error ->
                    Timber.tag("TreeSitter").w(error, "Failed to create compatibility parser")
                }
                .getOrNull() ?: return@read emptyList()

            try {
                compatibilityParser.setLanguage(language)
                val tree = compatibilityParser.parseString(text) ?: return@read emptyList()
                try {
                    captureHighlightSpans(
                        query = query,
                        captureTypeByIndex = captureTypeByIndex,
                        rootNode = tree.rootNode,
                        textLength = text.length,
                        visibleRange = visibleRange
                    )
                } finally {
                    runCatching { tree.close() }
                }
            } catch (error: Throwable) {
                Timber.tag("TreeSitter").d(error, "Compatibility highlight pass failed")
                emptyList()
            } finally {
                runCatching { compatibilityParser.close() }
            }
        }
    }

    override fun openDocument(text: String) {
        if (disposed.get()) return
        lifecycleLock.read {
            if (disposed.get()) return
            state.openDocument(text)
        }
    }

    override fun openDocumentBlocking(text: String) {
        if (disposed.get()) return
        lifecycleLock.read {
            if (disposed.get()) return
            state.openDocumentBlocking(text)
        }
    }

    override fun applyTextChange(change: TextChange) {
        if (disposed.get()) return
        lifecycleLock.read {
            if (disposed.get()) return
            state.applyTextChange(change)
        }
    }

    override fun getLineSegments(line: Int): List<HighlightLineSegment> {
        if (disposed.get()) return emptyList()
        return lifecycleLock.read {
            if (disposed.get()) return@read emptyList()
            state.getLineSegments(line)
        }
    }

    override fun setOnStateUpdated(callback: (() -> Unit)?) {
        if (disposed.get()) return
        lifecycleLock.read {
            if (disposed.get()) return
            state.setOnStateUpdated(callback)
        }
    }

    override fun setViewportHint(firstVisibleLine: Int) {
        if (disposed.get()) return
        lifecycleLock.read {
            if (disposed.get()) return
            state.setViewportHint(firstVisibleLine)
        }
    }

    override fun dispose() {
        if (!disposed.compareAndSet(false, true)) return
        runCatching {
            closeExecutor.execute {
                try {
                    closeState()
                } finally {
                    closeExecutor.shutdown()
                }
            }
        }.onFailure { error ->
            Timber.tag("TreeSitter").d(error, "Queue highlighter dispose failed")
            Thread(::closeState, "TreeSitterHighlighterDisposeFallback").apply { isDaemon = true }.start()
        }
    }

    private fun closeState() {
        lifecycleLock.write {
            state.close()
        }
    }

    companion object {
        fun create(context: Context, file: File?): TreeSitterHighlighter? {
            val languageName = TreeSitterLanguageRegistry.languageNameForFile(file)
            if (languageName == null) {
                Timber.tag("TreeSitter").d("No language mapping for file=%s", file?.name)
                return null
            }
            return create(context, languageName)
        }

        fun create(context: Context, languageName: String): TreeSitterHighlighter? {
            val normalizedLanguageName = languageName.lowercase(Locale.ROOT)
            val queryBundle = TreeSitterQueryLoader.load(context, normalizedLanguageName).also { bundle ->
                if (bundle == null) {
                    Timber.tag("TreeSitter").w(
                        "Missing Tree-sitter queries: language=%s (assets/tree-sitter-queries/%s/highlights.scm)",
                        normalizedLanguageName,
                        normalizedLanguageName
                    )
                }
            } ?: return null
            val language = TreeSitterLanguageRegistry.resolveLanguage(normalizedLanguageName).also { resolved ->
                if (resolved == null) {
                    Timber.tag("TreeSitter").w(
                        "Missing Tree-sitter language binding: language=%s",
                        normalizedLanguageName
                    )
                }
            } ?: return null

            val parser = runCatching { TSParser.create() }
                .onFailure { error ->
                    Timber.tag("TreeSitter").w(error, "Failed to create parser")
                }
                .getOrNull() ?: return null

            var query: TSQuery? = null
            return try {
                parser.setLanguage(language)
                query = TreeSitterQueryCompiler.compileWithRecovery(
                    language = language,
                    queryText = queryBundle.highlights,
                    languageName = normalizedLanguageName,
                    queryName = "highlights"
                ) ?: run {
                    runCatching { parser.close() }
                    return null
                }
                val captureTypeByIndex = buildCaptureTypeLookup(query.captureNames)
                TreeSitterHighlighter(
                    language = language,
                    parser = parser,
                    query = query,
                    captureTypeByIndex = captureTypeByIndex
                )
            } catch (error: Throwable) {
                Timber.tag("TreeSitter").w(
                    error,
                    "Failed to init highlighter for language=%s",
                    normalizedLanguageName
                )
                runCatching { query?.close() }
                runCatching { parser.close() }
                null
            }
        }

        private fun buildCaptureTypeLookup(captureNames: Array<String>): Array<HighlightType> = Array(captureNames.size) { index ->
            classifyCaptureName(captureNames[index])
        }

        internal fun classifyCaptureName(captureName: String): HighlightType {
            val normalized = captureName
                .trim()
                .removePrefix("@")
                .lowercase(Locale.ROOT)
            if (normalized.isEmpty()) return HighlightType.DEFAULT

            val tokens = normalized
                .split('.', '-', '_')
                .filter { it.isNotEmpty() }

            fun hasToken(token: String): Boolean = tokens.any { it == token }

            fun containsToken(token: String): Boolean = hasToken(token) || normalized.contains(token)

            return when {
                // @none / @spell = explicitly no highlight (tree-sitter spell/none markers)
                hasToken("none") || hasToken("spell") -> HighlightType.DEFAULT

                containsToken("comment") ||
                    containsToken("doc") ||
                    containsToken("documentation") -> HighlightType.COMMENT

                containsToken("string") ||
                    containsToken("char") ||
                    containsToken("character") -> HighlightType.STRING

                containsToken("number") ||
                    containsToken("integer") ||
                    containsToken("float") ||
                    containsToken("numeric") -> HighlightType.NUMBER

                // @boolean → CONSTANT (true/false/yes/no are named constants, not keywords)
                containsToken("boolean") -> HighlightType.CONSTANT

                // @constant.builtin / @constant.macro → BUILTIN (NULL, EOF, __LINE__ etc.)
                hasToken("constant") && (hasToken("builtin") || hasToken("macro")) -> HighlightType.BUILTIN

                // @constant → CONSTANT (cmake VERSION/SHARED/CONFIG, Kotlin SCREAMING_CASE, C enum values)
                hasToken("constant") -> HighlightType.CONSTANT

                // @*.builtin that isn't constant → BUILTIN (type.builtin, function.builtin, variable.builtin)
                hasToken("builtin") -> HighlightType.BUILTIN

                containsToken("keyword") ||
                    containsToken("conditional") ||
                    containsToken("repeat") ||
                    containsToken("exception") ||
                    containsToken("preproc") ||
                    containsToken("modifier") ||
                    containsToken("module") -> HighlightType.KEYWORD

                // @function.builtin handled above; remaining function/* → FUNCTION
                containsToken("function") ||
                    containsToken("method") ||
                    containsToken("constructor") ||
                    containsToken("call") -> HighlightType.FUNCTION

                containsToken("type") ||
                    containsToken("class") ||
                    containsToken("struct") ||
                    containsToken("enum") ||
                    containsToken("interface") ||
                    containsToken("namespace") -> HighlightType.TYPE

                containsToken("property") ||
                    containsToken("field") ||
                    containsToken("member") -> HighlightType.PROPERTY

                containsToken("variable") ||
                    containsToken("parameter") -> HighlightType.VARIABLE

                containsToken("operator") -> HighlightType.OPERATOR

                containsToken("punctuation") ||
                    containsToken("delimiter") ||
                    containsToken("bracket") ||
                    containsToken("paren") -> HighlightType.PUNCTUATION

                containsToken("identifier") -> HighlightType.DEFAULT

                else -> HighlightType.DEFAULT
            }
        }
    }
}
