package com.wuxianggujun.tinaide.core.config

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 应用主题枚举
 *
 * 支持四种主题模式：
 * - LIGHT: 浅色主题
 * - DARK: 深色主题（纯黑背景 #121212）
 * - GRAY: 灰色主题（深灰背景 #2D2D2D，减少视觉疲劳）
 * - AUTO: 跟随系统主题
 */
enum class AppTheme {
    LIGHT,
    DARK,
    GRAY,
    AUTO;

    companion object {
        /**
         * 从字符串转换为 AppTheme
         * @param value 主题名称字符串（大小写不敏感）
         * @return 对应的 AppTheme，无效值默认返回 DARK
         */
        fun fromString(value: String): AppTheme = try {
            valueOf(value.uppercase())
        } catch (e: IllegalArgumentException) {
            LIGHT // 默认浅色主题
        }
    }
}

/**
 * 全局主题管理器（单例）
 *
 * 职责：
 * - 管理当前主题状态（使用 StateFlow 实现响应式）
 * - 提供主题切换 API
 * - 通知所有订阅者主题变化（Compose、编辑器、Activity 等）
 *
 * 使用方式：
 * ```kotlin
 * // 初始化（Application.onCreate）
 * ThemeManager.initialize(Prefs.appTheme)
 *
 * // Compose 中订阅
 * val theme by ThemeManager.themeFlow.collectAsState()
 *
 * // 切换主题
 * ThemeManager.setTheme(AppTheme.DARK)
 * ```
 */
object ThemeManager {
    private val _themeFlow = MutableStateFlow(AppTheme.LIGHT)

    /**
     * 主题状态流，供外部订阅
     */
    val themeFlow: StateFlow<AppTheme> = _themeFlow.asStateFlow()

    /**
     * 设置主题并通知所有订阅者
     * @param theme 新主题
     */
    fun setTheme(theme: AppTheme) {
        if (_themeFlow.value != theme) {
            _themeFlow.value = theme
        }
    }

    /**
     * 获取当前主题
     * @return 当前 AppTheme
     */
    fun getCurrentTheme(): AppTheme = _themeFlow.value

    /**
     * 初始化主题（从持久化存储恢复）
     * @param savedTheme 保存的主题字符串（例如 "DARK"、"LIGHT"）
     */
    fun initialize(savedTheme: String) {
        _themeFlow.value = AppTheme.fromString(savedTheme)
    }
}
