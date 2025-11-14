package com.wuxianggujun.tinaide.settings

import android.os.Bundle
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.SeekBarPreference
import androidx.preference.SwitchPreferenceCompat
import com.wuxianggujun.tinaide.R
import com.wuxianggujun.tinaide.core.ServiceLocator
import com.wuxianggujun.tinaide.core.config.IConfigManager
import com.wuxianggujun.tinaide.core.get
import com.wuxianggujun.tinaide.utils.Logger

/**
 * 编译器设置 Fragment。
 *
 * 管理优化级别、目标架构、编译线程数、调试符号等编译器相关配置。
 */
class CompilerPreferenceFragment : PreferenceFragmentCompat() {

    private val configManager: IConfigManager by lazy {
        ServiceLocator.get<IConfigManager>()
    }

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        setPreferencesFromResource(R.xml.compiler_preferences, rootKey)

        setupOptimizationPreference()
        setupTargetArchPreference()
        setupThreadsPreference()
        setupDebugSymbolsPreference()
    }

    override fun onResume() {
        super.onResume()
        Logger.i("CompilerPreferenceFragment onResume, activity=$activity", tag = "Settings")
        (activity as? SettingsActivity)?.updateTitle("编译器")
    }

    private fun setupOptimizationPreference() {
        findPreference<Preference>("compiler_optimization")?.apply {
            // 优化级别从 SharedPreferences 中读取，默认 O2
            setOnPreferenceChangeListener { preference, newValue ->
                val level = newValue as String
                // 更新 summary 显示当前选择
                val entries = resources.getStringArray(R.array.optimization_entries)
                val values = resources.getStringArray(R.array.optimization_values)
                val index = values.indexOf(level)
                if (index >= 0) {
                    preference.summary = entries[index]
                }
                true
            }
        }
    }

    private fun setupTargetArchPreference() {
        findPreference<Preference>("compiler_target_arch")?.apply {
            // 目标架构为多选，AndroidX Preference 会自动处理
            setOnPreferenceChangeListener { preference, newValue ->
                @Suppress("UNCHECKED_CAST")
                val selected = newValue as Set<String>
                preference.summary = if (selected.isEmpty()) {
                    "未选择任何架构"
                } else {
                    selected.joinToString(", ")
                }
                true
            }
        }
    }

    private fun setupThreadsPreference() {
        findPreference<SeekBarPreference>("compiler_threads")?.apply {
            value = configManager.get("compiler.threads", 2)
            setOnPreferenceChangeListener { _, newValue ->
                val threads = newValue as Int
                configManager.set("compiler.threads", threads.toString())
                true
            }
        }
    }

    private fun setupDebugSymbolsPreference() {
        findPreference<SwitchPreferenceCompat>("compiler_debug_symbols")?.apply {
            isChecked = configManager.get("compiler.debugSymbols", false)
            setOnPreferenceChangeListener { _, newValue ->
                val enabled = newValue as Boolean
                configManager.set("compiler.debugSymbols", enabled)
                true
            }
        }
    }
}
