package com.wuxianggujun.tinaide.core.proot

import com.wuxianggujun.tinaide.core.linuxdistro.DistroPackageManager
import com.wuxianggujun.tinaide.core.linuxdistro.InstalledLinuxDistro
import java.io.File

object LinuxDistroRootfsProfileMapper {

    fun toRootfsProfile(
        installation: InstalledLinuxDistro,
        now: Long = System.currentTimeMillis(),
    ): RootfsProfile {
        val rootfsDir = File(installation.rootfsPath)
        val inspected = RootfsProfileInspector.inspect(
            profileId = installation.profileId,
            rootfsDir = rootfsDir,
            sourceType = RootfsSourceType.LINUX_DISTRO,
            fallbackDisplayName = installation.displayName,
            now = now,
        )
        return inspected.copy(
            displayName = installation.displayName.ifBlank { inspected.displayName },
            packageManager = installation.packageManager.toRootfsPackageManager(),
            createdAt = installation.installedAtEpochMillis,
            updatedAt = installation.updatedAtEpochMillis.coerceAtLeast(now),
        )
    }

    fun DistroPackageManager.toRootfsPackageManager(): RootfsPackageManager = when (this) {
        DistroPackageManager.APK -> RootfsPackageManager.APK
        DistroPackageManager.APT -> RootfsPackageManager.APT
        DistroPackageManager.PACMAN -> RootfsPackageManager.PACMAN
        DistroPackageManager.DNF -> RootfsPackageManager.DNF
        DistroPackageManager.ZYPPER,
        DistroPackageManager.XBPS,
        DistroPackageManager.UNKNOWN -> RootfsPackageManager.UNKNOWN
    }
}
