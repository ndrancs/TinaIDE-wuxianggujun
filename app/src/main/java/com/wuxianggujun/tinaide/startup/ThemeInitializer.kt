package com.wuxianggujun.tinaide.startup

import android.content.Context
import com.wuxianggujun.tinaide.core.config.Prefs
import com.wuxianggujun.tinaide.core.config.ThemeManager
import timber.log.Timber

/**
 * 主题初始化器
 *
 * 从 SharedPreferences 读取主题配置并初始化 ThemeManager。
 * 直接读取 SharedPreferences（确保 :crash 进程也能正确显示主题）。
 */
class ThemeInitializer(private val context: Context) {

    companion object {
        private const val TAG = "ThemeInitializer"
    }

    fun initialize() {
        try {
            val savedTheme = Prefs.readPersistedTheme(context)
            ThemeManager.initialize(savedTheme)
            Timber.tag(TAG).i("ThemeManager initialized with theme: %s", savedTheme)
        } catch (t: Throwable) {
            Timber.tag(TAG).e(t, "Failed to initialize ThemeManager")
            ThemeManager.initialize("LIGHT")
        }
    }

    fun applyNightMode() {
        try {
            Prefs.applyNightMode(Prefs.readPersistedTheme(context))
        } catch (t: Throwable) {
            Timber.tag(TAG).w(t, "Failed to apply night mode")
        }
    }
}
