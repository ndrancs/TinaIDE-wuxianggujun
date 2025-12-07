package com.wuxianggujun.tinaide.treesitter.api

import com.wuxianggujun.tinaide.treesitter.TSNode
import com.wuxianggujun.tinaide.treesitter.TSPoint

/**
 * 安全的语法节点包装
 * 
 * 特性:
 * - 绑定到 SyntaxTree 生命周期
 * - 访问前检查树是否已关闭
 * - 提供便捷的遍历和查询 API
 * 
 * 注意: SyntaxNode 是轻量级的值对象，不需要手动关闭。
 * 但是当关联的 SyntaxTree 关闭后，节点将不可访问。
 */
class SyntaxNode internal constructor(
    private val native: TSNode,
    private val tree: SyntaxTree
) {
    
    /**
     * 节点类型（如 "function_definition", "identifier" 等）
     */
    val type: String
        get() {
            tree.checkNotClosed()
            return native.type ?: ""
        }
    
    /**
     * 节点符号 ID
     */
    val symbol: Int
        get() {
            tree.checkNotClosed()
            return native.symbol
        }
    
    /**
     * 起始字节偏移
     */
    val startByte: Int
        get() {
            tree.checkNotClosed()
            return native.startByte
        }
    
    /**
     * 结束字节偏移
     */
    val endByte: Int
        get() {
            tree.checkNotClosed()
            return native.endByte
        }
    
    /**
     * 起始位置 (行, 列)
     */
    val startPoint: TSPoint
        get() {
            tree.checkNotClosed()
            return native.startPoint
        }
    
    /**
     * 结束位置 (行, 列)
     */
    val endPoint: TSPoint
        get() {
            tree.checkNotClosed()
            return native.endPoint
        }
    
    /**
     * 是否为命名节点
     */
    val isNamed: Boolean
        get() {
            tree.checkNotClosed()
            return native.isNamed
        }
    
    /**
     * 是否为缺失节点（语法错误恢复产生的）
     */
    val isMissing: Boolean
        get() {
            tree.checkNotClosed()
            return native.isMissing
        }
    
    /**
     * 是否包含语法错误
     */
    val hasError: Boolean
        get() {
            tree.checkNotClosed()
            return native.hasError
        }
    
    /**
     * 子节点数量
     */
    val childCount: Int
        get() {
            tree.checkNotClosed()
            return native.childCount
        }
    
    /**
     * 命名子节点数量
     */
    val namedChildCount: Int
        get() {
            tree.checkNotClosed()
            return native.namedChildCount
        }
    
    /**
     * 获取指定索引的子节点
     */
    fun child(index: Int): SyntaxNode? {
        tree.checkNotClosed()
        val child = native.getChild(index)
        return if (child.isNull) null else SyntaxNode(child, tree)
    }
    
    /**
     * 获取指定索引的命名子节点
     */
    fun namedChild(index: Int): SyntaxNode? {
        tree.checkNotClosed()
        val child = native.getNamedChild(index)
        return if (child.isNull) null else SyntaxNode(child, tree)
    }
    
    /**
     * 父节点
     */
    val parent: SyntaxNode?
        get() {
            tree.checkNotClosed()
            val p = native.parent
            return if (p.isNull) null else SyntaxNode(p, tree)
        }
    
    /**
     * 下一个兄弟节点
     */
    val nextSibling: SyntaxNode?
        get() {
            tree.checkNotClosed()
            val s = native.nextSibling
            return if (s.isNull) null else SyntaxNode(s, tree)
        }
    
    /**
     * 上一个兄弟节点
     */
    val prevSibling: SyntaxNode?
        get() {
            tree.checkNotClosed()
            val s = native.prevSibling
            return if (s.isNull) null else SyntaxNode(s, tree)
        }
    
    /**
     * 下一个命名兄弟节点
     */
    val nextNamedSibling: SyntaxNode?
        get() {
            tree.checkNotClosed()
            val s = native.nextNamedSibling
            return if (s.isNull) null else SyntaxNode(s, tree)
        }
    
    /**
     * 上一个命名兄弟节点
     */
    val prevNamedSibling: SyntaxNode?
        get() {
            tree.checkNotClosed()
            val s = native.prevNamedSibling
            return if (s.isNull) null else SyntaxNode(s, tree)
        }
    
    /**
     * 所有子节点序列
     */
    val children: Sequence<SyntaxNode>
        get() = sequence {
            tree.checkNotClosed()
            for (i in 0 until childCount) {
                child(i)?.let { yield(it) }
            }
        }
    
    /**
     * 所有命名子节点序列
     */
    val namedChildren: Sequence<SyntaxNode>
        get() = sequence {
            tree.checkNotClosed()
            for (i in 0 until namedChildCount) {
                namedChild(i)?.let { yield(it) }
            }
        }
    
    /**
     * 深度优先遍历所有后代节点
     */
    fun walk(visitor: (SyntaxNode) -> Unit) {
        visitor(this)
        children.forEach { it.walk(visitor) }
    }
    
    /**
     * 深度优先遍历，带深度信息
     */
    fun walkWithDepth(depth: Int = 0, visitor: (SyntaxNode, Int) -> Unit) {
        visitor(this, depth)
        children.forEach { it.walkWithDepth(depth + 1, visitor) }
    }
    
    /**
     * 查找满足条件的第一个后代节点
     */
    fun find(predicate: (SyntaxNode) -> Boolean): SyntaxNode? {
        if (predicate(this)) return this
        for (child in children) {
            child.find(predicate)?.let { return it }
        }
        return null
    }
    
    /**
     * 查找所有满足条件的后代节点
     */
    fun findAll(predicate: (SyntaxNode) -> Boolean): List<SyntaxNode> {
        val result = mutableListOf<SyntaxNode>()
        walk { if (predicate(it)) result.add(it) }
        return result
    }
    
    /**
     * 按类型查找后代节点
     */
    fun findByType(type: String): List<SyntaxNode> = findAll { it.type == type }
    
    /**
     * S-expression 表示
     */
    override fun toString(): String {
        return try {
            tree.checkNotClosed()
            native.toString()
        } catch (e: IllegalStateException) {
            "(closed)"
        }
    }
    
    /**
     * 获取底层 TSNode（供 sora-editor 集成使用）
     */
    internal fun toTSNode(): TSNode = native
    
    /**
     * 获取关联的语法树（供内部使用）
     */
    internal fun getTree(): SyntaxTree = tree
    
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is SyntaxNode) return false
        return native == other.native
    }
    
    override fun hashCode(): Int = native.hashCode()
}
