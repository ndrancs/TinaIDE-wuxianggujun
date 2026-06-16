package com.wuxianggujun.tinaide.plugin

import android.content.Context
import com.wuxianggujun.tinaide.core.linux.LinuxEnvironment
import com.wuxianggujun.tinaide.core.linux.LinuxEnvironmentProvider
import com.wuxianggujun.tinaide.core.linux.UnavailableLinuxEnvironment
import com.wuxianggujun.tinaide.core.proot.PRootEnvironment

/**
 * 由插件 capability 驱动的 Linux 环境提供器。
 */
class PluginLinuxEnvironmentProvider(
    private val context: Context,
    private val pluginManager: PluginManager,
) : LinuxEnvironmentProvider {

    private val prootEnvironment: PRootEnvironment by lazy {
        PRootEnvironment(context.applicationContext)
    }

    override fun get(): LinuxEnvironment = if (pluginManager.hasEnabledCapability(PluginCapabilities.LINUX_ENVIRONMENT)) {
        prootEnvironment
    } else {
        UnavailableLinuxEnvironment
    }
}
