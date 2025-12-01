package com.wuxianggujun.tinaide.core.editor

import android.os.Bundle
import android.util.Log
import io.github.rosemoe.sora.lang.Language
import io.github.rosemoe.sora.lang.analysis.AnalyzeManager
import io.github.rosemoe.sora.lang.analysis.StyleReceiver
import io.github.rosemoe.sora.lang.completion.CompletionPublisher
import io.github.rosemoe.sora.lang.format.Formatter
import io.github.rosemoe.sora.lang.smartEnter.NewlineHandler
import io.github.rosemoe.sora.lang.styling.MappedSpans
import io.github.rosemoe.sora.lang.styling.Span
import io.github.rosemoe.sora.lang.styling.Styles
import io.github.rosemoe.sora.lang.styling.TextStyle
import io.github.rosemoe.sora.text.CharPosition
import io.github.rosemoe.sora.text.Content
import io.github.rosemoe.sora.text.ContentReference
import io.github.rosemoe.sora.widget.SymbolPairMatch
import io.github.rosemoe.sora.widget.schemes.EditorColorScheme
import com.wuxianggujun.tinaide.core.nativebridge.NativeCompiler
import com.wuxianggujun.tinaide.core.nativebridge.NativeLoader
import com.wuxianggujun.tinaide.core.nativebridge.SysrootInstaller
import kotlinx.coroutines.*
import java.io.File

/**
 * 基于 libclang 的 C/C++ 语言支持
 * 提供语义级语法高亮
 */
class ClangLanguage(
    private val sysroot: String,
    private val filePath: String,
    private val target: String,
    private val includeDirs: List<String> = emptyList()
) : Language {

    companion object {
        private const val TAG = "ClangLanguage"
        
        // C/C++ 符号对
        private val SYMBOL_PAIRS = SymbolPairMatch().apply {
            putPair('{', SymbolPairMatch.SymbolPair("{", "}"))
            putPair('(', SymbolPairMatch.SymbolPair("(", ")"))
            putPair('[', SymbolPairMatch.SymbolPair("[", "]"))
            putPair('"', SymbolPairMatch.SymbolPair("\"", "\""))
            putPair('\'', SymbolPairMatch.SymbolPair("'", "'"))
            putPair('<', SymbolPairMatch.SymbolPair("<", ">"))
        }
        
        /**
         * 判断文件是否为 C++ 文件
         */
        fun isCppFile(path: String): Boolean {
            val ext = path.substringAfterLast('.', "").lowercase()
            return ext in listOf("cpp", "cc", "cxx", "hpp", "hxx", "h++")
        }
        
        /**
         * 判断文件是否为 C/C++ 文件
         */
        fun isCOrCppFile(path: String): Boolean {
            val ext = path.substringAfterLast('.', "").lowercase()
            return ext in listOf("c", "h", "cpp", "cc", "cxx", "hpp", "hxx", "h++")
        }
    }

    private val analyzeManager = ClangAnalyzeManager()
    private val formatter = EmptyFormatter()
    private val isCxx = isCppFile(filePath)

    override fun getAnalyzeManager(): AnalyzeManager = analyzeManager

    override fun getInterruptionLevel(): Int = Language.INTERRUPTION_LEVEL_STRONG

    override fun requireAutoComplete(
        content: ContentReference,
        position: CharPosition,
        publisher: CompletionPublisher,
        extraArguments: Bundle
    ) {
        // TODO: 后续实现代码补全
    }

    override fun getIndentAdvance(content: ContentReference, line: Int, column: Int): Int {
        // 简单的缩进逻辑：如果行尾是 { 则增加缩进
        if (line < 0) return 0
        val lineContent = content.getLine(line)
        val trimmed = lineContent.toString().trim()
        return if (trimmed.endsWith("{") || trimmed.endsWith("(")) 4 else 0
    }

    override fun useTab(): Boolean = false

    override fun getFormatter(): Formatter = formatter

    override fun getSymbolPairs(): SymbolPairMatch = SYMBOL_PAIRS

    override fun getNewlineHandlers(): Array<NewlineHandler>? = null

    override fun destroy() {
        analyzeManager.destroy()
    }

    /**
     * Token 类型到颜色 ID 的映射
     * 使用更丰富的颜色方案来区分不同的语义类型
     */
    private fun tokenTypeToColorId(type: Int): Int {
        return when (type) {
            TokenType.KEYWORD -> EditorColorScheme.KEYWORD
            TokenType.TYPE -> EditorColorScheme.ATTRIBUTE_NAME  // 类型使用属性名颜色
            TokenType.FUNCTION -> EditorColorScheme.FUNCTION_NAME
            TokenType.VARIABLE -> EditorColorScheme.IDENTIFIER_VAR
            TokenType.PARAMETER -> EditorColorScheme.IDENTIFIER_VAR
            TokenType.MEMBER -> EditorColorScheme.IDENTIFIER_NAME
            TokenType.MACRO -> EditorColorScheme.ANNOTATION
            TokenType.STRING -> EditorColorScheme.LITERAL
            TokenType.NUMBER -> EditorColorScheme.LITERAL
            TokenType.COMMENT -> EditorColorScheme.COMMENT
            TokenType.OPERATOR -> EditorColorScheme.OPERATOR
            TokenType.NAMESPACE -> EditorColorScheme.ATTRIBUTE_VALUE  // 命名空间使用属性值颜色
            TokenType.CLASS -> EditorColorScheme.ATTRIBUTE_NAME  // 类名使用属性名颜色（与类型一致）
            TokenType.ENUM -> EditorColorScheme.ATTRIBUTE_NAME  // 枚举使用属性名颜色
            TokenType.ENUM_MEMBER -> EditorColorScheme.STATIC_SPAN_FOREGROUND  // 枚举成员使用静态成员颜色
            else -> EditorColorScheme.TEXT_NORMAL
        }
    }

    /**
     * Clang 分析管理器
     */
    inner class ClangAnalyzeManager : AnalyzeManager {
        
        private var receiver: StyleReceiver? = null
        private var contentRef: ContentReference? = null
        private var analyzeJob: Job? = null
        private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

        override fun setReceiver(receiver: StyleReceiver?) {
            this.receiver = receiver
        }

        override fun reset(content: ContentReference, extraArguments: Bundle) {
            this.contentRef = content
            rerun()
        }

        override fun insert(start: CharPosition, end: CharPosition, insertedContent: CharSequence) {
            // 延迟重新分析
            scheduleReanalyze()
        }

        override fun delete(start: CharPosition, end: CharPosition, deletedContent: CharSequence) {
            // 延迟重新分析
            scheduleReanalyze()
        }

        override fun rerun() {
            analyzeJob?.cancel()
            analyzeJob = scope.launch {
                analyze()
            }
        }

        override fun destroy() {
            analyzeJob?.cancel()
            scope.cancel()
            receiver = null
            contentRef = null
        }

        private var pendingReanalyze: Job? = null
        
        private fun scheduleReanalyze() {
            pendingReanalyze?.cancel()
            pendingReanalyze = scope.launch {
                delay(300) // 300ms 防抖
                analyze()
            }
        }

        private suspend fun analyze() = withContext(Dispatchers.IO) {
            val content = contentRef ?: return@withContext
            val currentReceiver = receiver ?: return@withContext
            
            try {
                // 确保 Native 库已加载
                NativeLoader.loadIfNeeded()
                
                // 检查 Native 库是否成功加载
                if (!NativeLoader.isLoaded()) {
                    Log.w(TAG, "Native library not loaded, skipping semantic analysis")
                    return@withContext
                }
                
                // 检查文件是否存在
                val file = File(filePath)
                if (!file.exists()) {
                    Log.w(TAG, "File not found: $filePath")
                    return@withContext
                }
                
                // 调用 Native 获取语义 Token
                val json = NativeCompiler.getSemanticTokens(
                    sysroot,
                    filePath,
                    target,
                    isCxx,
                    includeDirs.toTypedArray()
                )
                
                val tokens = SemanticTokenParser.parse(json)
                
                if (tokens.isEmpty()) {
                    Log.d(TAG, "No tokens returned for $filePath")
                    return@withContext
                }
                
                Log.d(TAG, "Got ${tokens.size} tokens for $filePath")
                
                // 构建 Styles
                val styles = buildStyles(content, tokens)
                
                // 发送到 UI 线程
                withContext(Dispatchers.Main) {
                    currentReceiver.setStyles(this@ClangAnalyzeManager, styles)
                }
                
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Error analyzing $filePath", e)
            }
        }

        private fun buildStyles(content: ContentReference, tokens: List<SemanticToken>): Styles {
            val defaultStyle = TextStyle.makeStyle(EditorColorScheme.TEXT_NORMAL)
            val actualLineCount = content.lineCount
            val builderLineCount = if (actualLineCount <= 0) 1 else actualLineCount
            val spansPerLine = ArrayList<MutableList<Pair<Int, Long>>>(builderLineCount)
            for (i in 0 until builderLineCount) {
                spansPerLine.add(mutableListOf(0 to defaultStyle))
            }
            val documentLength = content.length

            if (actualLineCount > 0 && tokens.isNotEmpty()) {
                fun clampOffset(offset: Int): Int = offset.coerceIn(0, documentLength)

                for (token in tokens) {
                    val startOffset = clampOffset(token.offset)
                    val endOffset = clampOffset(token.offset + token.length)
                    if (endOffset <= startOffset) {
                        continue
                    }

                    val startPos = content.getCharPosition(startOffset)
                    val endPos = content.getCharPosition(endOffset)

                    if (startPos.line !in 0 until actualLineCount) {
                        continue
                    }

                    val highlightStyle = TextStyle.makeStyle(tokenTypeToColorId(token.type))
                    val lastLine = endPos.line.coerceIn(startPos.line, actualLineCount - 1)
                    var currentLine = startPos.line

                    while (currentLine <= lastLine) {
                        val lineColumnCount = content.getColumnCount(currentLine).coerceAtLeast(0)
                        val rawStartColumn = if (currentLine == startPos.line) startPos.column else 0
                        val rawEndColumn = when {
                            currentLine < lastLine -> lineColumnCount
                            endPos.line >= actualLineCount -> lineColumnCount
                            else -> endPos.column
                        }

                        val startColumn = rawStartColumn.coerceIn(0, lineColumnCount)
                        val endColumn = rawEndColumn.coerceIn(startColumn, lineColumnCount)

                        if (endColumn > startColumn) {
                            spansPerLine[currentLine].add(startColumn to highlightStyle)
                            spansPerLine[currentLine].add(endColumn to defaultStyle)
                        }

                        if (currentLine == lastLine) {
                            break
                        }
                        currentLine++
                    }
                }
            }

            val builder = MappedSpans.Builder(builderLineCount + 1)
            for (line in 0 until builderLineCount) {
                val normalizedPairs = spansPerLine[line]
                    .sortedBy { it.first }
                    .fold(mutableListOf<Pair<Int, Long>>()) { acc, (column, style) ->
                        if (acc.isEmpty()) {
                            acc.add(column to style)
                        } else {
                            val last = acc.last()
                            when {
                                last.first == column -> acc[acc.lastIndex] = column to style
                                last.second != style -> acc.add(column to style)
                            }
                        }
                        acc
                    }

                val ensuredPairs = if (normalizedPairs.isEmpty() || normalizedPairs.first().first > 0) {
                    mutableListOf(0 to defaultStyle).apply { addAll(normalizedPairs) }
                } else {
                    normalizedPairs
                }

                ensuredPairs.forEach { (column, style) ->
                    builder.add(line, Span.obtain(column, style))
                }
            }

            builder.determine(builderLineCount - 1)
            return Styles(builder.build()).apply { finishBuilding() }
        }
    }

    /**
     * 空格式化器
     */
    class EmptyFormatter : Formatter {
        override fun format(text: Content, cursorRange: io.github.rosemoe.sora.text.TextRange) {}
        override fun formatRegion(text: Content, rangeToFormat: io.github.rosemoe.sora.text.TextRange, cursorRange: io.github.rosemoe.sora.text.TextRange) {}
        override fun setReceiver(receiver: Formatter.FormatResultReceiver?) {}
        override fun isRunning(): Boolean = false
        override fun destroy() {}
    }
}
