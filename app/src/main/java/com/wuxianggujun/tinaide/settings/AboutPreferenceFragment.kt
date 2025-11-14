package com.wuxianggujun.tinaide.settings

import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import com.wuxianggujun.tinaide.R
import com.wuxianggujun.tinaide.extensions.toastInfo
import com.wuxianggujun.tinaide.utils.Logger

/**
 * 关于页面 Fragment。
 *
 * 显示版本信息、GitHub 链接、开源许可等。
 */
class AboutPreferenceFragment : PreferenceFragmentCompat() {

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        setPreferencesFromResource(R.xml.about_preferences, rootKey)

        setupVersionPreference()
        setupLicensesPreference()
    }

    override fun onResume() {
        super.onResume()
        Logger.i("AboutPreferenceFragment onResume, activity=$activity", tag = "Settings")
        (activity as? SettingsActivity)?.updateTitle("关于")
    }

    private fun setupVersionPreference() {
        findPreference<Preference>("about_version")?.apply {
            summary = try {
                val packageInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    requireContext().packageManager.getPackageInfo(
                        requireContext().packageName,
                        PackageManager.PackageInfoFlags.of(0)
                    )
                } else {
                    @Suppress("DEPRECATION")
                    requireContext().packageManager.getPackageInfo(
                        requireContext().packageName,
                        0
                    )
                }
                "版本 ${packageInfo.versionName} (${packageInfo.longVersionCode})"
            } catch (e: Exception) {
                "版本信息获取失败"
            }

            setOnPreferenceClickListener {
                requireContext().toastInfo("TinaIDE - Android C/C++ IDE")
                true
            }
        }
    }

    private fun setupLicensesPreference() {
        findPreference<Preference>("about_licenses")?.apply {
            setOnPreferenceClickListener {
                requireContext().toastInfo("开源许可功能开发中")
                true
            }
        }
    }
}
