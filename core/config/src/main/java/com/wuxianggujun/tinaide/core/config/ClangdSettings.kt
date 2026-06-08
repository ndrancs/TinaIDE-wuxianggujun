package com.wuxianggujun.tinaide.core.config

/**
 * Clangd LSP 服务器配置。
 *
 * 控制 clangd 启动时的各项参数，影响代码补全、静态分析等功能。
 * 通过 StateFlow 暴露，支持热更新（需重新连接 LSP 生效）。
 */
data class ClangdSettings(
    /** 是否启用后台索引（提升跨文件补全质量，但消耗更多资源） */
    val backgroundIndex: Boolean = true,

    /** 是否启用 clang-tidy 静态分析 */
    val clangTidy: Boolean = true,

    /** 头文件插入模式 */
    val headerInsertion: HeaderInsertionMode = HeaderInsertionMode.IWYU,

    /** 补全样式 */
    val completionStyle: CompletionStyle = CompletionStyle.DETAILED,

    /** 是否启用函数参数占位符 */
    val functionArgPlaceholders: Boolean = true,
) {
    /**
     * 头文件插入模式
     */
    enum class HeaderInsertionMode(val value: String) {
        /** 永不自动插入头文件 */
        NEVER("never"),

        /** Include What You Use - 智能插入所需头文件 */
        IWYU("iwyu"),
        ;

        companion object {
            fun fromValue(value: String): HeaderInsertionMode = entries.find { it.value == value } ?: IWYU
        }
    }

    /**
     * 补全样式
     */
    enum class CompletionStyle(val value: String) {
        /** 详细模式 - 显示完整的类型信息和文档 */
        DETAILED("detailed"),

        /** 简洁模式 - 仅显示基本信息 */
        BUNDLED("bundled"),
        ;

        companion object {
            fun fromValue(value: String): CompletionStyle = entries.find { it.value == value } ?: DETAILED
        }
    }

    /**
     * 构建 clangd 命令行参数
     */
    fun buildCommandArgs(): String = buildString {
        // 后台索引
        if (backgroundIndex) {
            append(" --background-index")
        } else {
            append(" --background-index=false")
        }

        // clang-tidy
        if (clangTidy) {
            append(" --clang-tidy")
        } else {
            append(" --clang-tidy=false")
        }

        // 头文件插入
        append(" --header-insertion=${headerInsertion.value}")

        // 补全样式
        append(" --completion-style=${completionStyle.value}")

        // 函数参数占位符（clangd 21+ 需要显式布尔值）
        append(" --function-arg-placeholders=${if (functionArgPlaceholders) "true" else "false"}")
    }
}
