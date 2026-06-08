package com.wuxianggujun.tinaide.core.compile

import android.content.Context
import android.content.SharedPreferences
import com.wuxianggujun.tinaide.core.config.AppPreferences
import com.wuxianggujun.tinaide.core.i18n.Strings
import com.wuxianggujun.tinaide.core.i18n.str

/**
 * 编译超时配置管理器
 *
 * **功能**:
 * - 提供可配置的编译超时选项
 * - 用户可以在设置中自定义各阶段超时时间
 * - 提供合理的默认值
 *
 * **超时阶段**:
 * - 环境检查超时（cmake --version, clang --version 等）
 * - CMake 配置超时（cmake 配置项目）
 * - CMake 构建超时（cmake --build）
 * - CMake 清理超时（cmake --build --target clean）
 *
 * **使用示例**:
 * ```kotlin
 * val config = CompileTimeoutConfig(context)
 * // 获取配置超时
 * val configTimeout = config.getCMakeConfigTimeout()
 * // 更新构建超时
 * config.setCMakeBuildTimeout(900_000) // 15分钟
 * ```
 *
 * **注意事项**:
 * - 程序运行无超时限制（用户可以运行服务器、无限循环等程序）
 * - 仅编译阶段有超时限制，防止卡死
 * - 超时时间单位为毫秒
 *
 * @param context Android 应用上下文
 */
class CompileTimeoutConfig(context: Context) {

    companion object {
        // 默认超时值（毫秒）
        private const val DEFAULT_ENV_CHECK_TIMEOUT = 10_000L // 10秒 - 环境检查
        private const val DEFAULT_CMAKE_CONFIG_TIMEOUT = 120_000L // 2分钟 - CMake配置
        private const val DEFAULT_CMAKE_BUILD_TIMEOUT = 600_000L // 10分钟 - CMake构建
        private const val DEFAULT_CMAKE_CLEAN_TIMEOUT = 60_000L // 1分钟 - CMake清理
        private const val DEFAULT_CMAKE_HELP_TIMEOUT = 10_000L // 10秒 - CMake帮助
        private const val DEFAULT_MAKE_BUILD_TIMEOUT = 600_000L // 10分钟 - Make 构建
        private const val DEFAULT_MAKE_CLEAN_TIMEOUT = 60_000L // 1分钟 - Make 清理

        // SharedPreferences 键名
        private const val KEY_ENV_CHECK_TIMEOUT = "compile_timeout_env_check"
        private const val KEY_CMAKE_CONFIG_TIMEOUT = "compile_timeout_cmake_config"
        private const val KEY_CMAKE_BUILD_TIMEOUT = "compile_timeout_cmake_build"
        private const val KEY_CMAKE_CLEAN_TIMEOUT = "compile_timeout_cmake_clean"
        private const val KEY_CMAKE_HELP_TIMEOUT = "compile_timeout_cmake_help"
        private const val KEY_MAKE_BUILD_TIMEOUT = "compile_timeout_make_build"
        private const val KEY_MAKE_CLEAN_TIMEOUT = "compile_timeout_make_clean"

        // 超时范围限制（防止用户设置过小或过大的值）
        private const val MIN_TIMEOUT = 5_000L // 最小5秒
        private const val MAX_TIMEOUT = 3_600_000L // 最大1小时
    }

    private val prefs: SharedPreferences = AppPreferences.get(context)

    /**
     * 获取环境检查超时（cmake --version, clang --version 等）
     * 默认：10秒
     */
    fun getEnvCheckTimeout(): Long = prefs.getLong(KEY_ENV_CHECK_TIMEOUT, DEFAULT_ENV_CHECK_TIMEOUT)
        .coerceIn(MIN_TIMEOUT, MAX_TIMEOUT)

    /**
     * 设置环境检查超时
     */
    fun setEnvCheckTimeout(timeoutMs: Long) {
        prefs.edit()
            .putLong(KEY_ENV_CHECK_TIMEOUT, timeoutMs.coerceIn(MIN_TIMEOUT, MAX_TIMEOUT))
            .apply()
    }

    /**
     * 获取 CMake 配置超时（cmake 配置项目）
     * 默认：2分钟
     */
    fun getCMakeConfigTimeout(): Long = prefs.getLong(KEY_CMAKE_CONFIG_TIMEOUT, DEFAULT_CMAKE_CONFIG_TIMEOUT)
        .coerceIn(MIN_TIMEOUT, MAX_TIMEOUT)

    /**
     * 设置 CMake 配置超时
     */
    fun setCMakeConfigTimeout(timeoutMs: Long) {
        prefs.edit()
            .putLong(KEY_CMAKE_CONFIG_TIMEOUT, timeoutMs.coerceIn(MIN_TIMEOUT, MAX_TIMEOUT))
            .apply()
    }

    /**
     * 获取 CMake 构建超时（cmake --build）
     * 默认：10分钟
     */
    fun getCMakeBuildTimeout(): Long = prefs.getLong(KEY_CMAKE_BUILD_TIMEOUT, DEFAULT_CMAKE_BUILD_TIMEOUT)
        .coerceIn(MIN_TIMEOUT, MAX_TIMEOUT)

    /**
     * 设置 CMake 构建超时
     */
    fun setCMakeBuildTimeout(timeoutMs: Long) {
        prefs.edit()
            .putLong(KEY_CMAKE_BUILD_TIMEOUT, timeoutMs.coerceIn(MIN_TIMEOUT, MAX_TIMEOUT))
            .apply()
    }

    /**
     * 获取 CMake 清理超时（cmake --build --target clean）
     * 默认：1分钟
     */
    fun getCMakeCleanTimeout(): Long = prefs.getLong(KEY_CMAKE_CLEAN_TIMEOUT, DEFAULT_CMAKE_CLEAN_TIMEOUT)
        .coerceIn(MIN_TIMEOUT, MAX_TIMEOUT)

    /**
     * 设置 CMake 清理超时
     */
    fun setCMakeCleanTimeout(timeoutMs: Long) {
        prefs.edit()
            .putLong(KEY_CMAKE_CLEAN_TIMEOUT, timeoutMs.coerceIn(MIN_TIMEOUT, MAX_TIMEOUT))
            .apply()
    }

    /**
     * 获取 CMake 帮助超时（cmake --build --target help）
     * 默认：10秒
     */
    fun getCMakeHelpTimeout(): Long = prefs.getLong(KEY_CMAKE_HELP_TIMEOUT, DEFAULT_CMAKE_HELP_TIMEOUT)
        .coerceIn(MIN_TIMEOUT, MAX_TIMEOUT)

    /**
     * 设置 CMake 帮助超时
     */
    fun setCMakeHelpTimeout(timeoutMs: Long) {
        prefs.edit()
            .putLong(KEY_CMAKE_HELP_TIMEOUT, timeoutMs.coerceIn(MIN_TIMEOUT, MAX_TIMEOUT))
            .apply()
    }

    /**
     * 获取 Make 构建超时（make）
     * 默认：10分钟
     */
    fun getMakeBuildTimeout(): Long = prefs.getLong(KEY_MAKE_BUILD_TIMEOUT, DEFAULT_MAKE_BUILD_TIMEOUT)
        .coerceIn(MIN_TIMEOUT, MAX_TIMEOUT)

    /**
     * 设置 Make 构建超时
     */
    fun setMakeBuildTimeout(timeoutMs: Long) {
        prefs.edit()
            .putLong(KEY_MAKE_BUILD_TIMEOUT, timeoutMs.coerceIn(MIN_TIMEOUT, MAX_TIMEOUT))
            .apply()
    }

    /**
     * 获取 Make 清理超时（make clean）
     * 默认：1分钟
     */
    fun getMakeCleanTimeout(): Long = prefs.getLong(KEY_MAKE_CLEAN_TIMEOUT, DEFAULT_MAKE_CLEAN_TIMEOUT)
        .coerceIn(MIN_TIMEOUT, MAX_TIMEOUT)

    /**
     * 设置 Make 清理超时
     */
    fun setMakeCleanTimeout(timeoutMs: Long) {
        prefs.edit()
            .putLong(KEY_MAKE_CLEAN_TIMEOUT, timeoutMs.coerceIn(MIN_TIMEOUT, MAX_TIMEOUT))
            .apply()
    }

    /**
     * 重置所有超时为默认值
     */
    fun resetToDefaults() {
        prefs.edit()
            .putLong(KEY_ENV_CHECK_TIMEOUT, DEFAULT_ENV_CHECK_TIMEOUT)
            .putLong(KEY_CMAKE_CONFIG_TIMEOUT, DEFAULT_CMAKE_CONFIG_TIMEOUT)
            .putLong(KEY_CMAKE_BUILD_TIMEOUT, DEFAULT_CMAKE_BUILD_TIMEOUT)
            .putLong(KEY_CMAKE_CLEAN_TIMEOUT, DEFAULT_CMAKE_CLEAN_TIMEOUT)
            .putLong(KEY_CMAKE_HELP_TIMEOUT, DEFAULT_CMAKE_HELP_TIMEOUT)
            .putLong(KEY_MAKE_BUILD_TIMEOUT, DEFAULT_MAKE_BUILD_TIMEOUT)
            .putLong(KEY_MAKE_CLEAN_TIMEOUT, DEFAULT_MAKE_CLEAN_TIMEOUT)
            .apply()
    }

    /**
     * 获取所有超时配置的摘要信息（用于显示）
     */
    fun getSummary(): String = buildString {
        appendLine(Strings.compile_timeout_summary_title.str())
        appendLine(Strings.compile_timeout_env_check_line.str(formatTimeout(getEnvCheckTimeout())))
        appendLine(Strings.compile_timeout_cmake_config_line.str(formatTimeout(getCMakeConfigTimeout())))
        appendLine(Strings.compile_timeout_cmake_build_line.str(formatTimeout(getCMakeBuildTimeout())))
        appendLine(Strings.compile_timeout_cmake_clean_line.str(formatTimeout(getCMakeCleanTimeout())))
        appendLine(Strings.compile_timeout_cmake_help_line.str(formatTimeout(getCMakeHelpTimeout())))
        appendLine(Strings.compile_timeout_make_build_line.str(formatTimeout(getMakeBuildTimeout())))
        appendLine(Strings.compile_timeout_make_clean_line.str(formatTimeout(getMakeCleanTimeout())))
        appendLine()
        appendLine(Strings.compile_timeout_notice_no_run_timeout.str())
    }

    /**
     * 格式化超时时间为可读字符串
     */
    private fun formatTimeout(timeoutMs: Long): String {
        val seconds = timeoutMs / 1000
        return when {
            seconds < 60 -> Strings.compile_timeout_seconds.str(seconds)
            seconds < 3600 -> Strings.compile_timeout_minutes.str(seconds / 60)
            else -> Strings.compile_timeout_hours.str(seconds / 3600)
        }
    }
}
