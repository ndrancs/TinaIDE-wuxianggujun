package com.wuxianggujun.tinaide.settings

import android.os.Bundle
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.SwitchPreferenceCompat
import com.wuxianggujun.tinaide.R
import com.wuxianggujun.tinaide.core.ServiceLocator
import com.wuxianggujun.tinaide.core.config.ConfigKeys
import com.wuxianggujun.tinaide.core.config.IConfigManager
import com.wuxianggujun.tinaide.core.get
import com.wuxianggujun.tinaide.extensions.toastInfo
import com.wuxianggujun.tinaide.utils.Logger

/**
 * 项目设置 Fragment。
 *
 * 管理默认项目路径、自动保存间隔、自动备份等项目相关配置。
 */
class ProjectPreferenceFragment : PreferenceFragmentCompat() {

    private val configManager: IConfigManager by lazy {
        ServiceLocator.get<IConfigManager>()
    }

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        setPreferencesFromResource(R.xml.project_preferences, rootKey)

        setupDefaultPathPreference()
        setupAutoSavePreference()
        setupBackupPreference()
    }

    override fun onResume() {
        super.onResume()
        Logger.i("ProjectPreferenceFragment onResume, activity=$activity", tag = "Settings")
        (activity as? SettingsActivity)?.updateTitle("项目")
    }

    private fun setupDefaultPathPreference() {
        findPreference<Preference>("project_default_path")?.apply {
            summary = configManager.get(ConfigKeys.ProjectRootDir)
            setOnPreferenceClickListener {
                requireContext().toastInfo("选择项目路径功能待实现")
                true
            }
        }
    }

    private fun setupAutoSavePreference() {
        findPreference<Preference>("project_auto_save")?.apply {
            val currentValue = configManager.get("project.autoSave", "60")
            summary = getAutoSaveSummary(currentValue)

            setOnPreferenceChangeListener { preference, newValue ->
                val interval = newValue as String
                configManager.set("project.autoSave", interval)
                preference.summary = getAutoSaveSummary(interval)
                true
            }
        }
    }

    private fun setupBackupPreference() {
        findPreference<SwitchPreferenceCompat>("project_backup")?.apply {
            isChecked = configManager.get("project.backup", true)
            setOnPreferenceChangeListener { _, newValue ->
                val enabled = newValue as Boolean
                configManager.set("project.backup", enabled)
                true
            }
        }
    }

    private fun getAutoSaveSummary(value: String): String {
        return when (value) {
            "0" -> "已禁用"
            "30" -> "30 秒"
            "60" -> "1 分钟"
            "120" -> "2 分钟"
            "300" -> "5 分钟"
            else -> "$value 秒"
        }
    }
}
