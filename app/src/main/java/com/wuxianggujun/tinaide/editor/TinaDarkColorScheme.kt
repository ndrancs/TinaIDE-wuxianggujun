package com.wuxianggujun.tinaide.editor

import io.github.rosemoe.sora.widget.schemes.EditorColorScheme

/**
 * TinaIDE 深色主题颜色方案
 * 使用黑色背景 + 青绿色强调色
 */
class TinaDarkColorScheme : EditorColorScheme(true) {

    override fun applyDefault() {
        super.applyDefault()
        
        // 背景颜色
        setColor(WHOLE_BACKGROUND, 0xFF121212.toInt())           // 主背景 - 深黑色
        setColor(LINE_NUMBER_BACKGROUND, 0xFF1E1E1E.toInt())     // 行号背景 - 稍浅的黑色
        setColor(CURRENT_LINE, 0x20FFFFFF)                        // 当前行高亮 - 半透明白色
        
        // 行号颜色
        setColor(LINE_NUMBER, 0xFF6E6E6E.toInt())                // 行号 - 灰色
        setColor(LINE_NUMBER_CURRENT, 0xFF03DAC6.toInt())        // 当前行号 - 青绿色
        setColor(LINE_DIVIDER, 0xFF2D2D2D.toInt())               // 行分隔线 - 深灰色
        
        // 文本颜色
        setColor(TEXT_NORMAL, 0xFFE0E0E0.toInt())                // 普通文本 - 浅灰白色
        setColor(TEXT_SELECTED, 0)                                // 选中文本颜色（0 表示不改变）
        
        // 选择和光标颜色 - 青绿色
        setColor(SELECTION_INSERT, 0xFF03DAC6.toInt())           // 光标颜色 - 青绿色
        setColor(SELECTION_HANDLE, 0xFF03DAC6.toInt())           // 选择手柄 - 青绿色
        setColor(SELECTED_TEXT_BACKGROUND, 0x4003DAC6)           // 选中文本背景 - 半透明青绿色
        setColor(UNDERLINE, 0xFF03DAC6.toInt())                  // 下划线 - 青绿色
        
        // 代码块线条
        setColor(BLOCK_LINE, 0xFF3D3D3D.toInt())                 // 代码块线 - 深灰色
        setColor(BLOCK_LINE_CURRENT, 0xFF03DAC6.toInt())         // 当前代码块线 - 青绿色
        setColor(SIDE_BLOCK_LINE, 0xFF4D4D4D.toInt())            // 侧边代码块线 - 灰色
        
        // 滚动条颜色
        setColor(SCROLL_BAR_THUMB, 0xFF4D4D4D.toInt())           // 滚动条 - 灰色
        setColor(SCROLL_BAR_THUMB_PRESSED, 0xFF03DAC6.toInt())   // 滚动条按下 - 青绿色
        setColor(SCROLL_BAR_TRACK, 0x00000000)                    // 滚动条轨道 - 透明
        
        // 语法高亮颜色
        setColor(KEYWORD, 0xFF569CD6.toInt())                    // 关键字 - 蓝色
        setColor(COMMENT, 0xFF6A9955.toInt())                    // 注释 - 绿色
        setColor(LITERAL, 0xFFCE9178.toInt())                    // 字面量/字符串 - 橙色
        setColor(OPERATOR, 0xFFD4D4D4.toInt())                   // 运算符 - 浅灰色
        setColor(IDENTIFIER_NAME, 0xFF4EC9B0.toInt())            // 标识符名称 - 青色
        setColor(IDENTIFIER_VAR, 0xFF9CDCFE.toInt())             // 变量标识符 - 浅蓝色
        setColor(FUNCTION_NAME, 0xFFDCDCAA.toInt())              // 函数名 - 黄色
        setColor(ANNOTATION, 0xFF4EC9B0.toInt())                 // 注解 - 青色
        
        // HTML/XML 颜色
        setColor(HTML_TAG, 0xFF569CD6.toInt())                   // HTML 标签 - 蓝色
        setColor(ATTRIBUTE_NAME, 0xFF9CDCFE.toInt())             // 属性名 - 浅蓝色
        setColor(ATTRIBUTE_VALUE, 0xFFCE9178.toInt())            // 属性值 - 橙色
        
        // 自动补全窗口颜色
        setColor(COMPLETION_WND_BACKGROUND, 0xFF252526.toInt())  // 补全窗口背景 - 深灰色
        setColor(COMPLETION_WND_CORNER, 0xFF252526.toInt())      // 补全窗口圆角 - 深灰色
        setColor(COMPLETION_WND_TEXT_PRIMARY, 0xFFE0E0E0.toInt()) // 补全主文本 - 浅灰白色
        setColor(COMPLETION_WND_TEXT_SECONDARY, 0xFF9E9E9E.toInt()) // 补全次要文本 - 灰色
        setColor(COMPLETION_WND_TEXT_MATCHED, 0xFF03DAC6.toInt()) // 补全匹配文本 - 青绿色
        setColor(COMPLETION_WND_ITEM_CURRENT, 0xFF094771.toInt()) // 补全当前项 - 深蓝色
        
        // 匹配文本背景
        setColor(MATCHED_TEXT_BACKGROUND, 0x4003DAC6)            // 匹配文本背景 - 半透明青绿色
        
        // 非打印字符
        setColor(NON_PRINTABLE_CHAR, 0x40FFFFFF)                 // 非打印字符 - 半透明白色
        
        // 问题/诊断颜色
        setColor(PROBLEM_ERROR, 0xAAF44336.toInt())              // 错误 - 红色
        setColor(PROBLEM_WARNING, 0xAAFFC107.toInt())            // 警告 - 黄色
        setColor(PROBLEM_TYPO, 0x6603DAC6)                       // 拼写 - 半透明青绿色
        
        // 高亮分隔符
        setColor(HIGHLIGHTED_DELIMITERS_FOREGROUND, 0xFFFFFFFF.toInt()) // 分隔符前景 - 白色
        setColor(HIGHLIGHTED_DELIMITERS_UNDERLINE, 0xFF03DAC6.toInt())  // 分隔符下划线 - 青绿色
        setColor(HIGHLIGHTED_DELIMITERS_BACKGROUND, 0x2003DAC6)         // 分隔符背景 - 半透明青绿色
        
        // 代码片段背景
        setColor(SNIPPET_BACKGROUND_EDITING, 0xFF3D3D3D.toInt())
        setColor(SNIPPET_BACKGROUND_RELATED, 0xFF2D2D2D.toInt())
        setColor(SNIPPET_BACKGROUND_INACTIVE, 0xFF1E1E1E.toInt())
        
        // 行号面板
        setColor(LINE_NUMBER_PANEL, 0xDD1E1E1E.toInt())
        setColor(LINE_NUMBER_PANEL_TEXT, 0xFFE0E0E0.toInt())
        
        // 内联提示
        setColor(TEXT_INLAY_HINT_FOREGROUND, 0xFF9E9E9E.toInt())
        setColor(TEXT_INLAY_HINT_BACKGROUND, 0xFF2D2D2D.toInt())
        
        // 签名和悬停提示
        setColor(SIGNATURE_TEXT_NORMAL, 0xFFE0E0E0.toInt())
        setColor(SIGNATURE_TEXT_HIGHLIGHTED_PARAMETER, 0xFF03DAC6.toInt())
        setColor(SIGNATURE_BACKGROUND, 0xFF252526.toInt())
        setColor(SIGNATURE_BORDER, 0xFF3D3D3D.toInt())
        setColor(HOVER_TEXT_NORMAL, 0xFFE0E0E0.toInt())
        setColor(HOVER_TEXT_HIGHLIGHTED, 0xFF03DAC6.toInt())
        setColor(HOVER_BACKGROUND, 0xFF252526.toInt())
        setColor(HOVER_BORDER, 0xFF3D3D3D.toInt())
        
        // 诊断提示
        setColor(DIAGNOSTIC_TOOLTIP_BACKGROUND, 0xFF252526.toInt())
        setColor(DIAGNOSTIC_TOOLTIP_BRIEF_MSG, 0xFFE0E0E0.toInt())
        setColor(DIAGNOSTIC_TOOLTIP_DETAILED_MSG, 0xFF9E9E9E.toInt())
        setColor(DIAGNOSTIC_TOOLTIP_ACTION, 0xFF03DAC6.toInt())
        
        // 文本操作窗口
        setColor(TEXT_ACTION_WINDOW_BACKGROUND, 0xFF252526.toInt())
        setColor(TEXT_ACTION_WINDOW_ICON_COLOR, 0xFFE0E0E0.toInt())
        
        // 静态 span
        setColor(STATIC_SPAN_BACKGROUND, 0xFF2D2D2D.toInt())
        setColor(STATIC_SPAN_FOREGROUND, 0xFFE0E0E0.toInt())
        
        // 硬换行标记
        setColor(HARD_WRAP_MARKER, 0xFF3D3D3D.toInt())
        
        // 粘性滚动分隔线
        setColor(STICKY_SCROLL_DIVIDER, 0xFF3D3D3D.toInt())
        
        // 函数字符背景描边
        setColor(FUNCTION_CHAR_BACKGROUND_STROKE, 0x4003DAC6)
    }
}
