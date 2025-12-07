package com.wuxianggujun.tinaide.treesitter.api

import com.wuxianggujun.tinaide.treesitter.TSLanguage

/**
 * Tree-sitter Kotlin 扩展函数
 * 
 * 提供更 Kotlin 化的 API 使用方式
 */

// ============================================================================
// TSLanguage 扩展
// ============================================================================

/**
 * 使用此语言解析源代码
 * 
 * ```kotlin
 * TSLanguageCpp.getInstance().parse(sourceCode) { tree ->
 *     println(tree.root.type)
 * }
 * ```
 */
inline fun <R> TSLanguage.parse(source: String, block: (SyntaxTree) -> R): R {
    return TreeSitter.parse(this, source, block)
}

/**
 * 创建此语言的查询
 */
fun TSLanguage.createQuery(source: String): QueryCreateResult {
    return Query.create(this, source)
}

/**
 * 创建此语言的查询，失败时抛出异常
 */
fun TSLanguage.createQueryOrThrow(source: String): Query {
    return Query.createOrThrow(this, source)
}

// ============================================================================
// SyntaxNode 扩展
// ============================================================================

/**
 * 获取节点在源代码中的文本
 * 
 * @param source 完整的源代码
 */
fun SyntaxNode.text(source: String): String {
    val start = startByte.coerceIn(0, source.length)
    val end = endByte.coerceIn(start, source.length)
    return source.substring(start, end)
}

/**
 * 获取节点在源代码中的文本（UTF-16 字节偏移）
 * 
 * 注意：tree-sitter 使用 UTF-8 字节偏移，但 Kotlin String 是 UTF-16
 * 如果源代码只包含 ASCII 字符，可以直接使用 text(source)
 * 否则需要使用此方法进行正确的转换
 */
fun SyntaxNode.textUtf16(source: String, utf8ToUtf16: (Int) -> Int): String {
    val start = utf8ToUtf16(startByte).coerceIn(0, source.length)
    val end = utf8ToUtf16(endByte).coerceIn(start, source.length)
    return source.substring(start, end)
}

/**
 * 检查节点是否在指定范围内
 */
fun SyntaxNode.isInRange(startLine: Int, startColumn: Int, endLine: Int, endColumn: Int): Boolean {
    val sp = startPoint
    val ep = endPoint
    return sp.row >= startLine && ep.row <= endLine &&
           (sp.row > startLine || sp.column >= startColumn) &&
           (ep.row < endLine || ep.column <= endColumn)
}

/**
 * 获取节点的所有祖先节点（从父节点到根节点）
 */
val SyntaxNode.ancestors: Sequence<SyntaxNode>
    get() = generateSequence(parent) { it.parent }

/**
 * 获取节点的路径（从根节点到当前节点）
 */
val SyntaxNode.path: List<SyntaxNode>
    get() = ancestors.toList().reversed() + this

/**
 * 查找最近的满足条件的祖先节点
 */
fun SyntaxNode.findAncestor(predicate: (SyntaxNode) -> Boolean): SyntaxNode? {
    return ancestors.find(predicate)
}

/**
 * 查找最近的指定类型的祖先节点
 */
fun SyntaxNode.findAncestorByType(type: String): SyntaxNode? {
    return findAncestor { it.type == type }
}

// ============================================================================
// SyntaxTree 扩展
// ============================================================================

/**
 * 在树上执行查询
 */
fun SyntaxTree.query(querySource: String): Sequence<QueryMatch> {
    val query = Query.createOrThrow(language, querySource)
    return query.execute(root)
}

/**
 * 在树上执行查询，使用回调
 */
inline fun SyntaxTree.query(querySource: String, onMatch: (QueryMatch) -> Unit) {
    Query.createOrThrow(language, querySource).use { query ->
        query.execute(root, onMatch = onMatch)
    }
}

// ============================================================================
// Query 扩展
// ============================================================================

/**
 * 在语法树上执行查询
 */
fun Query.execute(tree: SyntaxTree): Sequence<QueryMatch> = execute(tree.root)

// ============================================================================
// 便捷构建器
// ============================================================================

/**
 * 构建 UTF-8 到 UTF-16 的偏移映射
 * 
 * 用于正确处理包含非 ASCII 字符的源代码
 */
class Utf8ToUtf16Mapper(source: String) {
    private val mapping: IntArray
    
    init {
        val bytes = source.toByteArray(Charsets.UTF_8)
        mapping = IntArray(bytes.size + 1)
        var utf16Index = 0
        var utf8Index = 0
        
        while (utf8Index < bytes.size) {
            mapping[utf8Index] = utf16Index
            val byte = bytes[utf8Index].toInt() and 0xFF
            val charLen = when {
                byte and 0x80 == 0 -> 1      // ASCII
                byte and 0xE0 == 0xC0 -> 2   // 2-byte UTF-8
                byte and 0xF0 == 0xE0 -> 3   // 3-byte UTF-8
                byte and 0xF8 == 0xF0 -> 4   // 4-byte UTF-8
                else -> 1
            }
            utf8Index += charLen
            utf16Index += if (charLen == 4) 2 else 1  // 4-byte UTF-8 = surrogate pair
        }
        mapping[bytes.size] = utf16Index
    }
    
    fun convert(utf8Offset: Int): Int {
        return mapping.getOrElse(utf8Offset.coerceIn(0, mapping.size - 1)) { mapping.last() }
    }
}

/**
 * 创建 UTF-8 到 UTF-16 的映射器
 */
fun String.utf8ToUtf16Mapper(): Utf8ToUtf16Mapper = Utf8ToUtf16Mapper(this)
