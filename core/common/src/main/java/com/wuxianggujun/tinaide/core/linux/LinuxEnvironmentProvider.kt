package com.wuxianggujun.tinaide.core.linux

/**
 * Linux 环境提供器。
 */
interface LinuxEnvironmentProvider {
    fun get(): LinuxEnvironment
}

/**
 * 默认不可用实现（用于未注入/未启用场景）。
 */
object UnavailableLinuxEnvironmentProvider : LinuxEnvironmentProvider {
    override fun get(): LinuxEnvironment = UnavailableLinuxEnvironment
}
