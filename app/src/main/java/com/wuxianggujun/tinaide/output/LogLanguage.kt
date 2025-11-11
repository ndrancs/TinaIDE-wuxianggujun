package com.wuxianggujun.tinaide.output

import io.github.rosemoe.sora.lang.EmptyLanguage
import io.github.rosemoe.sora.lang.analysis.AnalyzeManager
import io.github.rosemoe.sora.lang.styling.Span
import io.github.rosemoe.sora.lang.styling.Spans
import io.github.rosemoe.sora.lang.styling.TextStyle
import io.github.rosemoe.sora.text.CharPosition
import io.github.rosemoe.sora.text.ContentReference
import io.github.rosemoe.sora.widget.schemes.EditorColorScheme

/**
 * 日志语言支持
 * 自动识别日志等级并高亮显示
 */
class LogLanguage : EmptyLanguage() {
    
    private val analyzeManager = LogAnalyzeManager()
    
    override fun getAnalyzeManager(): AnalyzeManager {
        return analyzeManager
    }
    
    /**
     * 日志分析器
     */
    private class LogAnalyzeManager : AnalyzeManager {
        
        override fun analyze(
            content: ContentReference,
            colors: io.github.rosemoe.sora.lang.styling.Styles,
            delegate: AnalyzeManager.Delegate
        ) {
            val spans = Spans()
            spans.adjustOnDelete = true
            
            val lineCount = content.lineCount
            
            for (line in 0 until lineCount) {
                val lineText = content.getLine(line).toString()
                
                // 检测日志等级
                val logLevel = LogLevel.detect(lineText)
                if (logLevel != null) {
                    // 为整行设置颜色
                    val span = Span.obtain(0, TextStyle.makeStyle(EditorColorScheme.TEXT_NORMAL))
                    span.setUnderlineColor(logLevel.color)
                    
                    // 查找日志等级关键字的位置
                    val index = lineText.uppercase().indexOf(logLevel.prefix)
                    if (index >= 0) {
                        // 高亮日志等级关键字
                        val start = index
                        val end = index + logLevel.prefix.length
                        
                        // 使用自定义颜色标记日志等级
                        val levelSpan = Span.obtain(start, TextStyle.makeStyle(
                            when (logLevel) {
                                LogLevel.ERROR, LogLevel.FAIL -> EditorColorScheme.KEYWORD
                                LogLevel.WARN -> EditorColorScheme.LITERAL
                                LogLevel.INFO -> EditorColorScheme.COMMENT
                                LogLevel.DEBUG -> EditorColorScheme.OPERATOR
                                LogLevel.VERBOSE -> EditorColorScheme.TEXT_NORMAL
                                LogLevel.SUCCESS -> EditorColorScheme.IDENTIFIER_NAME
                            }
                        ))
                        levelSpan.column = start
                        spans.addSpan(line, levelSpan)
                        
                        // 添加行尾标记
                        val endSpan = Span.obtain(end, TextStyle.makeStyle(EditorColorScheme.TEXT_NORMAL))
                        endSpan.column = end
                        spans.addSpan(line, endSpan)
                    }
                } else {
                    // 普通文本
                    val normalSpan = Span.obtain(0, TextStyle.makeStyle(EditorColorScheme.TEXT_NORMAL))
                    spans.addSpan(line, normalSpan)
                }
            }
            
            colors.spans = spans
        }
        
        override fun reset(content: ContentReference, delegate: AnalyzeManager.Delegate) {
            // 重置时重新分析
        }
        
        override fun insert(
            content: ContentReference,
            start: CharPosition,
            end: CharPosition,
            insertedContent: CharSequence,
            delegate: AnalyzeManager.Delegate
        ) {
            // 插入时重新分析
            val colors = io.github.rosemoe.sora.lang.styling.Styles()
            analyze(content, colors, delegate)
        }
        
        override fun delete(
            content: ContentReference,
            start: CharPosition,
            end: CharPosition,
            deletedContent: CharSequence,
            delegate: AnalyzeManager.Delegate
        ) {
            // 删除时重新分析（虽然是只读模式，但保留接口）
        }
        
        override fun onAddMultilineCommentSpan(
            content: ContentReference,
            colors: io.github.rosemoe.sora.lang.styling.Styles,
            startLine: Int,
            startColumn: Int,
            endLine: Int,
            endColumn: Int
        ) {
            // 不使用多行注释
        }
        
        override fun destroy() {
            // 清理资源
        }
    }
}
