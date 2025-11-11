package com.wuxianggujun.tinaide.settings

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import com.geyifeng.immersionbar.ktx.immersionBar
import com.wuxianggujun.tinaide.R

/**
 * 设置界面 Activity
 * Material Design 风格
 */
class SettingsActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        setTheme(R.style.Theme_TinaIDE)
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        // 沉浸式状态栏
        immersionBar {
            statusBarColorInt(getColor(R.color.dark_primary))
            statusBarDarkFont(false)
            navigationBarColorInt(getColor(R.color.dark_background))
            fitsSystemWindows(true)
            autoStatusBarDarkModeEnable(true)
            init()
        }

        // 设置 Toolbar
        val toolbar = findViewById<Toolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.apply {
            setDisplayHomeAsUpEnabled(true)
            title = "设置"
        }

        // 加载 SettingsFragment
        if (savedInstanceState == null) {
            supportFragmentManager
                .beginTransaction()
                .replace(R.id.settings_container, SettingsFragment())
                .commit()
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }
}
