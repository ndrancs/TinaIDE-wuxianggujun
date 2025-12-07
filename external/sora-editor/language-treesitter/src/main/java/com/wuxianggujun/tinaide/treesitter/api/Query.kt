package com.wuxianggujun.tinaide.treesitter.api

import com.wuxianggujun.tinaide.treesitter.TSLanguage
import com.wuxianggujun.tinaide.treesitter.TSPoint
import com.wuxianggujun.tinaide.treesitter.TSQuery
import com.wuxianggujun.tinaide.treesitter.TSQueryCursor
import com.wuxianggujun.tinaide.treesitter.TSQueryError
import java.io.Closeable
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 安全的查询包装
 * 
 * 使用示例:
 * ```kotlin
 * Query.create(language, "(function_definition) @func").use { query ->
 *     query.execute(tree.root).forEach { match ->
 *         match.captures.forEach { capture ->
 *             println("${capture.name}: ${capture.node.type}")
 *         }
 *     }
 * }
 * ```
 */
class Query private constructor(
    private val native: TSQuery,
    val language: TSLanguage
) : Closeable {
    
    companion object {
        /**
         * 创建查询
         * 
         * @param language 语言
         * @param source 查询源码 (S-expression)
         * @return 查询结果，包含查询对象或错误信息
         */
        fun create(language: TSLanguage, source: String): QueryCreateResult {
            val query = TSQuery.create(language, source)
            return if (query.canAccess()) {
                QueryCreateResult.Success(Query(query, language))
            } else {
                query.close()
                QueryCreateResult.Error(
                    query.errorType,
                    query.errorOffset,
                    "Query error: ${query.errorType} at offset ${query.errorOffset}"
                )
            }
        }
        
        /**
         * 创建查询，失败时抛出异常
         */
        fun createOrThrow(language: TSLanguage, source: String): Query {
            return when (val result = create(language, source)) {
                is QueryCreateResult.Success -> result.query
                is QueryCreateResult.Error -> throw TreeSitterException(result.message)
            }
        }
    }
    
    private val closed = AtomicBoolean(false)
    
    /**
     * 模式数量
     */
    val patternCount: Int
        get() {
            checkNotClosed()
            return native.patternCount
        }
    
    /**
     * 捕获数量
     */
    val captureCount: Int
        get() {
            checkNotClosed()
            return native.captureCount
        }
    
    /**
     * 获取捕获名称
     */
    fun getCaptureName(index: Int): String {
        checkNotClosed()
        return native.getCaptureNameForId(index)
    }
    
    /**
     * 在节点上执行查询
     * 
     * @param node 要查询的节点
     * @param startPoint 可选的起始位置限制
     * @param endPoint 可选的结束位置限制
     * @return 匹配结果序列
     */
    fun execute(
        node: SyntaxNode,
        startPoint: TSPoint? = null,
        endPoint: TSPoint? = null
    ): Sequence<QueryMatch> {
        checkNotClosed()
        return QueryExecutor(native, node, startPoint, endPoint).asSequence()
    }
    
    /**
     * 在节点上执行查询，返回所有匹配
     */
    fun executeAll(
        node: SyntaxNode,
        startPoint: TSPoint? = null,
        endPoint: TSPoint? = null
    ): List<QueryMatch> = execute(node, startPoint, endPoint).toList()
    
    /**
     * 在节点上执行查询，使用回调处理每个匹配
     */
    inline fun execute(
        node: SyntaxNode,
        startPoint: TSPoint? = null,
        endPoint: TSPoint? = null,
        onMatch: (QueryMatch) -> Unit
    ) {
        execute(node, startPoint, endPoint).forEach(onMatch)
    }
    
    override fun close() {
        if (closed.compareAndSet(false, true)) {
            native.close()
        }
    }
    
    val isClosed: Boolean get() = closed.get()
    
    private fun checkNotClosed() {
        check(!closed.get()) { "Query has been closed" }
    }
    
    /**
     * 获取底层 TSQuery（供 sora-editor 集成使用）
     */
    internal fun toTSQuery(): TSQuery = native
    
    inline fun <R> use(block: (Query) -> R): R {
        return try {
            block(this)
        } finally {
            close()
        }
    }
}

/**
 * 查询创建结果
 */
sealed class QueryCreateResult {
    data class Success(val query: Query) : QueryCreateResult()
    data class Error(
        val errorType: TSQueryError,
        val errorOffset: Int,
        val message: String
    ) : QueryCreateResult()
}

/**
 * 查询执行器（内部使用）
 */
internal class QueryExecutor(
    private val query: TSQuery,
    private val node: SyntaxNode,
    private val startPoint: TSPoint?,
    private val endPoint: TSPoint?
) : Iterator<QueryMatch>, Closeable {
    
    private val cursor = TSQueryCursor.create()
    private var nextMatch: QueryMatch? = null
    private var initialized = false
    private var closed = false
    
    private fun ensureInitialized() {
        if (!initialized) {
            initialized = true
            if (startPoint != null && endPoint != null) {
                cursor.setPointRange(startPoint, endPoint)
            }
            cursor.exec(query, node.toTSNode())
            advance()
        }
    }
    
    private fun advance() {
        val match = cursor.nextMatch()
        nextMatch = match?.let { m ->
            QueryMatch(
                id = m.id,
                patternIndex = m.patternIndex,
                captures = m.captures.map { c ->
                    QueryCapture(
                        node = SyntaxNode(c.node, node.tree),
                        index = c.index,
                        name = query.getCaptureNameForId(c.index)
                    )
                }
            )
        }
    }
    
    override fun hasNext(): Boolean {
        ensureInitialized()
        return nextMatch != null
    }
    
    override fun next(): QueryMatch {
        ensureInitialized()
        val result = nextMatch ?: throw NoSuchElementException()
        advance()
        return result
    }
    
    override fun close() {
        if (!closed) {
            closed = true
            cursor.close()
        }
    }
    
    fun asSequence(): Sequence<QueryMatch> = sequence {
        try {
            while (hasNext()) {
                yield(next())
            }
        } finally {
            close()
        }
    }
}

private val SyntaxNode.tree: SyntaxTree
    get() = this.getTree()
