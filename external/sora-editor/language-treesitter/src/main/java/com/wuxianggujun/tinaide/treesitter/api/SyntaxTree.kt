package com.wuxianggujun.tinaide.treesitter.api

import com.wuxianggujun.tinaide.treesitter.TSInputEdit
import com.wuxianggujun.tinaide.treesitter.TSLanguage
import com.wuxianggujun.tinaide.treesitter.TSPoint
import com.wuxianggujun.tinaide.treesitter.TSTree
import java.io.Closeable
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 安全的语法树包装
 * 
 * 特性:
 * - 自动追踪关闭状态
 * - 防止访问已关闭的树
 * - 提供便捷的遍历 API
 * 
 * 使用示例:
 * ```kotlin
 * parser.parse(source)?.use { tree ->
 *     println("Root: ${tree.root.type}")
 *     
 *     // 遍历所有节点
 *     tree.root.walk { node ->
 *         println("  ${node.type} [${node.startPoint}..${node.endPoint}]")
 *     }
 * }
 * ```
 */
class SyntaxTree internal constructor(
    internal val native: TSTree,
    val language: TSLanguage
) : Closeable {
    
    private val closed = AtomicBoolean(false)
    
    /**
     * 根节点
     */
    val root: SyntaxNode
        get() {
            checkNotClosed()
            return SyntaxNode(native.rootNode, this)
        }
    
    /**
     * 是否已关闭
     */
    val isClosed: Boolean get() = closed.get()
    
    /**
     * 复制语法树
     * 复制的树是独立的，需要单独关闭
     */
    fun copy(): SyntaxTree {
        checkNotClosed()
        return SyntaxTree(native.copy(), language)
    }
    
    /**
     * 编辑语法树（用于增量解析）
     * 
     * @param startByte 编辑起始字节
     * @param oldEndByte 旧内容结束字节
     * @param newEndByte 新内容结束字节
     * @param startPoint 编辑起始位置
     * @param oldEndPoint 旧内容结束位置
     * @param newEndPoint 新内容结束位置
     */
    fun edit(
        startByte: Int,
        oldEndByte: Int,
        newEndByte: Int,
        startPoint: TSPoint,
        oldEndPoint: TSPoint,
        newEndPoint: TSPoint
    ) {
        checkNotClosed()
        native.edit(TSInputEdit(
            startByte, oldEndByte, newEndByte,
            startPoint, oldEndPoint, newEndPoint
        ))
    }
    
    /**
     * 简化的编辑方法
     */
    fun edit(
        startLine: Int, startColumn: Int,
        oldEndLine: Int, oldEndColumn: Int,
        newEndLine: Int, newEndColumn: Int,
        startByte: Int, oldEndByte: Int, newEndByte: Int
    ) {
        edit(
            startByte, oldEndByte, newEndByte,
            TSPoint(startLine, startColumn),
            TSPoint(oldEndLine, oldEndColumn),
            TSPoint(newEndLine, newEndColumn)
        )
    }
    
    override fun close() {
        if (closed.compareAndSet(false, true)) {
            native.close()
        }
    }
    
    internal fun checkNotClosed() {
        check(!closed.get()) { "SyntaxTree has been closed" }
    }
    
    /**
     * 支持 use 扩展函数
     */
    inline fun <R> use(block: (SyntaxTree) -> R): R {
        return try {
            block(this)
        } finally {
            close()
        }
    }
}
