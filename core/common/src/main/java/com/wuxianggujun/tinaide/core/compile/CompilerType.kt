package com.wuxianggujun.tinaide.core.compile

import android.content.Context
import androidx.annotation.StringRes
import com.wuxianggujun.tinaide.core.i18n.Strings

/**
 * 编译器类型
 *
 * 定义 TinaIDE 支持的编译器类型，用于在运行配置中选择使用哪个编译器。
 *
 * @property displayNameResId 字符串资源 ID（用于国际化）
 */
enum class CompilerType(
    @param:StringRes private val displayNameResId: Int
) {
    /**
     * Clang 编译器（LLVM 项目）
     * - 现代 C/C++ 编译器
     * - 诊断信息更友好
     * - 编译速度较快
     * - 与 LLDB 调试器配合最佳
     */
    CLANG(
        displayNameResId = Strings.compiler_type_clang
    ),

    /**
     * GCC 编译器（GNU Compiler Collection）
     * - 经典的 GNU 编译器
     * - 兼容性最好
     * - 支持更多平台和架构
     * - 与 GDB 调试器配合最佳
     */
    GCC(
        displayNameResId = Strings.compiler_type_gcc
    ),

    /**
     * 自定义编译器
     *
     * - 由用户在运行配置中指定编译器路径
     * - 路径应为 PRoot guest rootfs 内可执行文件路径（如 /usr/bin/clang 或 /usr/local/bin/gcc）
     */
    CUSTOM(
        displayNameResId = Strings.compiler_type_custom
    );

    /**
     * 获取本地化的显示名称
     */
    fun getDisplayName(context: Context): String = context.getString(displayNameResId)

    /**
     * 获取编译器的简短标识符（用于日志、配置文件等）
     */
    fun getId(): String = name.lowercase()

    companion object {
        /**
         * 从字符串 ID 获取编译器类型
         *
         * @param id 编译器 ID（"clang" 或 "gcc"）
         * @return 对应的 CompilerType，未找到则返回 CLANG
         */
        fun fromId(id: String): CompilerType = values().find { it.getId() == id.lowercase() } ?: CLANG
    }
}
