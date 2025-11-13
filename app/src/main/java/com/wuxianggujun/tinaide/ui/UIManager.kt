package com.wuxianggujun.tinaide.ui

import android.app.Activity
import android.content.res.Configuration
import android.util.Log
import android.view.View
import androidx.appcompat.app.AppCompatDelegate
import com.wuxianggujun.tinaide.R
import com.wuxianggujun.tinaide.core.ServiceLifecycle
import com.wuxianggujun.tinaide.core.ServiceLocator
import com.wuxianggujun.tinaide.core.config.IConfigManager
import com.wuxianggujun.tinaide.core.config.ConfigKeys
import com.wuxianggujun.tinaide.core.get

/**
 * UI 管理器实现
 * 管理 IDE 的整体 UI 布局和主题
 */
class UIManager(private val activity: Activity) : IUIManager, ServiceLifecycle {
    companion object {
        private const val TAG = "UIManager"
        private const val KEY_THEME = "ui.theme"
        private const val KEY_PANEL_PREFIX = "ui.panel."
    }
    
    private val configManager: IConfigManager by lazy {
        ServiceLocator.get<IConfigManager>()
    }
    
    private val panelVisibility = mutableMapOf<PanelType, Boolean>()
    private var currentTheme: Theme = Theme.DARK
    
    override fun onCreate() {
        // 恢复主题设置
        val themeName = configManager.get(ConfigKeys.Theme)
        currentTheme = try {
            Theme.valueOf(themeName)
        } catch (e: Exception) {
            Theme.DARK
        }
        applyTheme(currentTheme)
        
        // 恢复面板可见性
        PanelType.values().forEach { panel ->
            val key = KEY_PANEL_PREFIX + panel.name
            panelVisibility[panel] = configManager.get(ConfigKeys.panelVisible(panel.name, getDefaultVisibility(panel)))
        }
    }
    
    override fun onDestroy() {
        saveLayoutState()
    }
    
    override fun showPanel(panel: PanelType) {
        if (panelVisibility[panel] == true) return
        
        panelVisibility[panel] = true
        updatePanelView(panel, true)
        
        // 保存状态
        val key = KEY_PANEL_PREFIX + panel.name
        configManager.set(key, true)
    }
    
    override fun hidePanel(panel: PanelType) {
        if (panelVisibility[panel] == false) return
        
        panelVisibility[panel] = false
        updatePanelView(panel, false)
        
        // 保存状态
        val key = KEY_PANEL_PREFIX + panel.name
        configManager.set(key, false)
    }
    
    override fun togglePanel(panel: PanelType) {
        if (isPanelVisible(panel)) {
            hidePanel(panel)
        } else {
            showPanel(panel)
        }
    }
    
    override fun isPanelVisible(panel: PanelType): Boolean {
        return panelVisibility[panel] ?: getDefaultVisibility(panel)
    }
    
    override fun setTheme(theme: Theme) {
        if (currentTheme == theme) return
        
        currentTheme = theme
        applyTheme(theme)
        
        // 保存主题设置
        configManager.set(KEY_THEME, theme.name)
    }
    
    override fun getCurrentTheme(): Theme {
        return currentTheme
    }
    
    override fun saveLayoutState() {
        // 保存主题
        configManager.set(KEY_THEME, currentTheme.name)
        
        // 保存面板可见性
        panelVisibility.forEach { (panel, visible) ->
            val key = KEY_PANEL_PREFIX + panel.name
            configManager.set(key, visible)
        }
    }
    
    override fun restoreLayoutState() {
        // 恢复主题
        val themeName = configManager.get(ConfigKeys.Theme)
        currentTheme = try {
            Theme.valueOf(themeName)
        } catch (e: Exception) {
            Theme.DARK
        }
        applyTheme(currentTheme)
        
        // 恢复面板可见性
        PanelType.values().forEach { panel ->
            val key = KEY_PANEL_PREFIX + panel.name
            val visible = configManager.get(ConfigKeys.panelVisible(panel.name, getDefaultVisibility(panel)))
            panelVisibility[panel] = visible
            updatePanelView(panel, visible)
        }
    }
    
    /**
     * 应用主题
     */
    private fun applyTheme(theme: Theme) {
        val mode = when (theme) {
            Theme.LIGHT -> AppCompatDelegate.MODE_NIGHT_NO
            Theme.DARK -> AppCompatDelegate.MODE_NIGHT_YES
            Theme.AUTO -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
        }
        // Avoid unnecessary Activity recreate: only set when changed
        if (AppCompatDelegate.getDefaultNightMode() != mode) {
            AppCompatDelegate.setDefaultNightMode(mode)
        }
    }
    
    /**
     * 更新面板视图
     */
    private fun updatePanelView(panel: PanelType, visible: Boolean) {
        try {
            val view = when (panel) {
                PanelType.EDITOR -> activity.findViewById<View>(R.id.editor_container)
                PanelType.FILE_TREE -> activity.findViewById<View>(R.id.file_tree_container)
                // 终端面板已移除布局，这里直接忽略
                PanelType.TERMINAL -> null
                PanelType.TOOLBAR -> activity.findViewById<View>(R.id.toolbar)
            }
            view?.visibility = if (visible) View.VISIBLE else View.GONE
        } catch (e: Exception) {
            Log.e(TAG, "Error updating panel view: $panel", e)
        }
    }
    
    /**
     * 获取面板默认可见性
     */
    private fun getDefaultVisibility(panel: PanelType): Boolean {
        return when (panel) {
            PanelType.EDITOR -> true
            PanelType.FILE_TREE -> true
            PanelType.TERMINAL -> false
            PanelType.TOOLBAR -> true
        }
    }
    
    /**
     * 检查当前是否为暗色模式
     */
    fun isDarkMode(): Boolean {
        return when (currentTheme) {
            Theme.LIGHT -> false
            Theme.DARK -> true
            Theme.AUTO -> {
                val nightMode = activity.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
                nightMode == Configuration.UI_MODE_NIGHT_YES
            }
        }
    }
}
