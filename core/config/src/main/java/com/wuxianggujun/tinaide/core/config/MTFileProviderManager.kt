package com.wuxianggujun.tinaide.core.config

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import timber.log.Timber

/**
 * MT 文件提供器管理器
 *
 * 负责根据用户设置动态启用/禁用 MT 管理器文件提供器。
 */
object MTFileProviderManager {

    private const val TAG = "MTFileProviderManager"

    /**
     * 初始化 MT 文件提供器
     * 根据配置决定是否启用
     */
    fun initialize(context: Context, configManager: IConfigManager) {
        val enabled = configManager.get(ConfigKeys.MTFileProviderEnabled)
        setProviderEnabled(context, enabled)
    }

    /**
     * 设置 MT 文件提供器的启用状态
     *
     * @param context 上下文
     * @param enabled 是否启用
     */
    fun setProviderEnabled(context: Context, enabled: Boolean) {
        val packageManager = context.packageManager
        val packageName = context.packageName

        // Provider 组件
        val providerComponent = ComponentName(
            packageName,
            "$packageName.provider.MTDataFilesProvider"
        )

        // WakeUp Activity 组件
        val activityComponent = ComponentName(
            packageName,
            "$packageName.provider.MTDataFilesWakeUpActivity"
        )

        val newState = if (enabled) {
            PackageManager.COMPONENT_ENABLED_STATE_ENABLED
        } else {
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED
        }

        try {
            // 设置 Provider 状态
            packageManager.setComponentEnabledSetting(
                providerComponent,
                newState,
                PackageManager.DONT_KILL_APP
            )

            // 设置 Activity 状态
            packageManager.setComponentEnabledSetting(
                activityComponent,
                newState,
                PackageManager.DONT_KILL_APP
            )

            Timber.tag(TAG).i("MT File Provider ${if (enabled) "enabled" else "disabled"}")
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Failed to set MT File Provider state")
        }
    }

    /**
     * 检查 MT 文件提供器是否已启用
     */
    fun isProviderEnabled(context: Context): Boolean {
        val packageManager = context.packageManager
        val packageName = context.packageName

        val providerComponent = ComponentName(
            packageName,
            "$packageName.provider.MTDataFilesProvider"
        )

        return try {
            val state = packageManager.getComponentEnabledSetting(providerComponent)
            state == PackageManager.COMPONENT_ENABLED_STATE_ENABLED
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Failed to check MT File Provider state")
            false
        }
    }
}
