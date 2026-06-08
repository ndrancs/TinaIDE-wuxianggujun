package com.wuxianggujun.tinaide.editor

/**
 * 搜索状态数据类
 *
 * 用于管理编辑器搜索功能的状态
 */
data class SearchState(
    /** 搜索框是否激活显示 */
    val isActive: Boolean = false,
    /** 搜索关键词 */
    val query: String = "",
    /** 是否区分大小写 */
    val caseSensitive: Boolean = false,
    /** 是否启用正则 */
    val useRegex: Boolean = false,
    /** 是否全词匹配 */
    val wholeWord: Boolean = false,
    /** 匹配结果数量 */
    val matchCount: Int = 0,
    /** 当前高亮的匹配索引（从0开始，-1表示无匹配） */
    val currentIndex: Int = -1
) {
    /** 是否有匹配结果 */
    val hasMatches: Boolean get() = matchCount > 0

    /** 显示用的索引（从1开始） */
    val displayIndex: Int get() = if (currentIndex >= 0) currentIndex + 1 else 0
}
