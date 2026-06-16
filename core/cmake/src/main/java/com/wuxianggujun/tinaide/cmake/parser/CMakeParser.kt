/*
 * CMake Parser for TinaIDE
 * Copyright (C) 2024 wuxianggujun
 *
 * 语法分析器 - 将 Token 流转换为 AST
 * 参考: https://cmake.org/cmake/help/v3.26/manual/cmake-language.7.html
 */

package com.wuxianggujun.tinaide.cmake.parser

import com.wuxianggujun.tinaide.cmake.i18n.CMakeI18n
import com.wuxianggujun.tinaide.core.i18n.Strings

/**
 * CMake 语法分析器
 * 负责将源码解析为结构化的 CMakeDocument
 *
 * CMake 文件结构:
 * file ::= file_element*
 * file_element ::= command_invocation newline
 *                | (bracket_comment | space)* newline
 * command_invocation ::= space* identifier space* '(' arguments ')'
 * arguments ::= argument? separated_arguments*
 * separated_arguments ::= separation+ argument?
 *                       | separation* '(' arguments ')'
 */
class CMakeParser(private val source: String) {

    private val lexer = CMakeLexer(source)

    /**
     * 解析错误
     */
    sealed class ParseError : Exception() {
        data class ExpectedChar(val expected: Char, val found: Char?, val pos: Int) : ParseError() {
            override val message: String
                get() {
                    val foundText = found?.toString() ?: "EOF"
                    return CMakeI18n.strOrFallback(
                        Strings.cmake_parser_error_expected_char,
                        "Expected '$expected', got '$foundText', position: $pos",
                        expected.toString(),
                        foundText,
                        pos
                    )
                }
        }

        data class ExpectedIdentifier(val pos: Int) : ParseError() {
            override val message: String
                get() = CMakeI18n.strOrFallback(
                    Strings.cmake_parser_error_expected_identifier,
                    "Expected identifier, position: $pos",
                    pos
                )
        }

        data class UnexpectedEOF(val context: String) : ParseError() {
            override val message: String
                get() = CMakeI18n.strOrFallback(
                    Strings.cmake_parser_error_unexpected_eof,
                    "Unexpected end of file: $context",
                    context
                )
        }

        data class LexerError(val lexerCause: CMakeLexer.LexerError) : ParseError() {
            override val message: String
                get() = CMakeI18n.strOrFallback(
                    Strings.cmake_parser_error_lexer_error,
                    "Lexer error: ${lexerCause.message}",
                    lexerCause.message
                )
        }
    }

    /**
     * 解析整个 CMake 文件
     */
    fun parse(): Result<CMakeDocument> {
        val elements = mutableListOf<FileElement>()

        while (!lexer.isEnd()) {
            val elementResult = parseFileElement()
            when {
                elementResult.isSuccess -> {
                    elementResult.getOrNull()?.let { elements.add(it) }
                }

                elementResult.isFailure -> {
                    return Result.failure(elementResult.exceptionOrNull()!!)
                }
            }
        }

        return Result.success(CMakeDocument(elements, source))
    }

    /**
     * 解析文件元素（命令调用或空行）
     */
    private fun parseFileElement(): Result<FileElement?> {
        lexer.skipSpaces()

        // 检查是否是注释行或空行
        val c = lexer.currentChar()
        when {
            c == null -> return Result.success(null)
            c == '\n' || c == '\r' -> {
                lexer.skipLineEnding()
                return Result.success(FileElement.BlankLine(emptyList()))
            }

            c == '#' -> {
                // 可能是块注释或行注释
                val commentResult = lexer.parseBracketComment()
                if (commentResult != null) {
                    return commentResult.map { FileElement.BlankLine(listOf(it)) }
                }
                // 行注释，跳过
                lexer.skipLineEnding()
                return Result.success(FileElement.BlankLine(emptyList()))
            }
        }

        // 解析命令调用
        return parseCommandInvocation().map { FileElement.Command(it) }
    }

    /**
     * 解析命令调用
     * command_invocation ::= space* identifier space* '(' arguments ')'
     */
    private fun parseCommandInvocation(): Result<CommandInvocation> {
        val startPos = lexer.currentPosition()
        lexer.skipSpaces()

        // 解析命令标识符
        val identToken = lexer.parseIdentifier()
            ?: return Result.failure(ParseError.ExpectedIdentifier(lexer.currentPosition()))

        lexer.skipSpaces()

        // 期望左括号
        if (!lexer.expect('(')) {
            return Result.failure(
                ParseError.ExpectedChar('(', lexer.currentChar(), lexer.currentPosition())
            )
        }

        // 解析参数
        val argumentsResult = parseArguments()
        if (argumentsResult.isFailure) {
            return Result.failure(argumentsResult.exceptionOrNull()!!)
        }
        val arguments = argumentsResult.getOrThrow()

        // 期望右括号
        if (!lexer.expect(')')) {
            return Result.failure(
                ParseError.ExpectedChar(')', lexer.currentChar(), lexer.currentPosition())
            )
        }

        // 跳过行尾
        lexer.skipLineEnding()

        val endPos = lexer.currentPosition()

        return Result.success(
            CommandInvocation(
                identifier = identToken.text.lowercase(),
                originalIdentifier = identToken.text,
                arguments = arguments,
                startOffset = startPos,
                endOffset = endPos
            )
        )
    }

    /**
     * 解析参数列表
     * arguments ::= argument? separated_arguments*
     */
    private fun parseArguments(): Result<List<Token>> {
        val arguments = mutableListOf<Token>()

        // 第一个参数（可选）
        lexer.skipSpaces()
        skipCommentsInArguments()

        val firstArg = parseArgument()
        if (firstArg != null) {
            if (firstArg.isFailure) {
                return Result.failure(firstArg.exceptionOrNull()!!)
            }
            firstArg.getOrNull()?.let { arguments.add(it) }
        }

        // 后续参数
        while (!lexer.isEnd()) {
            lexer.skipSpaces()
            skipCommentsInArguments()

            val c = lexer.currentChar()

            // 检查是否结束
            when (c) {
                ')' -> break
                null -> break

                // 嵌套括号参数
                '(' -> {
                    lexer.expect('(')
                    val nestedResult = parseArguments()
                    if (nestedResult.isFailure) {
                        return nestedResult
                    }
                    arguments.addAll(nestedResult.getOrThrow())

                    if (!lexer.expect(')')) {
                        return Result.failure(
                            ParseError.ExpectedChar(')', lexer.currentChar(), lexer.currentPosition())
                        )
                    }
                    continue
                }

                // 换行也作为分隔符
                '\n', '\r' -> {
                    lexer.skipLineEnding()
                    continue
                }
            }

            // 尝试解析参数
            val argResult = parseArgument()
            if (argResult == null) {
                // 没有更多参数
                break
            }
            if (argResult.isFailure) {
                return Result.failure(argResult.exceptionOrNull()!!)
            }
            argResult.getOrNull()?.let { arguments.add(it) }
        }

        return Result.success(arguments)
    }

    /**
     * 解析单个参数
     */
    private fun parseArgument(): Result<Token>? {
        lexer.skipSpaces()
        skipCommentsInArguments()

        val c = lexer.currentChar() ?: return null

        return when (c) {
            ')' -> null
            '\n', '\r' -> null
            '"' -> lexer.parseQuotedArgument()
            '[' -> lexer.parseBracketArgument()
            '#' -> null // 注释
            else -> lexer.parseArgument()
        }?.let { result ->
            result.mapCatching { it }
        }
    }

    /**
     * 跳过参数列表中的注释
     */
    private fun skipCommentsInArguments() {
        while (!lexer.isEnd()) {
            val c = lexer.currentChar()
            if (c == '#') {
                // 尝试解析块注释
                val bracketComment = lexer.parseBracketComment()
                if (bracketComment != null) {
                    // 已消费块注释
                    lexer.skipSpaces()
                    continue
                }
                // 行注释，不在参数中间处理
                break
            }
            break
        }
    }

    companion object {
        /**
         * 解析 CMake 源码
         *
         * @param source CMake 源码字符串
         * @return 解析结果
         */
        fun parse(source: String): Result<CMakeDocument> = CMakeParser(source).parse()

        /**
         * 解析并返回命令列表（便捷方法）
         */
        fun parseCommands(source: String): Result<List<CommandInvocation>> = parse(source).map { it.commands() }
    }
}
