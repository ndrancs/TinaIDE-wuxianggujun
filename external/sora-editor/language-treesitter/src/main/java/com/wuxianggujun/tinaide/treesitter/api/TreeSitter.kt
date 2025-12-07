package com.wuxianggujun.tinaide.treesitter.api

import com.wuxianggujun.tinaide.treesitter.TSLanguage
import com.wuxianggujun.tinaide.treesitter.TSParser
import java.util.concurrent.ConcurrentHashMap

/**
 * Tree-sitter API 入口点
 * 
 * 提供线程安全的解析器池和便捷的 DSL 风格 API
 * 
 * 使用示例:
 * ```kotlin
 * // 方式1: 使用 DSL
 * TreeSitter.parse(language, sourceCode) { tree ->
 *     tree.root.walk { node ->
 *         println("${node.type}: ${node.text}")
 *     }
 * }
 * 
 * // 方式2: 获取解析器实例
 * val parser = TreeSitter.getParser(language)
 * val tree = parser.parse(sourceCode)
 * // ... 使用 tree
 * tree.close()
 * ```
 */
object TreeSitter {
    
    private val parserPools = ConcurrentHashMap<Long, ParserPool>()
    
    /**
     * 获取指定语言的解析器
     * 解析器从池中获取，使用完毕后会自动归还
     */
    fun getParser(language: TSLanguage): Parser {
        val pool = parserPools.getOrPut(language.getPointer()) {
            ParserPool(language)
        }
        return pool.acquire()
    }
    
    /**
     * DSL 风格解析
     * 自动管理解析器和语法树的生命周期
     */
    inline fun <R> parse(
        language: TSLanguage,
        source: String,
        block: (SyntaxTree) -> R
    ): R {
        val parser = getParser(language)
        return try {
            val tree = parser.parse(source)
                ?: throw TreeSitterException("Failed to parse source")
            try {
                block(tree)
            } finally {
                tree.close()
            }
        } finally {
            parser.release()
        }
    }
    
    /**
     * DSL 风格增量解析
     */
    inline fun <R> parseIncremental(
        language: TSLanguage,
        source: String,
        oldTree: SyntaxTree?,
        block: (SyntaxTree) -> R
    ): R {
        val parser = getParser(language)
        return try {
            val tree = parser.parse(source, oldTree)
                ?: throw TreeSitterException("Failed to parse source")
            try {
                block(tree)
            } finally {
                tree.close()
            }
        } finally {
            parser.release()
        }
    }
    
    /**
     * 清理所有解析器池
     * 通常在应用退出时调用
     */
    fun shutdown() {
        parserPools.values.forEach { it.close() }
        parserPools.clear()
    }
}

/**
 * Tree-sitter 异常
 */
class TreeSitterException(message: String, cause: Throwable? = null) : Exception(message, cause)
