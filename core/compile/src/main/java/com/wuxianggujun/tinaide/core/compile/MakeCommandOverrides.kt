package com.wuxianggujun.tinaide.core.compile

/**
 * Make 构建命令的工具函数。
 *
 * 该对象保持纯函数，便于单元测试覆盖命令拼装逻辑。
 */
internal object MakeCommandOverrides {

    const val DEFAULT_SYSROOT_API_LEVEL: Int = 28
    const val MIN_SYSROOT_API_LEVEL: Int = 21
    const val MAX_SYSROOT_API_LEVEL: Int = 35

    fun isValidSysrootApiLevel(apiLevel: Int): Boolean = apiLevel in MIN_SYSROOT_API_LEVEL..MAX_SYSROOT_API_LEVEL

    fun buildVariableValue(
        shellCommand: String,
        extraArgs: List<String>
    ): String = if (extraArgs.isEmpty()) {
        shellCommand
    } else {
        "$shellCommand ${extraArgs.joinToString(" ")}"
    }

    fun buildVariableAssignment(
        variable: String,
        shellCommand: String,
        extraArgs: List<String> = emptyList()
    ): String {
        val value = buildVariableValue(shellCommand, extraArgs)
        return "$variable=$value"
    }

    fun buildRawVariableAssignment(
        variable: String,
        value: String
    ): String = "$variable=$value"

    data class FlagSplit(
        val compileFlags: List<String>,
        val linkFlags: List<String>
    )

    /**
     * 将编译/链接参数拆分，避免把 `-L` 注入到纯编译阶段（如 `-c`）触发 unused warning。
     */
    fun splitCompileAndLinkFlags(flags: List<String>): FlagSplit {
        if (flags.isEmpty()) {
            return FlagSplit(emptyList(), emptyList())
        }
        val compileFlags = mutableListOf<String>()
        val linkFlags = mutableListOf<String>()
        for (flag in flags) {
            if (flag.startsWith("-L")) {
                linkFlags.add(flag)
            } else {
                compileFlags.add(flag)
            }
        }
        return FlagSplit(
            compileFlags = compileFlags,
            linkFlags = linkFlags
        )
    }
}
