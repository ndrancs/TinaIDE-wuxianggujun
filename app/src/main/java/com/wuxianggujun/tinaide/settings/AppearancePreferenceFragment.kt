package com.wuxianggujun.tinaide.settings

import android.os.Bundle
import androidx.appcompat.app.AppCompatDelegate
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.SwitchPreferenceCompat
import com.wuxianggujun.tinaide.R
import com.wuxianggujun.tinaide.core.ServiceLocator
import com.wuxianggujun.tinaide.core.config.IConfigManager
import com.wuxianggujun.tinaide.core.config.Prefs
import com.wuxianggujun.tinaide.core.get
import com.wuxianggujun.tinaide.extensions.toast
import com.wuxianggujun.tinaide.utils.Logger

/**
 * 外观设置 Fragment。
 *
 * 管理应用主题、沉浸式状态栏等 UI 相关配置。
 * 主题切换使用 AppCompatDelegate.setDefaultNightMode()，会自动触发 Activity 重建，即时生效。
 */
class AppearancePreferenceFragment : PreferenceFragmentCompat() {

    private val configManager: IConfigManager by lazy {
        ServiceLocator.get<IConfigManager>()
    }

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        setPreferencesFromResource(R.xml.appearance_preferences, rootKey)

        Logger.i("AppearancePreferenceFragment onCreatePreferences", tag = "Settings")
        setupThemePreference()
        setupStatusBarPreference()
    }

    private fun setupThemePreference() {
        findPreference<Preference>("app_theme")?.apply {
            // 将 ConfigManager 中的枚举值映射回 preference 值
            val current = when (Prefs.appTheme) {
                "LIGHT" -> "light"
                "AUTO" -> "auto"
                else -> "dark"
            }

            // 设置当前值（对于 ListPreference，需要通过 SharedPreferences）
            sharedPreferences?.edit()?.putString("app_theme", current)?.apply()

            setOnPreferenceChangeListener { _, newValue ->
                val mapped = when (newValue as String) {
                    "light" -> "LIGHT"
                    "auto" -> "AUTO"
                    else -> "DARK"
                }
                Prefs.setTheme(mapped)

                // 即时应用主题，无需重启
                applyThemeImmediately(mapped)
                true
            }
        }
    }

    /**
     * 即时应用主题
     *
     * 使用 AppCompatDelegate.setDefaultNightMode() 切换主题，
     * 系统会自动重建当前 Activity 以应用新主题。
     */
    private fun applyThemeImmediately(themeName: String) {
        val mode = when (themeName) {
            "LIGHT" -> AppCompatDelegate.MODE_NIGHT_NO
            "AUTO" -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
            else -> AppCompatDelegate.MODE_NIGHT_YES
        }

        // 只有当模式实际改变时才设置，避免不必要的重建
        if (AppCompatDelegate.getDefaultNightMode() != mode) {
            AppCompatDelegate.setDefaultNightMode(mode)
            // 主题会自动应用，Activity 会自动重建
        }
    }

    private fun setupStatusBarPreference() {
        findPreference<SwitchPreferenceCompat>("statusbar_immersive")?.apply {
            isChecked = configManager.get("ui.statusbar.immersive", true)
            setOnPreferenceChangeListener { _, newValue ->
                val enabled = newValue as Boolean
                configManager.set("ui.statusbar.immersive", enabled)
                requireContext().toast(
                    if (enabled) "已启用沉浸式状态栏" else "已禁用沉浸式状态栏"
                )
                true
            }
        }
    }
}
