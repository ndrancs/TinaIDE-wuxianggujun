package com.wuxianggujun.tinaide.settings

import android.os.Bundle
import androidx.preference.PreferenceFragmentCompat
import com.wuxianggujun.tinaide.R
import com.wuxianggujun.tinaide.utils.Logger

/**
 * 根设置界面 Fragment。
 *
 * 使用 AndroidX Preference 框架，自动加载 root_preferences.xml。
 * 该 Fragment 显示主设置分类，点击后进入子设置界面。
 */
class SettingsFragment : PreferenceFragmentCompat() {

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        setPreferencesFromResource(R.xml.root_preferences, rootKey)
    }

    override fun onResume() {
        super.onResume()
        Logger.i("SettingsFragment onResume, activity=$activity", tag = "Settings")
        (activity as? SettingsActivity)?.updateTitle(getString(R.string.menu_settings))
    }
}
