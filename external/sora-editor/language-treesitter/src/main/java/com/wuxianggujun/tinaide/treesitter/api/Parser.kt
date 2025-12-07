package com.wuxianggujun.tinaide.treesitter.api

import com.wuxianggujun.tinaide.treesitter.TSLanguage
import com.wuxianggujun.tinaide.treesitter.TSParser
import com.wuxianggujun.tinaide.treesitter.UTF16String
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 安全的解析器包装
 * 
 * 特性:
 * - 自动归还到池中
 * - 防止重复释放
 * - 生命周期追踪
 * 
 * 使用示例:
 * ```kotlin
 * val parser = TreeSitter.getParser(language)
 * try {
 *     val tree = parser.parse(sourceCode)
 *     // 使用 tree...
 *     tree?.close()
 * } finally {
 *     parser.release()
 * }
 * 
 * // 或者使用 use 扩展
 * TreeSitter.getParser(language).use { parser ->
 *     parser.parse(sourceCode)?.use { tree ->
 *         // 使用 tree...
 *     }
 * }
 * ```
 */
class Parser internal constructor(
    private val native: TSParser,
    val language: TSLanguage,
    private val pool: ParserPool
) {
    private val released = AtomicBoolean(false)
    
    /**
     * 解析源代码字符串
     * 
     * @param source 源代码
     * @param oldTree 旧的语法树（用于增量解析）
     * @return 语法树，解析失败返回 null
     */
    fun parse(source: String, oldTree: SyntaxTree? = null): SyntaxTree? {
        checkNotReleased()
        val tree = native.parseString(oldTree?.native, source) ?: return null
        return SyntaxTree(tree, language)
    }
    
    /**
     * 解析 UTF-16 编码的源代码
     * 适用于 Android 的 CharSequence
     */
    fun parse(source: UTF16String, oldTree: SyntaxTree? = null): SyntaxTree? {
        checkNotReleased()
        val tree = native.parseString(oldTree?.native, source) ?: return null
        return SyntaxTree(tree, language)
    }
    
    /**
     * 释放解析器（归还到池中）
     */
    fun release() {
        if (released.compareAndSet(false, true)) {
            pool.release(native)
        }
    }
    
    /**
     * 是否已释放
     */
    val isReleased: Boolean get() = released.get()
    
    private fun checkNotReleased() {
        check(!released.get()) { "Parser has been released" }
    }
    
    /**
     * 支持 use 扩展函数
     */
    inline fun <R> use(block: (Parser) -> R): R {
        return try {
            block(this)
        } finally {
            release()
        }
    }
}
