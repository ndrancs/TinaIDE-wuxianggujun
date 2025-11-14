package com.wuxianggujun.tinaide.settings

import android.os.Bundle
import com.wuxianggujun.tinaide.R
import com.wuxianggujun.tinaide.base.BaseActivity
import com.wuxianggujun.tinaide.databinding.ActivitySettingsBinding
import com.wuxianggujun.tinaide.utils.Logger

/**
 * 设置界面 Activity。
 *
 * 使用 AndroidX Preference 框架，加载 SettingsFragment 作为根界面。
 * 支持多层级设置导航。
 */
class SettingsActivity : BaseActivity<ActivitySettingsBinding>(ActivitySettingsBinding::inflate) {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)  // BaseActivity 已处理主题和状态栏

        // 设置 Toolbar
        val toolbar = binding.toolbar
        Logger.i("SettingsActivity onCreate, toolbar=$toolbar", tag = "Settings")
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        // 默认标题：设置
        val initialTitle = getString(R.string.menu_settings)
        toolbar.title = initialTitle
        Logger.i("Initial toolbar title set to: $initialTitle", tag = "Settings")

        // 返回按钮处理
        toolbar.setNavigationOnClickListener {
            onBackPressedDispatcher.onBackPressed()
        }

        // 加载根设置 Fragment
        if (savedInstanceState == null) {
            supportFragmentManager
                .beginTransaction()
                .replace(R.id.settings_container, SettingsFragment())
                .commit()
        }
    }

    /**
     * 供子 Fragment 调用，更新设置页标题。
     */
    fun updateTitle(title: CharSequence) {
        Logger.i("updateTitle() called with: \"$title\"", tag = "Settings")
        Logger.i("Before update: toolbar.title=\"${binding.toolbar.title}\", actionBar.title=\"${supportActionBar?.title}\"", tag = "Settings")
        binding.toolbar.title = title
        supportActionBar?.title = title
        Logger.i("After update: toolbar.title=\"${binding.toolbar.title}\", actionBar.title=\"${supportActionBar?.title}\"", tag = "Settings")
    }

    override fun onSupportNavigateUp(): Boolean {
        onBackPressedDispatcher.onBackPressed()
        return true
    }
}
