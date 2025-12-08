package com.wuxianggujun.tinaide.settings

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import androidx.activity.result.contract.ActivityResultContracts
import androidx.preference.EditTextPreference
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.SwitchPreferenceCompat
import com.wuxianggujun.tinaide.R
import com.wuxianggujun.tinaide.core.config.Prefs
import com.wuxianggujun.tinaide.extensions.toast
import com.wuxianggujun.tinaide.utils.Logger
import java.io.File

/**
 * 编辑器设置 Fragment。
 *
 * 管理字体大小、Tab 大小、行号、自动缩进、自动换行等编辑器相关配置。
 */
class EditorPreferenceFragment : PreferenceFragmentCompat() {

    private val fontPickerLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            result.data?.data?.let { uri ->
                handleFontSelected(uri)
            }
        }
    }

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        setPreferencesFromResource(R.xml.editor_preferences, rootKey)

        Logger.i("EditorPreferenceFragment onCreatePreferences", tag = "Settings")
        setupFontPathPreference()
        setupFontSizePreference()
        setupLineNumbersPreference()
        setupAutoIndentPreference()
        setupTabSizePreference()
        setupWordWrapPreference()
    }

    private fun setupFontSizePreference() {
        findPreference<EditTextPreference>("editor_font_size")?.apply {
            // 设置当前值
            text = Prefs.editorFontSize.toInt().toString()
            summary = "当前: ${Prefs.editorFontSize.toInt()} sp (范围: 8-72)"
            
            // 设置输入类型为数字
            setOnBindEditTextListener { editText ->
                editText.inputType = android.text.InputType.TYPE_CLASS_NUMBER
                editText.setSelection(editText.text.length)
            }
            
            setOnPreferenceChangeListener { preference, newValue ->
                val input = newValue as String
                val fontSize = input.toIntOrNull()
                
                when {
                    fontSize == null -> {
                        requireContext().toast("请输入有效的数字")
                        false
                    }
                    fontSize < 8 -> {
                        requireContext().toast("字体大小不能小于 8 sp")
                        false
                    }
                    fontSize > 72 -> {
                        requireContext().toast("字体大小不能大于 72 sp")
                        false
                    }
                    else -> {
                        Prefs.setEditorFontSize(fontSize.toFloat())
                        preference.summary = "当前: $fontSize sp (范围: 8-72)"
                        true
                    }
                }
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

    private fun setupFontPathPreference() {
        findPreference<Preference>("editor_font_path")?.apply {
            updateFontSummary(this)
            setOnPreferenceClickListener {
                showFontOptions()
                true
            }
        }
    }

    private fun updateFontSummary(preference: Preference) {
        val fontPath = Prefs.editorFontPath
        preference.summary = if (fontPath.isEmpty()) {
            "使用默认字体"
        } else {
            File(fontPath).name
        }
    }

    private fun showFontOptions() {
        val items = arrayOf("使用默认字体", "选择字体文件...")
        androidx.appcompat.app.AlertDialog.Builder(requireContext())
            .setTitle("自定义字体")
            .setItems(items) { _, which ->
                when (which) {
                    0 -> {
                        Prefs.setEditorFontPath("")
                        findPreference<Preference>("editor_font_path")?.let { updateFontSummary(it) }
                        requireContext().toast("已恢复默认字体")
                    }
                    1 -> openFontPicker()
                }
            }
            .show()
    }

    private fun openFontPicker() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("font/ttf", "font/otf", "application/x-font-ttf", "application/x-font-otf"))
        }
        fontPickerLauncher.launch(intent)
    }

    private fun handleFontSelected(uri: android.net.Uri) {
        try {
            // 复制字体到应用私有目录
            val context = requireContext()
            val fontsDir = File(context.filesDir, "fonts").apply { mkdirs() }
            val fileName = uri.lastPathSegment?.substringAfterLast('/') ?: "custom_font.ttf"
            val destFile = File(fontsDir, fileName)

            context.contentResolver.openInputStream(uri)?.use { input ->
                destFile.outputStream().use { output ->
                    input.copyTo(output)
                }
            }

            Prefs.setEditorFontPath(destFile.absolutePath)
            findPreference<Preference>("editor_font_path")?.let { updateFontSummary(it) }
            context.toast("字体已设置: ${destFile.name}")
        } catch (e: Exception) {
            Logger.e("Failed to copy font file", e, tag = "Settings")
            requireContext().toast("字体设置失败: ${e.message}")
        }
    }
}
