package com.wuxianggujun.tinaide.core.config

import android.content.Context
import androidx.annotation.StringRes
import com.wuxianggujun.tinaide.core.i18n.Strings

/**
 * 调试工具栏位置配置
 *
 * 定义调试工具栏的显示位置，允许用户根据个人习惯选择最合适的方式
 */
enum class DebugToolbarPosition(val value: String, @param:StringRes @get:StringRes val displayNameRes: Int) {
    /**
     * 顶部显示
     * - 优点：始终可见，不需要展开底部面板
     * - 缺点：占用顶部空间，可能遮挡编辑器内容
     */
    TOP("top", Strings.debug_toolbar_top),

    /**
     * 底部显示（默认）
     * - 优点：不遮挡编辑器，调试信息和控制在一起
     * - 缺点：需要展开底部面板才能看到
     */
    BOTTOM("bottom", Strings.debug_toolbar_bottom),

    /**
     * 两处都显示
     * - 优点：最大灵活性，任何位置都能操作
     * - 缺点：占用更多空间
     */
    BOTH("both", Strings.debug_toolbar_both);

    /**
     * 获取本地化的显示名称
     */
    fun getDisplayName(context: Context): String = context.getString(displayNameRes)

    companion object {
        /**
         * 从字符串值解析枚举
         */
        fun fromString(value: String): DebugToolbarPosition = entries.find { it.value == value } ?: BOTTOM

        /**
         * 默认值
         */
        val DEFAULT = BOTTOM
    }
}
