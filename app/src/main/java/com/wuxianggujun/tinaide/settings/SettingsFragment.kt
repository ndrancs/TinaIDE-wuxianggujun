package com.wuxianggujun.tinaide.settings

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.preference.ListPreference
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.SeekBarPreference
import androidx.preference.SwitchPreferenceCompat
import com.wuxianggujun.tinaide.R
import com.wuxianggujun.tinaide.core.ServiceLocator
import com.wuxianggujun.tinaide.core.config.IConfigManager
import com.wuxianggujun.tinaide.core.get

/**
 * 设置界面 Fragment
 * 使用 AndroidX Preference，完全遵循 Material Design
 */
class SettingsFragment : PreferenceFragmentCompat() {

    private val configManager: IConfigManager by lazy {
        ServiceLocator.get<IConfigManager>()
    }

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        setPreferencesFromResource(R.xml.preferences, rootKey)
        
        setupPreferences()
    }

    private fun setupPreferences() {
        // 编辑器设置
        setupEditorPreferences()
        
        // 编译器设置
        setupCompilerPreferences()
        
        // 项目设置
        setupProjectPreferences()
        
        // 外观设置
        setupAppearancePreferences()
        
        // 关于
        setupAboutPreferences()
    }

    private fun setupEditorPreferences() {
        // 字体大小
        findPreference<SeekBarPreference>("editor_font_size")?.apply {
            setOnPreferenceChangeListener { _, newValue ->
                summary = "${newValue}sp"
                true
            }
            summary = "${value}sp"
        }

        // 行号显示
        findPreference<SwitchPreferenceCompat>("editor_line_numbers")?.apply {
            setOnPreferenceChangeListener { _, newValue ->
                Toast.makeText(requireContext(), 
                    if (newValue as Boolean) "已启用行号显示" else "已禁用行号显示", 
                    Toast.LENGTH_SHORT).show()
                true
            }
        }

        // Tab 大小
        findPreference<ListPreference>("editor_tab_size")?.apply {
            setOnPreferenceChangeListener { _, newValue ->
                summary = "${newValue} 空格"
                true
            }
            summary = "${value} 空格"
        }

        // 编辑器主题
        findPreference<ListPreference>("editor_theme")?.apply {
            setOnPreferenceChangeListener { _, newValue ->
                entry?.let { summary = it.toString() }
                Toast.makeText(requireContext(), "主题将在重启应用后生效", Toast.LENGTH_SHORT).show()
                true
            }
            entry?.let { summary = it.toString() }
        }
    }

    private fun setupCompilerPreferences() {
        // 优化级别
        findPreference<ListPreference>("compiler_optimization")?.apply {
            setOnPreferenceChangeListener { _, _ ->
                entry?.let { summary = it.toString() }
                true
            }
            entry?.let { summary = it.toString() }
        }

        // 编译线程数
        findPreference<SeekBarPreference>("compiler_threads")?.apply {
            setOnPreferenceChangeListener { _, newValue ->
                summary = "${newValue} 线程"
                true
            }
            summary = "${value} 线程"
        }
    }

    private fun setupProjectPreferences() {
        // 默认项目路径
        findPreference<Preference>("project_default_path")?.apply {
            setOnPreferenceClickListener {
                Toast.makeText(requireContext(), "选择项目路径功能开发中", Toast.LENGTH_SHORT).show()
                true
            }
            // 从配置中读取当前路径
            val currentPath = configManager.get("project.root_dir", "/storage/emulated/0/TinaIDE/Projects")
            summary = currentPath
        }

        // 自动保存间隔
        findPreference<ListPreference>("project_auto_save")?.apply {
            setOnPreferenceChangeListener { _, newValue ->
                val interval = newValue.toString().toInt()
                summary = when (interval) {
                    0 -> "已关闭"
                    30 -> "30 秒"
                    60 -> "60 秒"
                    300 -> "5 分钟"
                    else -> "${interval} 秒"
                }
                true
            }
            // 初始化 summary
            val interval = value.toInt()
            summary = when (interval) {
                0 -> "已关闭"
                30 -> "30 秒"
                60 -> "60 秒"
                300 -> "5 分钟"
                else -> "${interval} 秒"
            }
        }
    }

    private fun setupAppearancePreferences() {
        // 应用主题
        findPreference<ListPreference>("app_theme")?.apply {
            setOnPreferenceChangeListener { _, _ ->
                entry?.let { summary = it.toString() }
                Toast.makeText(requireContext(), "主题将在重启应用后生效", Toast.LENGTH_SHORT).show()
                true
            }
            entry?.let { summary = it.toString() }
        }

        // 沉浸式状态栏
        findPreference<SwitchPreferenceCompat>("statusbar_immersive")?.apply {
            setOnPreferenceChangeListener { _, newValue ->
                Toast.makeText(requireContext(), 
                    if (newValue as Boolean) "已启用沉浸式状态栏" else "已禁用沉浸式状态栏", 
                    Toast.LENGTH_SHORT).show()
                // 需要重启应用才能生效
                true
            }
        }
    }

    private fun setupAboutPreferences() {
        // 版本信息
        findPreference<Preference>("about_version")?.apply {
            try {
                val packageInfo = requireContext().packageManager.getPackageInfo(
                    requireContext().packageName, 0
                )
                summary = "版本 ${packageInfo.versionName} (${packageInfo.longVersionCode})"
            } catch (e: Exception) {
                summary = "版本信息获取失败"
            }
            
            setOnPreferenceClickListener {
                // 可以在这里显示更详细的版本信息对话框
                Toast.makeText(requireContext(), "TinaIDE - Android C/C++ IDE", Toast.LENGTH_SHORT).show()
                true
            }
        }

        // GitHub 链接（已在 XML 中配置 Intent，这里可以添加额外逻辑）
        findPreference<Preference>("about_github")?.apply {
            setOnPreferenceClickListener {
                // Intent 会自动触发，这里可以添加统计等逻辑
                true
            }
        }

        // 开源许可
        findPreference<Preference>("about_licenses")?.apply {
            setOnPreferenceClickListener {
                // TODO: 显示开源许可对话框或跳转到许可页面
                Toast.makeText(requireContext(), "开源许可功能开发中", Toast.LENGTH_SHORT).show()
                true
            }
        }
    }
}
