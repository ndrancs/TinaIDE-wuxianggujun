package com.wuxianggujun.tinaide.core.proot

import com.wuxianggujun.tinaide.core.linux.LinuxEnvironment

/**
 * 从 LinuxEnvironment 抽象中解析当前 guest 的系统包管理器。
 */
fun LinuxEnvironment.resolveGuestPackageManager(): RootfsPackageManager = (this as? PRootEnvironment)?.getActiveGuestPackageManager() ?: RootfsPackageManager.UNKNOWN

fun RootfsPackageManager.displayName(): String = when (this) {
    RootfsPackageManager.APK -> "apk"
    RootfsPackageManager.APT -> "apt"
    RootfsPackageManager.PACMAN -> "pacman"
    RootfsPackageManager.DNF -> "dnf"
    RootfsPackageManager.UNKNOWN -> "unknown"
}
