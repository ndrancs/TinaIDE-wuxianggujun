package com.wuxianggujun.tinaide.settings

import android.os.Bundle
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.SwitchPreferenceCompat
import com.wuxianggujun.tinaide.R
import com.wuxianggujun.tinaide.core.ServiceLocator
import com.wuxianggujun.tinaide.core.config.IConfigManager
import com.wuxianggujun.tinaide.core.config.Prefs
import com.wuxianggujun.tinaide.core.get
import com.wuxianggujun.tinaide.extensions.toast
import com.wuxianggujun.tinaide.extensions.toastInfo
import com.wuxianggujun.tinaide.utils.Logger

/**
 * 外观设置 Fragment。
 *
 * 管理应用主题、沉浸式状态栏等 UI 相关配置。
 */
class AppearancePreferenceFragment : PreferenceFragmentCompat() {

    private val configManager: IConfigManager by lazy {
        ServiceLocator.get<IConfigManager>()
    }

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        setPreferencesFromResource(R.xml.appearance_preferences, rootKey)

        setupThemePreference()
        setupStatusBarPreference()
    }

    override fun onResume() {
        super.onResume()
        Logger.i("AppearancePreferenceFragment onResume, activity=$activity", tag = "Settings")
        (activity as? SettingsActivity)?.updateTitle("外观")
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
                requireContext().toastInfo("主题将在重启应用后生效")
                true
            }
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
