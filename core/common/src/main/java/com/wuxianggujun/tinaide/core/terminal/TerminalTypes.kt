package com.wuxianggujun.tinaide.core.terminal

import androidx.annotation.StringRes

/**
 * 终端相关类型定义
 */

/**
 * 支持的 Shell 类型
 */
enum class ShellType(val value: String) {
    AUTO("auto"),
    SH("sh"),
    BASH("bash"),
    ZSH("zsh");

    companion object {
        fun fromValue(value: String): ShellType = entries.find { it.value == value } ?: AUTO
    }
}

/**
 * 终端后端模式
 *
 * - AUTO: 自动选择（如果 PRoot 已安装则用 PRoot,否则用 HOST）
 * - PROOT: 强制使用 PRoot Linux 容器环境（完整 Linux 开发环境,apt/gcc/python 等）
 * - HOST: 强制使用 Android 原生环境（可运行 NDK 编译的二进制文件）
 */
enum class BackendMode(val value: String) {
    AUTO("auto"),
    PROOT("proot"),
    HOST("host");

    companion object {
        fun fromValue(value: String): BackendMode = entries.find { it.value == value } ?: AUTO
    }
}

/**
 * 终端语言环境
 */
enum class TerminalLocale(val value: String, @param:StringRes val displayNameResId: Int) {
    C_UTF8("C.UTF-8", com.wuxianggujun.tinaide.core.i18n.R.string.terminal_locale_c_utf8),
    ZH_CN("zh_CN.UTF-8", com.wuxianggujun.tinaide.core.i18n.R.string.terminal_locale_zh_cn),
    ZH_TW("zh_TW.UTF-8", com.wuxianggujun.tinaide.core.i18n.R.string.terminal_locale_zh_tw),
    EN_US("en_US.UTF-8", com.wuxianggujun.tinaide.core.i18n.R.string.terminal_locale_en_us),
    JA_JP("ja_JP.UTF-8", com.wuxianggujun.tinaide.core.i18n.R.string.terminal_locale_ja_jp);

    companion object {
        fun fromValue(value: String): TerminalLocale = entries.find { it.value == value } ?: C_UTF8
    }
}
