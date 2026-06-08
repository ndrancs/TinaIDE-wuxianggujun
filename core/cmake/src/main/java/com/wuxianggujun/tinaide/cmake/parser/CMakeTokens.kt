/*
 * CMake Parser for TinaIDE
 * Copyright (C) 2024 wuxianggujun
 *
 * 参考实现: https://github.com/rust-utility/cmake-parser
 * CMake Language 规范: https://cmake.org/cmake/help/v3.26/manual/cmake-language.7.html
 */

package com.wuxianggujun.tinaide.cmake.parser

/**
 * CMake Token - 表示解析后的文本单元
 * 对应 Rust 实现中的 Token 结构
 *
 * @property text Token 的文本内容
 * @property quoted 是否为带引号的参数
 * @property startOffset 在源文件中的起始位置
 * @property endOffset 在源文件中的结束位置
 */
data class Token(
    val text: String,
    val quoted: Boolean = false,
    val startOffset: Int = 0,
    val endOffset: Int = 0
) {
    val length: Int get() = text.length

    companion object {
        fun unquoted(text: String, start: Int = 0): Token = Token(text, false, start, start + text.length)

        fun quoted(text: String, start: Int = 0): Token = Token(text, true, start, start + text.length)
    }
}

/**
 * CMake 命令调用
 * 表示一个 CMake 命令，如: set(VAR "value")
 *
 * @property identifier 命令名称（小写标准化）
 * @property originalIdentifier 原始命令名称（保留大小写）
 * @property arguments 命令参数列表
 * @property startOffset 命令在源文件中的起始位置
 * @property endOffset 命令在源文件中的结束位置
 */
data class CommandInvocation(
    val identifier: String,
    val originalIdentifier: String,
    val arguments: List<Token>,
    val startOffset: Int = 0,
    val endOffset: Int = 0
) {
    /**
     * 获取标准化的命令名称（小写）
     */
    fun normalizedIdentifier(): String = identifier.lowercase()
}

/**
 * CMake 注释
 */
sealed class Comment {
    /**
     * 行注释: # comment
     */
    data class Line(val content: String, val offset: Int) : Comment()

    /**
     * 块注释: #[[ comment ]]
     */
    data class Bracket(val content: String, val bracketLength: Int, val offset: Int) : Comment()
}

/**
 * 文件元素 - CMake 文件的基本构成单元
 */
sealed class FileElement {
    /**
     * 命令调用
     */
    data class Command(val invocation: CommandInvocation) : FileElement()

    /**
     * 空行或仅包含空白/注释的行
     */
    data class BlankLine(val comments: List<Comment>) : FileElement()
}

/**
 * CMake 文件解析结果
 *
 * @property elements 文件中的所有元素（命令和空行）
 * @property source 原始源码（用于错误报告）
 */
data class CMakeDocument(
    val elements: List<FileElement>,
    val source: String
) {
    /**
     * 获取所有命令调用
     */
    fun commands(): List<CommandInvocation> = elements.filterIsInstance<FileElement.Command>().map { it.invocation }

    /**
     * 按命令名称过滤
     */
    fun commandsByName(name: String): List<CommandInvocation> {
        val normalizedName = name.lowercase()
        return commands().filter { it.normalizedIdentifier() == normalizedName }
    }
}
