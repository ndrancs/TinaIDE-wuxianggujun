/*
 * CMake Lexer for TinaIDE
 * Copyright (C) 2024 wuxianggujun
 *
 * 词法分析器 - 将 CMake 源码转换为 Token 流
 * 参考: https://cmake.org/cmake/help/v3.26/manual/cmake-language.7.html
 */

package com.wuxianggujun.tinaide.cmake.parser

import com.wuxianggujun.tinaide.cmake.i18n.CMakeI18n
import com.wuxianggujun.tinaide.core.i18n.Strings

/**
 * CMake 词法分析器
 * 负责将源码字符串转换为 Token 流
 *
 * CMake 语法要点:
 * - 标识符: [A-Za-z_][A-Za-z0-9_]*
 * - 带引号参数: "..." 支持转义序列
 * - 无引号参数: 不包含空白、括号、#、"、\ 的字符序列
 * - 括号参数: [[...]] 或 [=[...]=] 等
 * - 行注释: # 到行尾
 * - 块注释: #[[...]] 或 #[=[...]=]
 */
class CMakeLexer(private val source: String) {

    private var position: Int = 0
    private val length: Int = source.length

    /**
     * 解析错误
     */
    sealed class LexerError : Exception() {
        data class UnterminatedString(val startPos: Int) : LexerError() {
            override val message: String
                get() = CMakeI18n.strOrFallback(
                    Strings.cmake_lexer_error_unterminated_string,
                    "Unterminated string, start position: $startPos",
                    startPos
                )
        }

        data class UnterminatedBracket(val startPos: Int) : LexerError() {
            override val message: String
                get() = CMakeI18n.strOrFallback(
                    Strings.cmake_lexer_error_unterminated_bracket,
                    "Unterminated bracket argument, start position: $startPos",
                    startPos
                )
        }

        data class InvalidEscape(val pos: Int, val char: Char) : LexerError() {
            override val message: String
                get() = CMakeI18n.strOrFallback(
                    Strings.cmake_lexer_error_invalid_escape,
                    "Invalid escape sequence '\\$char', position: $pos",
                    char,
                    pos
                )
        }

        data class UnexpectedChar(val pos: Int, val char: Char) : LexerError() {
            override val message: String
                get() = CMakeI18n.strOrFallback(
                    Strings.cmake_lexer_error_unexpected_char,
                    "Unexpected character '$char', position: $pos",
                    char,
                    pos
                )
        }
    }

    // ========== 基础工具方法 ==========

    private fun peek(): Char? = if (position < length) source[position] else null

    private fun peekAt(offset: Int): Char? {
        val idx = position + offset
        return if (idx < length) source[idx] else null
    }

    private fun advance(): Char? {
        return if (position < length) source[position++] else null
    }

    private fun isAtEnd(): Boolean = position >= length

    private fun match(expected: Char): Boolean {
        if (peek() == expected) {
            position++
            return true
        }
        return false
    }

    private fun matchString(expected: String): Boolean {
        if (source.startsWith(expected, position)) {
            position += expected.length
            return true
        }
        return false
    }

    // ========== 字符分类 ==========

    private fun isWhitespace(c: Char): Boolean = c == ' ' || c == '\t'

    private fun isNewline(c: Char): Boolean = c == '\n' || c == '\r'

    private fun isIdentifierStart(c: Char): Boolean = c.isLetter() || c == '_'

    private fun isIdentifierPart(c: Char): Boolean = c.isLetterOrDigit() || c == '_'

    /**
     * 无引号参数中不允许的字符（标准模式）
     */
    private fun isUnquotedForbidden(c: Char): Boolean {
        return c in " \t\r\n()#\"\\".toSet()
    }

    /**
     * Legacy 无引号参数中不允许的字符
     * Legacy 模式允许内嵌引号和 $() 引用
     */
    private fun isLegacyUnquotedForbidden(c: Char): Boolean {
        return c in " \t\r\n()#".toSet()
    }

    // ========== 跳过空白和注释 ==========

    /**
     * 跳过空白字符（不包括换行）
     */
    fun skipSpaces(): Int {
        var count = 0
        while (!isAtEnd() && isWhitespace(peek()!!)) {
            advance()
            count++
        }
        return count
    }

    /**
     * 跳过行尾（包括可选的注释）
     * @return 是否成功匹配行尾
     */
    fun skipLineEnding(): Boolean {
        skipSpaces()

        // 可选的行注释
        if (peek() == '#' && peekAt(1) != '[') {
            skipLineComment()
        }

        // 匹配换行符
        return when {
            match('\n') -> true
            matchString("\r\n") -> true
            match('\r') -> true
            isAtEnd() -> true
            else -> false
        }
    }

    /**
     * 跳过行注释（# 到行尾）
     */
    private fun skipLineComment(): String {
        val start = position
        if (!match('#')) return ""

        // 确保不是块注释
        if (peek() == '[') {
            position = start
            return ""
        }

        val contentStart = position
        while (!isAtEnd() && !isNewline(peek()!!)) {
            advance()
        }
        return source.substring(contentStart, position)
    }

    // ========== Token 解析 ==========

    /**
     * 解析标识符
     * identifier ::= [A-Za-z_][A-Za-z0-9_]*
     */
    fun parseIdentifier(): Token? {
        val start = position
        val c = peek() ?: return null

        if (!isIdentifierStart(c)) return null

        advance()
        while (!isAtEnd() && isIdentifierPart(peek()!!)) {
            advance()
        }

        val text = source.substring(start, position)
        return Token.unquoted(text, start)
    }

    /**
     * 解析括号参数
     * bracket_argument ::= '[' '='* '[' bracket_content ']' '='* ']'
     */
    fun parseBracketArgument(): Result<Token> {
        val start = position

        if (!match('[')) {
            return Result.failure(LexerError.UnexpectedChar(position, peek() ?: '\u0000'))
        }

        // 计算 '=' 的数量
        var bracketLevel = 0
        while (match('=')) {
            bracketLevel++
        }

        if (!match('[')) {
            position = start
            return Result.failure(LexerError.UnexpectedChar(position, peek() ?: '\u0000'))
        }

        // 跳过可选的首行换行
        if (peek() == '\n') advance()
        else if (peek() == '\r' && peekAt(1) == '\n') {
            advance()
            advance()
        }

        // 构建结束标记
        val closeMarker = "]${"=".repeat(bracketLevel)}]"
        val contentStart = position

        // 查找结束标记
        val closeIndex = source.indexOf(closeMarker, position)
        if (closeIndex == -1) {
            return Result.failure(LexerError.UnterminatedBracket(start))
        }

        val content = source.substring(contentStart, closeIndex)
        position = closeIndex + closeMarker.length

        return Result.success(Token(content, false, start, position))
    }

    /**
     * 解析带引号的参数
     * quoted_argument ::= '"' quoted_element* '"'
     * quoted_element ::= <除 '\' 和 '"' 外的任意字符> | escape_sequence | line_continuation
     */
    fun parseQuotedArgument(): Result<Token> {
        val start = position

        if (!match('"')) {
            return Result.failure(LexerError.UnexpectedChar(position, peek() ?: '\u0000'))
        }

        val content = StringBuilder()

        while (!isAtEnd()) {
            when (val c = peek()) {
                '"' -> {
                    advance()
                    return Result.success(Token(content.toString(), true, start, position))
                }

                '\\' -> {
                    advance()
                    val escaped = peek()
                    when (escaped) {
                        null -> return Result.failure(LexerError.UnterminatedString(start))
                        't' -> {
                            content.append('\t')
                            advance()
                        }

                        'r' -> {
                            content.append('\r')
                            advance()
                        }

                        'n' -> {
                            content.append('\n')
                            advance()
                        }

                        '\n' -> {
                            // 行续接：忽略换行
                            advance()
                        }

                        '\r' -> {
                            advance()
                            if (peek() == '\n') advance()
                        }

                        in "()#\" \\$@^;" -> {
                            content.append(escaped)
                            advance()
                        }

                        else -> {
                            // CMake 允许未知转义，原样保留
                            content.append('\\')
                            content.append(escaped)
                            advance()
                        }
                    }
                }

                else -> {
                    content.append(c)
                    advance()
                }
            }
        }

        return Result.failure(LexerError.UnterminatedString(start))
    }

    /**
     * 解析无引号参数
     * unquoted_argument ::= unquoted_element+
     * unquoted_element ::= <除空白、()#"\ 外的字符> | escape_sequence
     */
    fun parseUnquotedArgument(): Result<Token> {
        val start = position
        val content = StringBuilder()

        while (!isAtEnd()) {
            val c = peek()!!

            // 处理转义序列
            if (c == '\\') {
                advance()
                val escaped = peek() ?: break
                when (escaped) {
                    't' -> {
                        content.append('\t')
                        advance()
                    }

                    'r' -> {
                        content.append('\r')
                        advance()
                    }

                    'n' -> {
                        content.append('\n')
                        advance()
                    }

                    in "()#\" \\$@^;" -> {
                        content.append(escaped)
                        advance()
                    }

                    else -> {
                        content.append('\\')
                        content.append(escaped)
                        advance()
                    }
                }
            } else {
                if (isUnquotedForbidden(c)) {
                    break
                }
                content.append(c)
                advance()
            }
        }

        return if (content.isEmpty()) {
            Result.failure(LexerError.UnexpectedChar(position, peek() ?: '\u0000'))
        } else {
            Result.success(Token(content.toString(), false, start, position))
        }
    }

    /**
     * 解析 Legacy 无引号参数（CMake 兼容模式）
     *
     * Legacy 语法支持:
     * - 内嵌引号字符串: -Da="b c", a"b"c
     * - 旧式变量引用: $(VAR), $ENV(VAR)
     * - 混合格式: -Da="b" -Dx=$(y)
     *
     * 参考: https://cmake.org/cmake/help/v3.26/manual/cmake-language.7.html#unquoted-argument
     *
     * 典型用例:
     * - add_definitions(-DVERSION="1.0.0")
     * - set(FLAGS -Da="b c" -Dx=$(y))
     * - target_compile_options(app PRIVATE -I"/path/with spaces")
     */
    fun parseLegacyUnquotedArgument(): Result<Token> {
        val start = position
        val content = StringBuilder()

        while (!isAtEnd()) {
            val c = peek()!!

            // Legacy 模式下，只有空白、换行、括号、# 会终止解析
            if (isLegacyUnquotedForbidden(c)) {
                break
            }

            when (c) {
                // 处理内嵌引号字符串: -Da="b c"
                '"' -> {
                    val quoteStart = position
                    advance() // 跳过开始引号
                    content.append('"')

                    // 读取引号内容直到闭合引号
                    while (!isAtEnd()) {
                        val qc = peek()!!
                        when (qc) {
                            '"' -> {
                                content.append('"')
                                advance()
                                break
                            }
                            '\\' -> {
                                // 处理引号内的转义
                                content.append('\\')
                                advance()
                                if (!isAtEnd()) {
                                    content.append(peek()!!)
                                    advance()
                                }
                            }
                            '\n', '\r' -> {
                                // 引号内遇到换行，视为未闭合
                                // 回退到引号开始位置，作为普通字符处理
                                position = quoteStart
                                content.deleteRange(content.length - 1, content.length)
                                break
                            }
                            else -> {
                                content.append(qc)
                                advance()
                            }
                        }
                    }
                }

                // 处理旧式变量引用: $(VAR)
                '$' -> {
                    content.append(c)
                    advance()

                    // 检查是否是 $(...) 或 $ENV(...) 格式
                    if (!isAtEnd()) {
                        val next = peek()!!
                        when {
                            // $(VAR) 格式
                            next == '(' -> {
                                content.append('(')
                                advance()
                                // 读取直到闭合括号
                                var depth = 1
                                while (!isAtEnd() && depth > 0) {
                                    val vc = peek()!!
                                    content.append(vc)
                                    advance()
                                    when (vc) {
                                        '(' -> depth++
                                        ')' -> depth--
                                    }
                                }
                            }
                            // $ENV(VAR) 或 $CACHE(VAR) 格式
                            next.isLetter() -> {
                                // 读取变量名
                                while (!isAtEnd() && peek()!!.isLetterOrDigit()) {
                                    content.append(peek()!!)
                                    advance()
                                }
                                // 如果后面是括号，读取括号内容
                                if (!isAtEnd() && peek() == '(') {
                                    content.append('(')
                                    advance()
                                    var depth = 1
                                    while (!isAtEnd() && depth > 0) {
                                        val vc = peek()!!
                                        content.append(vc)
                                        advance()
                                        when (vc) {
                                            '(' -> depth++
                                            ')' -> depth--
                                        }
                                    }
                                }
                            }
                            // ${VAR} 格式（标准格式，也支持）
                            next == '{' -> {
                                content.append('{')
                                advance()
                                while (!isAtEnd() && peek() != '}') {
                                    content.append(peek()!!)
                                    advance()
                                }
                                if (!isAtEnd() && peek() == '}') {
                                    content.append('}')
                                    advance()
                                }
                            }
                        }
                    }
                }

                // 处理转义序列
                '\\' -> {
                    advance()
                    val escaped = peek()
                    if (escaped != null) {
                        when (escaped) {
                            't' -> {
                                content.append('\t')
                                advance()
                            }
                            'r' -> {
                                content.append('\r')
                                advance()
                            }
                            'n' -> {
                                content.append('\n')
                                advance()
                            }
                            // 在 Legacy 模式下，保留所有转义字符原样
                            else -> {
                                content.append('\\')
                                content.append(escaped)
                                advance()
                            }
                        }
                    } else {
                        content.append('\\')
                    }
                }

                // 普通字符
                else -> {
                    content.append(c)
                    advance()
                }
            }
        }

        return if (content.isEmpty()) {
            Result.failure(LexerError.UnexpectedChar(position, peek() ?: '\u0000'))
        } else {
            Result.success(Token(content.toString(), false, start, position))
        }
    }

    /**
     * 检测当前位置是否可能是 Legacy 无引号参数
     *
     * Legacy 参数的特征:
     * - 以字母、数字、-、_ 开头
     * - 后面可能包含 = 和引号
     * - 或者以 $ 开头且后面是 (
     */
    private fun looksLikeLegacyUnquoted(): Boolean {
        val c = peek() ?: return false

        // 检查是否有 = 后跟引号的模式: -DFOO="bar"
        if (c == '-' || c.isLetterOrDigit() || c == '_') {
            // 向前扫描查找 =" 模式
            var scanPos = position
            while (scanPos < length) {
                val sc = source[scanPos]
                when {
                    sc == '=' && scanPos + 1 < length && source[scanPos + 1] == '"' -> return true
                    sc in " \t\r\n()#" -> break
                    else -> scanPos++
                }
            }
        }

        // 检查 $( 模式
        if (c == '$' && peekAt(1) == '(') {
            return true
        }

        return false
    }

    /**
     * 解析单个参数（自动判断类型）
     *
     * 解析优先级:
     * 1. 带引号参数 "..."
     * 2. 括号参数 [[...]] 或 [=[...]=]
     * 3. Legacy 无引号参数（检测到 =" 或 $( 模式时）
     * 4. 标准无引号参数
     */
    fun parseArgument(): Result<Token>? {
        skipSpaces()
        if (isAtEnd()) return null

        return when (peek()) {
            '"' -> parseQuotedArgument()
            '[' -> parseBracketArgument()
            ')', '\n', '\r', '#' -> null
            else -> {
                // 智能选择解析模式
                if (looksLikeLegacyUnquoted()) {
                    parseLegacyUnquotedArgument()
                } else {
                    parseUnquotedArgument()
                }
            }
        }
    }

    /**
     * 解析括号块注释
     */
    fun parseBracketComment(): Result<Comment.Bracket>? {
        if (peek() != '#' || peekAt(1) != '[') return null

        val start = position
        advance() // skip #

        val bracketResult = parseBracketArgument()
        return bracketResult.map { token ->
            Comment.Bracket(token.text, 0, start)
        }
    }

    // ========== 公开 API ==========

    /**
     * 当前位置
     */
    fun currentPosition(): Int = position

    /**
     * 设置位置
     */
    fun setPosition(pos: Int) {
        position = pos.coerceIn(0, length)
    }

    /**
     * 是否到达末尾
     */
    fun isEnd(): Boolean = isAtEnd()

    /**
     * 查看当前字符
     */
    fun currentChar(): Char? = peek()

    /**
     * 匹配并消费指定字符
     */
    fun expect(c: Char): Boolean = match(c)

    /**
     * 获取剩余源码
     */
    fun remaining(): String = source.substring(position)
}
