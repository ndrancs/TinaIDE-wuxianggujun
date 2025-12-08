package com.wuxianggujun.tinaide.core.config

import android.content.SharedPreferences
import androidx.preference.PreferenceManager
import com.wuxianggujun.tinaide.TinaApplication
import com.wuxianggujun.tinaide.core.ServiceLocator
import com.wuxianggujun.tinaide.core.get

/**
 * TinaIDE 配置访问门面。
 *
 * 目标：
 * - 给调用方提供简单、类型安全的访问入口（避免到处写字符串 key + 默认值）；
 * - 在内部统一委托给 IConfigManager，集中管理持久化逻辑；
 * - 只暴露真正需要在代码中频繁读取的配置项。
 */
object Prefs {
    private val configManager: IConfigManager by lazy {
        ServiceLocator.get<IConfigManager>()
    }

    // 默认 SharedPreferences（供 modernpreferences / AndroidX Preference 使用）
    private val sharedPrefs: SharedPreferences by lazy {
        PreferenceManager.getDefaultSharedPreferences(TinaApplication.instance)
    }

    // ========== UI / 主题相关 ==========

    /** 应用主题："DARK" / "LIGHT" / "AUTO" */
    val appTheme: String
        get() = configManager.get(ConfigKeys.Theme)

    val useDarkMode: Boolean
        get() = appTheme == "DARK"

    // ========== 编辑器配置 ==========

    /**
     * 编辑器字体大小（sp）。默认 14，范围约束在 [8, 72]。
     * 存储于默认 SharedPreferences 中，键为 "editor_font_size"。
     * 注意：使用 String 存储以兼容 EditTextPreference。
     */
    val editorFontSize: Float
        get() = sharedPrefs.getString("editor_font_size", "14")
            ?.toFloatOrNull()
            ?.coerceIn(8f, 72f) ?: 14f

    /**
     * Tab 宽度（空格数）。默认 4，范围 [2, 8]。
     * 存储为字符串，键为 "editor_tab_size"。
     */
    val editorTabSize: Int
        get() = sharedPrefs.getString("editor_tab_size", "4")
            ?.toIntOrNull()
            ?.coerceIn(2, 8) ?: 4

    /** 是否启用自动换行。 */
    val editorWordWrap: Boolean
        get() = sharedPrefs.getBoolean("editor_word_wrap", false)

    /** 是否显示行号。 */
    val editorShowLineNumbers: Boolean
        get() = sharedPrefs.getBoolean("editor_line_numbers", true)

    /** 是否启用自动缩进。 */
    val editorAutoIndent: Boolean
        get() = sharedPrefs.getBoolean("editor_auto_indent", true)

    /** 
     * 自定义字体路径。空字符串表示使用默认字体。
     * 存储于默认 SharedPreferences 中，键为 "editor_font_path"。
     */
    val editorFontPath: String
        get() = sharedPrefs.getString("editor_font_path", "") ?: ""

    // ========== 编译器配置（按需扩展） ==========

    /** 编译优化等级，例如 "O0" / "O2" 等。 */
    val compilerOptimizationLevel: String
        get() = sharedPrefs.getString("compiler_optimization", "O2") ?: "O2"

    // ========== 简单写入方法（供设置界面或业务调用） ==========

    fun setTheme(theme: String) {
        configManager.set(ConfigKeys.Theme, theme)
    }

    fun setEditorFontSize(sizeSp: Float) {
        sharedPrefs.edit().putString("editor_font_size", sizeSp.toInt().coerceIn(8, 72).toString()).apply()
    }

    fun setEditorTabSize(tabSize: Int) {
        sharedPrefs.edit().putString("editor_tab_size", tabSize.coerceIn(2, 8).toString()).apply()
    }

    fun setEditorWordWrap(enabled: Boolean) {
        sharedPrefs.edit().putBoolean("editor_word_wrap", enabled).apply()
    }

    fun setEditorShowLineNumbers(enabled: Boolean) {
        sharedPrefs.edit().putBoolean("editor_line_numbers", enabled).apply()
    }

    fun setEditorAutoIndent(enabled: Boolean) {
        sharedPrefs.edit().putBoolean("editor_auto_indent", enabled).apply()
    }

    fun setEditorFontPath(path: String) {
        sharedPrefs.edit().putString("editor_font_path", path).apply()
    }

}
