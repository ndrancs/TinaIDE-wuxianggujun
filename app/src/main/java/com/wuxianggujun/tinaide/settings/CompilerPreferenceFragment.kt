package com.wuxianggujun.tinaide.settings

import android.os.Bundle
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.SeekBarPreference
import androidx.preference.SwitchPreferenceCompat
import com.wuxianggujun.tinaide.R
import com.wuxianggujun.tinaide.core.ServiceLocator
import com.wuxianggujun.tinaide.core.config.IConfigManager
import com.wuxianggujun.tinaide.core.config.ConfigKeys
import com.wuxianggujun.tinaide.core.get
import com.wuxianggujun.tinaide.core.nativebridge.SysrootInstaller
import com.wuxianggujun.tinaide.utils.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 编译器设置 Fragment。
 *
 * 管理优化级别、目标架构、编译线程数、调试符号等编译器相关配置。
 */
class CompilerPreferenceFragment : PreferenceFragmentCompat() {

    companion object {
        private const val MIN_COMPLETION_LIMIT = 10
        private const val MAX_COMPLETION_LIMIT = 200
    }

    private val configManager: IConfigManager by lazy {
        ServiceLocator.get<IConfigManager>()
    }

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        setPreferencesFromResource(R.xml.compiler_preferences, rootKey)

        Logger.i("CompilerPreferenceFragment onCreatePreferences", tag = "Settings")
        setupOptimizationPreference()
        setupTargetArchPreference()
        setupThreadsPreference()
        setupDebugSymbolsPreference()
        setupCompletionLimitPreference()
        setupReinstallSysrootPreference()
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

    private fun setupCompletionLimitPreference() {
        findPreference<SeekBarPreference>("lsp_completion_limit")?.apply {
            val current = configManager.get(ConfigKeys.LspCompletionLimit)
                .coerceIn(MIN_COMPLETION_LIMIT, MAX_COMPLETION_LIMIT)
            value = current
            updateCompletionSummary(this, current)
            setOnPreferenceChangeListener { preference, newValue ->
                val limit = (newValue as Int).coerceIn(MIN_COMPLETION_LIMIT, MAX_COMPLETION_LIMIT)
                configManager.set(ConfigKeys.LspCompletionLimit, limit)
                updateCompletionSummary(preference as SeekBarPreference, limit)
                Toast.makeText(
                    requireContext(),
                    getString(R.string.settings_completion_limit_summary),
                    Toast.LENGTH_SHORT
                ).show()
                true
            }
        }
    }

    private fun updateCompletionSummary(preference: Preference, limit: Int) {
        preference.summary = buildString {
            append(getString(R.string.settings_completion_limit_summary_with_value, limit))
            append('\n')
            append(getString(R.string.settings_completion_limit_summary))
        }
    }

    private fun setupReinstallSysrootPreference() {
        findPreference<Preference>("reinstall_sysroot")?.apply {
            setOnPreferenceClickListener {
                reinstallSysroot()
                true
            }
        }
    }

    private fun reinstallSysroot() {
        val ctx = requireContext().applicationContext
        val pref = findPreference<Preference>("reinstall_sysroot")
        
        pref?.isEnabled = false
        pref?.summary = "正在重新安装..."
        
        lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    SysrootInstaller.forceReinstall(ctx)
                }
                pref?.summary = "安装完成！请重启应用以生效"
                Toast.makeText(ctx, "Sysroot 重新安装完成，请重启应用", Toast.LENGTH_LONG).show()
                Logger.i("Sysroot reinstalled successfully", tag = "Settings")
            } catch (e: Exception) {
                pref?.summary = "安装失败: ${e.message}"
                Toast.makeText(ctx, "Sysroot 安装失败: ${e.message}", Toast.LENGTH_LONG).show()
                Logger.e("Failed to reinstall sysroot: ${e.message}", tag = "Settings")
            } finally {
                pref?.isEnabled = true
            }
        }
    }
}
