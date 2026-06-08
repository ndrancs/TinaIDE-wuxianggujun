package com.wuxianggujun.tinaide.core.editorview

import androidx.compose.ui.graphics.Color
import com.wuxianggujun.tinaide.core.treesitter.HighlightType

/**
 * TinaEditor 配色方案（编辑器 UI + 语法高亮）。
 *
 * 设计目标：
 * - 运行时可切换（Prefs / 插件主题）
 * - UI 渲染层与语法高亮共用同一套颜色来源，避免“主题切换后局部没更新”
 */
data class EditorColorScheme(
    val background: Color,
    val foreground: Color,
    val lineNumberBackground: Color,
    val gutterBackground: Color,
    val gutterDivider: Color,
    val lineNumberForeground: Color,
    val lineNumberForegroundActive: Color,
    val selectionBackground: Color,
    val currentLineBackground: Color,
    val cursor: Color,
    val selectionHandle: Color,
    val scrollbarTrack: Color,
    val scrollbarThumb: Color,
    val scrollbarThumbHover: Color,
    // gutter/marker
    val breakpoint: Color,
    val bookmark: Color,
    val gutterDiagnostic: Color,
    val foldIconExpanded: Color,
    val foldIconCollapsed: Color,
    val foldIconWarning: Color,
    val foldPlaceholderForeground: Color = Color(0xFFA0A0A0),
    val foldPlaceholderBackground: Color = Color(0x20808080),
    // diagnostics
    val diagnosticError: Color,
    val diagnosticWarning: Color,
    val diagnosticInfo: Color,
    val diagnosticHint: Color,
    val rainbowBracketColors: List<Color> = DEFAULT_RAINBOW_BRACKET_COLORS,
    val bracketPairGuide: Color = Color(0x33808080),
    val bracketPairGuideActive: Color = Color(0x66808080),
    val whitespace: Color = Color(0x40808080),
    val syntax: EditorSyntaxColors
) {
    companion object {
        private val DEFAULT_RAINBOW_BRACKET_COLORS = listOf(
            Color(0xFFE6B422), // gold
            Color(0xFFDA70D6), // orchid
            Color(0xFF179FFF), // cornflower blue
            Color(0xFF00C9A7), // medium spring green
            Color(0xFFFF6B6B), // light coral
            Color(0xFFC0A0FF) // light purple
        )

        fun builtinGray(): EditorColorScheme {
            val foreground = Color(0xFFE6E8EB)
            val warning = Color(0xFFE6A700)
            val error = Color(0xFFD84A4A)
            return EditorColorScheme(
                background = Color(0xFF202124),
                foreground = foreground,
                lineNumberBackground = Color(0xFF2A2C31),
                gutterBackground = Color(0xFF25272C),
                gutterDivider = Color(0xFF3A3E45),
                lineNumberForeground = Color(0xFF9AA0A6),
                lineNumberForegroundActive = foreground,
                selectionBackground = Color(0x664A90D9),
                currentLineBackground = Color(0x1A3A5C8F),
                cursor = foreground,
                selectionHandle = Color(0xFF5DA3FF),
                scrollbarTrack = Color(0x55677182),
                scrollbarThumb = Color(0xB3E7EDF7),
                scrollbarThumbHover = Color(0xDDE7EDF7),
                breakpoint = error,
                bookmark = Color(0xFF2E7D32),
                gutterDiagnostic = warning,
                foldIconExpanded = Color(0xFF7A7A7A),
                foldIconCollapsed = Color(0xFFB0B0B0),
                foldIconWarning = warning,
                diagnosticError = error,
                diagnosticWarning = warning,
                diagnosticInfo = Color(0xFF4AA3FF),
                diagnosticHint = Color(0xFF6FA8DC),
                syntax = EditorSyntaxColors(
                    defaultText = foreground,
                    keyword = Color(0xFF569CD6),
                    function = Color(0xFFDCDCAA),
                    // 普通标识符默认跟正文同色，避免在 C/C++ 里把用户变量刷得比关键字更抢眼。
                    variable = foreground,
                    property = foreground,
                    type = Color(0xFF4EC9B0),
                    string = Color(0xFFCE9178),
                    number = Color(0xFFB5CEA8),
                    comment = Color(0xFF6A9955),
                    operator = Color(0xFFD4D4D4),
                    punctuation = Color(0xFFD4D4D4),
                    constant = Color(0xFFD7BA7D),
                    builtin = Color(0xFF4EC9B0)
                )
            )
        }

        fun builtinDark(): EditorColorScheme {
            val foreground = Color(0xFFE6E8EB)
            return EditorColorScheme(
                background = Color(0xFF1E1E1E),
                foreground = foreground,
                lineNumberBackground = Color(0xFF252526),
                gutterBackground = Color(0xFF252526),
                gutterDivider = Color(0xFF2D2D2D),
                lineNumberForeground = Color(0xFF8E8E8E),
                lineNumberForegroundActive = foreground,
                selectionBackground = Color(0x66397FF5),
                currentLineBackground = Color(0x1AFFFFFF),
                cursor = foreground,
                selectionHandle = Color(0xFF5DA3FF),
                scrollbarTrack = Color(0x552D2D2D),
                scrollbarThumb = Color(0xB3C5C5C5),
                scrollbarThumbHover = Color(0xDDC5C5C5),
                breakpoint = builtinGray().breakpoint,
                bookmark = builtinGray().bookmark,
                gutterDiagnostic = builtinGray().gutterDiagnostic,
                foldIconExpanded = builtinGray().foldIconExpanded,
                foldIconCollapsed = builtinGray().foldIconCollapsed,
                foldIconWarning = builtinGray().foldIconWarning,
                diagnosticError = builtinGray().diagnosticError,
                diagnosticWarning = builtinGray().diagnosticWarning,
                diagnosticInfo = builtinGray().diagnosticInfo,
                diagnosticHint = builtinGray().diagnosticHint,
                syntax = builtinGray().syntax.copy(
                    defaultText = foreground,
                    variable = foreground,
                    property = foreground
                )
            )
        }

        fun builtinLight(): EditorColorScheme {
            val foreground = Color(0xFF1F1F1F)
            val warning = Color(0xFFE6A700)
            val error = Color(0xFFD84A4A)
            return EditorColorScheme(
                background = Color(0xFFFFFFFF),
                foreground = foreground,
                lineNumberBackground = Color(0xFFF3F3F3),
                gutterBackground = Color(0xFFF3F3F3),
                gutterDivider = Color(0xFFE0E0E0),
                lineNumberForeground = Color(0xFF6B6B6B),
                lineNumberForegroundActive = foreground,
                selectionBackground = Color(0x66397FF5),
                currentLineBackground = Color(0x0F000000),
                cursor = Color(0xFF000000),
                selectionHandle = Color(0xFF2F74FF),
                scrollbarTrack = Color(0x33000000),
                scrollbarThumb = Color(0x66000000),
                scrollbarThumbHover = Color(0x99000000),
                breakpoint = error,
                bookmark = Color(0xFF2E7D32),
                gutterDiagnostic = warning,
                foldIconExpanded = Color(0xFF7A7A7A),
                foldIconCollapsed = Color(0xFFB0B0B0),
                foldIconWarning = warning,
                diagnosticError = error,
                diagnosticWarning = warning,
                diagnosticInfo = Color(0xFF1E6BD6),
                diagnosticHint = Color(0xFF1E6BD6),
                syntax = EditorSyntaxColors(
                    defaultText = foreground,
                    keyword = Color(0xFF0000AA),
                    function = Color(0xFF795E26),
                    variable = foreground,
                    property = foreground,
                    type = Color(0xFF267F99),
                    string = Color(0xFFA31515),
                    number = Color(0xFF098658),
                    comment = Color(0xFF008000),
                    operator = foreground,
                    punctuation = foreground,
                    constant = Color(0xFF800000),
                    builtin = Color(0xFF267F99)
                )
            )
        }

        /**
         * 将插件主题（ThemeConfig.colors）映射为编辑器配色。
         *
         * 兼容两套 key（按优先级从高到低）：
         *
         * 1) 数字 ID（文档约定，覆盖常量名）：
         * - 4/WHOLE_BACKGROUND: 编辑器背景
         * - 5/TEXT_NORMAL: 普通文本
         * - 3/LINE_NUMBER_BACKGROUND: 行号区背景（同时用于 gutter 背景）
         * - 1/LINE_DIVIDER: 分割线（用于 gutter 分割线）
         * - 2/LINE_NUMBER: 行号文字
         * - 45/LINE_NUMBER_CURRENT: 当前行号文字
         * - 6/SELECTED_TEXT_BACKGROUND: 选区背景
         * - 9/CURRENT_LINE: 当前行背景
         * - 7/SELECTION_INSERT: 光标
         * - 8/SELECTION_HANDLE: 选择手柄
         * - 13/SCROLL_BAR_TRACK: 滚动条轨道
         * - 11/SCROLL_BAR_THUMB: 滚动条滑块
         * - 12/SCROLL_BAR_THUMB_PRESSED: 滚动条按下/hover
         * - 21/KEYWORD, 22/COMMENT, 23/OPERATOR, 24/LITERAL, 25/IDENTIFIER_VAR, 26/IDENTIFIER_NAME, 27/FUNCTION_NAME: 语法高亮
         *
         * 2) 新 key（便于后续扩展）：
         * - editor.background / editor.foreground / editor.selection / editor.cursor / editor.cursorLine
         * - editor.lineNumber / editor.lineNumberActive
         * - gutter.background / gutter.divider
         * - scrollbar.track / scrollbar.thumb / scrollbar.thumbHover
         * - syntax.keyword / syntax.string / syntax.number / syntax.comment / syntax.function / syntax.variable / syntax.type / syntax.operator / syntax.punctuation
         */
        fun fromThemeColors(
            colors: Map<String, String>,
            fallback: EditorColorScheme = builtinGray()
        ): EditorColorScheme {
            fun parse(key: String): Color? = parseColorOrNull(colors[key])

            fun pick(default: Color, vararg keys: String): Color {
                for (key in keys) {
                    val parsed = parse(key)
                    if (parsed != null) return parsed
                }
                return default
            }

            val background = pick(
                fallback.background,
                "4",
                "WHOLE_BACKGROUND",
                "editor.background"
            )
            val foreground = pick(
                fallback.foreground,
                "5",
                "TEXT_NORMAL",
                "editor.foreground"
            )
            val gutterBackground = pick(
                fallback.gutterBackground,
                "3",
                "LINE_NUMBER_BACKGROUND",
                "gutter.background"
            )
            val gutterDivider = pick(
                fallback.gutterDivider,
                "1",
                "LINE_DIVIDER",
                "gutter.divider"
            )

            val lineNumberForeground = pick(
                fallback.lineNumberForeground,
                "2",
                "LINE_NUMBER",
                "editor.lineNumber"
            )
            val lineNumberForegroundActive = pick(
                fallback.lineNumberForegroundActive,
                "45",
                "LINE_NUMBER_CURRENT",
                "editor.lineNumberActive"
            )
            val selectionBackground = pick(
                fallback.selectionBackground,
                "6",
                "SELECTED_TEXT_BACKGROUND",
                "editor.selection"
            )
            val currentLineBackground = pick(
                fallback.currentLineBackground,
                "9",
                "CURRENT_LINE",
                "editor.cursorLine"
            )
            val cursor = pick(
                fallback.cursor,
                "7",
                "SELECTION_INSERT",
                "editor.cursor"
            )
            val selectionHandle = pick(
                fallback.selectionHandle,
                "8",
                "SELECTION_HANDLE"
            )
            val scrollbarTrack = pick(
                fallback.scrollbarTrack,
                "13",
                "SCROLL_BAR_TRACK",
                "scrollbar.track"
            )
            val scrollbarThumb = pick(
                fallback.scrollbarThumb,
                "11",
                "SCROLL_BAR_THUMB",
                "scrollbar.thumb"
            )
            val scrollbarThumbHover = pick(
                fallback.scrollbarThumbHover,
                "12",
                "SCROLL_BAR_THUMB_PRESSED",
                "scrollbar.thumbHover"
            )

            val syntaxKeyword = pick(fallback.syntax.keyword, "21", "KEYWORD", "syntax.keyword")
            val syntaxComment = pick(fallback.syntax.comment, "22", "COMMENT", "syntax.comment")
            val syntaxOperator = pick(fallback.syntax.operator, "23", "OPERATOR", "syntax.operator")
            val syntaxLiteral = pick(fallback.syntax.string, "24", "LITERAL", "syntax.string")
            val syntaxVariable = pick(fallback.syntax.variable, "25", "IDENTIFIER_VAR", "syntax.variable")
            val syntaxProperty = pick(fallback.syntax.property, "syntax.property")
            val syntaxType = pick(fallback.syntax.type, "26", "IDENTIFIER_NAME", "syntax.type")
            val syntaxFunction = pick(fallback.syntax.function, "27", "FUNCTION_NAME", "syntax.function")
            val syntaxNumber = pick(fallback.syntax.number, "24", "LITERAL", "syntax.number")
            val syntaxPunctuation = pick(fallback.syntax.punctuation, "23", "OPERATOR", "syntax.punctuation")
            val syntaxConstant = pick(fallback.syntax.constant, "syntax.constant")
            val syntaxBuiltin = pick(fallback.syntax.builtin, "syntax.builtin")

            val rainbowColors = (0 until 6).mapNotNull { index ->
                parse("rainbowBrackets.$index")
                    ?: parse("${256 + index}")
            }.ifEmpty { fallback.rainbowBracketColors }

            return fallback.copy(
                rainbowBracketColors = rainbowColors,
                background = background,
                foreground = foreground,
                lineNumberBackground = gutterBackground,
                gutterBackground = gutterBackground,
                gutterDivider = gutterDivider,
                lineNumberForeground = lineNumberForeground,
                lineNumberForegroundActive = lineNumberForegroundActive,
                selectionBackground = selectionBackground,
                currentLineBackground = currentLineBackground,
                cursor = cursor,
                selectionHandle = selectionHandle,
                scrollbarTrack = scrollbarTrack,
                scrollbarThumb = scrollbarThumb,
                scrollbarThumbHover = scrollbarThumbHover,
                breakpoint = pick(
                    fallback.breakpoint,
                    "gutter.breakpoint",
                    "breakpoint",
                    "35",
                    "PROBLEM_ERROR"
                ),
                bookmark = pick(
                    fallback.bookmark,
                    "gutter.bookmark",
                    "bookmark"
                ),
                gutterDiagnostic = pick(
                    fallback.gutterDiagnostic,
                    "gutter.diagnostic",
                    "gutterDiagnostic",
                    "36",
                    "PROBLEM_WARNING"
                ),
                foldIconExpanded = pick(
                    fallback.foldIconExpanded,
                    "folding.iconExpanded",
                    "folding.icon",
                    "foldIconExpanded",
                    "81",
                    "FOLDING_ICON"
                ),
                foldIconCollapsed = pick(
                    fallback.foldIconCollapsed,
                    "folding.iconCollapsed",
                    "foldIconCollapsed"
                ),
                foldIconWarning = pick(
                    fallback.foldIconWarning,
                    "folding.iconWarning",
                    "foldIconWarning",
                    "36",
                    "PROBLEM_WARNING"
                ),
                diagnosticError = pick(
                    fallback.diagnosticError,
                    "diagnostic.error",
                    "problem.error",
                    "35",
                    "PROBLEM_ERROR"
                ),
                diagnosticWarning = pick(
                    fallback.diagnosticWarning,
                    "diagnostic.warning",
                    "problem.warning",
                    "36",
                    "PROBLEM_WARNING"
                ),
                diagnosticInfo = pick(
                    fallback.diagnosticInfo,
                    "diagnostic.info",
                    "problem.info"
                ),
                diagnosticHint = pick(
                    fallback.diagnosticHint,
                    "diagnostic.hint",
                    "problem.hint",
                    "37",
                    "PROBLEM_TYPO"
                ),
                syntax = fallback.syntax.copy(
                    defaultText = foreground,
                    keyword = syntaxKeyword,
                    function = syntaxFunction,
                    variable = syntaxVariable,
                    property = syntaxProperty.let { if (it == fallback.syntax.property) syntaxVariable else it },
                    type = syntaxType,
                    string = syntaxLiteral,
                    number = syntaxNumber,
                    comment = syntaxComment,
                    operator = syntaxOperator,
                    punctuation = syntaxPunctuation,
                    constant = syntaxConstant,
                    builtin = syntaxBuiltin
                )
            )
        }

        private fun parseColorOrNull(value: String?): Color? {
            val raw = value?.trim().orEmpty()
            if (raw.isEmpty()) return null
            if (raw == "0") return null
            val normalized = when {
                raw.startsWith("0x", ignoreCase = true) -> "#${raw.drop(2)}"
                else -> raw
            }
            return runCatching {
                Color(android.graphics.Color.parseColor(normalized))
            }.getOrNull()
        }
    }
}

data class EditorSyntaxColors(
    val defaultText: Color,
    val keyword: Color,
    val function: Color,
    val variable: Color,
    val property: Color = variable,
    val type: Color,
    val string: Color,
    val number: Color,
    val comment: Color,
    val operator: Color,
    val punctuation: Color,
    val constant: Color = type, // 命名常量：cmake VERSION/SHARED，Kotlin SCREAMING_CASE
    val builtin: Color = type, // 内置标识符：内置函数/类型/变量
    val deprecated: Color = Color(0xFFA0A0A0)
) {
    fun colorOf(highlightType: HighlightType): Color = when (highlightType) {
        HighlightType.KEYWORD -> keyword
        HighlightType.FUNCTION -> function
        HighlightType.VARIABLE -> variable
        HighlightType.PROPERTY -> property
        HighlightType.TYPE -> type
        HighlightType.STRING -> string
        HighlightType.NUMBER -> number
        HighlightType.COMMENT -> comment
        HighlightType.OPERATOR -> operator
        HighlightType.PUNCTUATION -> punctuation
        HighlightType.CONSTANT -> constant
        HighlightType.BUILTIN -> builtin
        HighlightType.DEFAULT -> defaultText
    }

    fun colorOfSemantic(
        tokenType: SemanticTokenType,
        tokenModifiers: Set<SemanticTokenModifier>
    ): Color {
        if (SemanticTokenModifier.DEPRECATED in tokenModifiers) {
            return deprecated
        }

        val mappedType = when (tokenType) {
            SemanticTokenType.NAMESPACE,
            SemanticTokenType.TYPE,
            SemanticTokenType.CLASS,
            SemanticTokenType.ENUM,
            SemanticTokenType.INTERFACE,
            SemanticTokenType.STRUCT,
            SemanticTokenType.TYPE_PARAMETER -> HighlightType.TYPE

            SemanticTokenType.PARAMETER,
            SemanticTokenType.VARIABLE,
            SemanticTokenType.PROPERTY,
            SemanticTokenType.EVENT -> HighlightType.PROPERTY

            SemanticTokenType.ENUM_MEMBER -> HighlightType.CONSTANT

            SemanticTokenType.FUNCTION,
            SemanticTokenType.METHOD,
            SemanticTokenType.MACRO -> HighlightType.FUNCTION

            SemanticTokenType.KEYWORD,
            SemanticTokenType.MODIFIER -> HighlightType.KEYWORD

            SemanticTokenType.COMMENT -> HighlightType.COMMENT
            SemanticTokenType.STRING,
            SemanticTokenType.REGEXP -> HighlightType.STRING

            SemanticTokenType.NUMBER -> HighlightType.NUMBER
            SemanticTokenType.OPERATOR -> HighlightType.OPERATOR
        }
        return colorOf(mappedType)
    }
}
