package com.wuxianggujun.tinaide.settings

import android.os.Bundle
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.SeekBarPreference
import androidx.preference.SwitchPreferenceCompat
import com.wuxianggujun.tinaide.R
import com.wuxianggujun.tinaide.core.config.Prefs
import com.wuxianggujun.tinaide.extensions.toast
import com.wuxianggujun.tinaide.utils.Logger

/**
 * 编辑器设置 Fragment。
 *
 * 管理字体大小、Tab 大小、行号、自动缩进、自动换行等编辑器相关配置。
 */
class EditorPreferenceFragment : PreferenceFragmentCompat() {

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        setPreferencesFromResource(R.xml.editor_preferences, rootKey)

        setupFontSizePreference()
        setupLineNumbersPreference()
        setupAutoIndentPreference()
        setupTabSizePreference()
        setupWordWrapPreference()
    }

    override fun onResume() {
        super.onResume()
        // 更新设置页 Toolbar 标题为当前分组名称
        Logger.i("EditorPreferenceFragment onResume, activity=$activity", tag = "Settings")
        (activity as? SettingsActivity)?.updateTitle("编辑器")
    }

    private fun setupFontSizePreference() {
        findPreference<SeekBarPreference>("editor_font_size")?.apply {
            value = Prefs.editorFontSize.toInt()
            setOnPreferenceChangeListener { _, newValue ->
                val fontSize = (newValue as Int).toFloat()
                Prefs.setEditorFontSize(fontSize)
                true
            }
        }
    }

    private fun setupLineNumbersPreference() {
        findPreference<SwitchPreferenceCompat>("editor_line_numbers")?.apply {
            isChecked = Prefs.editorShowLineNumbers
            setOnPreferenceChangeListener { _, newValue ->
                val enabled = newValue as Boolean
                Prefs.setEditorShowLineNumbers(enabled)
                requireContext().toast(
                    if (enabled) "已启用行号显示" else "已禁用行号显示"
                )
                true
            }
        }
    }

    private fun setupAutoIndentPreference() {
        findPreference<SwitchPreferenceCompat>("editor_auto_indent")?.apply {
            isChecked = Prefs.editorAutoIndent
            setOnPreferenceChangeListener { _, newValue ->
                val enabled = newValue as Boolean
                Prefs.setEditorAutoIndent(enabled)
                true
            }
        }
    }

    private fun setupTabSizePreference() {
        findPreference<Preference>("editor_tab_size")?.apply {
            summary = "${Prefs.editorTabSize} 空格"
            setOnPreferenceChangeListener { preference, newValue ->
                val tabSize = (newValue as String).toIntOrNull() ?: return@setOnPreferenceChangeListener false
                Prefs.setEditorTabSize(tabSize)
                preference.summary = "$tabSize 空格"
                true
            }
        }
    }

    private fun setupWordWrapPreference() {
        findPreference<SwitchPreferenceCompat>("editor_word_wrap")?.apply {
            isChecked = Prefs.editorWordWrap
            setOnPreferenceChangeListener { _, newValue ->
                val enabled = newValue as Boolean
                Prefs.setEditorWordWrap(enabled)
                true
            }
        }
    }
}
