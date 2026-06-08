package com.wuxianggujun.tinaide.core.linux

/**
 * Linux 运行模式统一策略。
 *
 * 规则：
 * - 用户选择 proot，但 Linux 环境不可用 => 回退 native
 * - 其它场景按配置解析（非法值默认 native）
 */
object LinuxRunModePolicy {

    const val MODE_NATIVE = "native"
    const val MODE_PROOT = "proot"

    enum class RunMode(val value: String) {
        NATIVE(MODE_NATIVE),
        PROOT(MODE_PROOT),
    }

    fun parse(configuredMode: String?): RunMode = if (configuredMode.equals(MODE_PROOT, ignoreCase = true)) {
        RunMode.PROOT
    } else {
        RunMode.NATIVE
    }

    fun resolve(configuredMode: String?, linuxEnvironmentAvailable: Boolean): RunMode {
        val preferred = parse(configuredMode)
        return if (preferred == RunMode.PROOT && linuxEnvironmentAvailable) {
            RunMode.PROOT
        } else {
            RunMode.NATIVE
        }
    }

    fun resolveValue(configuredMode: String?, linuxEnvironmentAvailable: Boolean): String = resolve(configuredMode, linuxEnvironmentAvailable).value
}
