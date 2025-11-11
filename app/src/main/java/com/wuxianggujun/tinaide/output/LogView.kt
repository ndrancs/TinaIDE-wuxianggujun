package com.wuxianggujun.tinaide.output

import android.content.Context
import android.util.AttributeSet
import io.github.rosemoe.sora.widget.CodeEditor
import io.github.rosemoe.sora.widget.schemes.EditorColorScheme

/**
 * 日志视图组件
 * 基于 sora-editor 的 CodeEditor，专门用于显示日志
 * 
 * 特性：
 * - 自动识别日志等级（ERROR, WARN, INFO, DEBUG等）
 * - 不同等级显示不同颜色
 * - 只读模式，只能复制
 * - 不显示行号
 * - 高性能
 */
class LogView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : CodeEditor(context, attrs, defStyleAttr) {
    
    init {
        setupLogView()
    }
    
    private fun setupLogView() {
        // 设置日志语言支持
        setEditorLanguage(LogLanguage())
        
        // 只读模式（可复制但不可编辑）
        isEditable = false
        
        // 不显示行号
        isLineNumberEnabled = false
        
        // 禁用自动补全
        isAutoCompletionEnabled = false
        
        // 禁用代码块线
        isBlockLineEnabled = false
        
        // 设置深色主题颜色
        setupDarkColorScheme()
        
        // 设置合适的文字大小
        textSizePx = 36f
        
        // 启用滚动条
        isVerticalScrollBarEnabled = true
        isHorizontalScrollBarEnabled = true
    }
    
    /**
     * 设置深色主题颜色方案
     */
    private fun setupDarkColorScheme() {
        colorScheme = colorScheme.apply {
            // 背景色
            setColor(EditorColorScheme.WHOLE_BACKGROUND, 0xFF1E1E1E.toInt())
            
            // 普通文本
            setColor(EditorColorScheme.TEXT_NORMAL, 0xFFD4D4D4.toInt())
            
            // 日志等级颜色
            setColor(EditorColorScheme.KEYWORD, LogLevel.ERROR.color)        // ERROR/FAIL
            setColor(EditorColorScheme.LITERAL, LogLevel.WARN.color)         // WARN
            setColor(EditorColorScheme.COMMENT, LogLevel.INFO.color)         // INFO
            setColor(EditorColorScheme.OPERATOR, LogLevel.DEBUG.color)       // DEBUG
            setColor(EditorColorScheme.IDENTIFIER_NAME, LogLevel.SUCCESS.color) // SUCCESS
            
            // 选中文本背景
            setColor(EditorColorScheme.SELECTED_TEXT_BACKGROUND, 0xFF264F78.toInt())
            
            // 当前行背景
            setColor(EditorColorScheme.CURRENT_LINE, 0xFF2A2A2A.toInt())
            
            // 滚动条
            setColor(EditorColorScheme.SCROLL_BAR_THUMB, 0xFF424242.toInt())
            setColor(EditorColorScheme.SCROLL_BAR_THUMB_PRESSED, 0xFF616161.toInt())
        }
    }
    
    /**
     * 追加日志
     */
    fun appendLog(text: String) {
        this.text.append(text)
        // 滚动到底部
        post {
            val lineCount = this.text.lineCount
            if (lineCount > 0) {
                setSelection(lineCount, 0)
            }
        }
    }
    
    /**
     * 追加带等级的日志
     */
    fun appendLog(level: LogLevel, message: String) {
        appendLog("[${level.prefix}] $message\n")
    }
    
    /**
     * 清空日志
     */
    fun clearLog() {
        setText("")
    }
    
    /**
     * 获取日志内容
     */
    fun getLogContent(): String {
        return text.toString()
    }
}
