package com.wuxianggujun.tinaide.treesitter.api

/**
 * 查询匹配结果
 */
data class QueryMatch(
    /** 匹配 ID */
    val id: Int,
    /** 模式索引 */
    val patternIndex: Int,
    /** 捕获列表 */
    val captures: List<QueryCapture>
) {
    /**
     * 按名称获取捕获
     */
    fun capture(name: String): QueryCapture? = captures.find { it.name == name }
    
    /**
     * 按名称获取所有捕获
     */
    fun capturesNamed(name: String): List<QueryCapture> = captures.filter { it.name == name }
    
    /**
     * 按索引获取捕获
     */
    fun capture(index: Int): QueryCapture? = captures.getOrNull(index)
}

/**
 * 查询捕获
 */
data class QueryCapture(
    /** 捕获的节点 */
    val node: SyntaxNode,
    /** 捕获索引 */
    val index: Int,
    /** 捕获名称（如 "@function.name"） */
    val name: String
) {
    /** 节点类型 */
    val type: String get() = node.type
    
    /** 起始字节 */
    val startByte: Int get() = node.startByte
    
    /** 结束字节 */
    val endByte: Int get() = node.endByte
}
