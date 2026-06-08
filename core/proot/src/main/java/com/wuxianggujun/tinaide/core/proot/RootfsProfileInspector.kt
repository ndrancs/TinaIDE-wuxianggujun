package com.wuxianggujun.tinaide.core.proot

import java.io.File

object RootfsProfileInspector {

    fun inspect(
        profileId: String,
        rootfsDir: File,
        sourceType: RootfsSourceType,
        fallbackDisplayName: String? = null,
        now: Long = System.currentTimeMillis(),
    ): RootfsProfile {
        require(rootfsDir.isDirectory) { "Rootfs directory does not exist: ${rootfsDir.absolutePath}" }

        val shellPath = detectShellPath(rootfsDir)
            ?: throw IllegalStateException("Missing required shell in rootfs: /bin/sh")

        val osRelease = parseOsRelease(rootfsDir)
        val distroId = osRelease["ID"]
            ?.takeIf { it.isNotBlank() }
            ?.lowercase()
            ?: "custom"

        val distroName = osRelease["PRETTY_NAME"]
            ?: osRelease["NAME"]
            ?: fallbackDisplayName
            ?: rootfsDir.name

        return RootfsProfile(
            id = profileId,
            displayName = distroName.takeIf { it.isNotBlank() }
                ?: fallbackDisplayName?.takeIf { it.isNotBlank() }
                ?: rootfsDir.name,
            distroId = distroId,
            distroName = distroName,
            rootfsPath = rootfsDir.absolutePath,
            sourceType = sourceType,
            packageManager = detectPackageManager(rootfsDir),
            shellPath = shellPath,
            createdAt = now,
            updatedAt = now,
        )
    }

    fun hasShell(rootfsDir: File): Boolean = detectShellPath(rootfsDir) != null

    private fun parseOsRelease(rootfsDir: File): Map<String, String> {
        val content = RootfsFileChecks.readTextOrNull(rootfsDir, "/etc/os-release")
            ?: RootfsFileChecks.readTextOrNull(rootfsDir, "/usr/lib/os-release")
            ?: return emptyMap()

        return buildMap {
            content.lineSequence()
                .map { it.trim() }
                .filter { it.isNotEmpty() && !it.startsWith('#') }
                .forEach { line ->
                    val separator = line.indexOf('=')
                    if (separator <= 0) return@forEach
                    val key = line.substring(0, separator).trim()
                    val value = line.substring(separator + 1).trim().trim('"', '\'')
                    if (key.isNotEmpty()) {
                        put(key, value)
                    }
                }
        }
    }

    private fun detectShellPath(rootfsDir: File): String? {
        val candidates = listOf(
            "/bin/bash",
            "/usr/bin/bash",
            "/bin/zsh",
            "/usr/bin/zsh",
            "/bin/sh",
            "/usr/bin/sh",
        )
        return candidates.firstOrNull { RootfsFileChecks.exists(rootfsDir, it) }
    }

    private fun detectPackageManager(rootfsDir: File): RootfsPackageManager = when {
        RootfsFileChecks.exists(rootfsDir, "/sbin/apk") ||
            RootfsFileChecks.exists(rootfsDir, "/usr/sbin/apk") ||
            RootfsFileChecks.exists(rootfsDir, "/usr/bin/apk") -> RootfsPackageManager.APK
        RootfsFileChecks.exists(rootfsDir, "/usr/bin/apt-get") ||
            RootfsFileChecks.exists(rootfsDir, "/bin/apt-get") -> RootfsPackageManager.APT
        RootfsFileChecks.exists(rootfsDir, "/usr/bin/pacman") ||
            RootfsFileChecks.exists(rootfsDir, "/bin/pacman") -> RootfsPackageManager.PACMAN
        RootfsFileChecks.exists(rootfsDir, "/usr/bin/dnf") ||
            RootfsFileChecks.exists(rootfsDir, "/bin/dnf") -> RootfsPackageManager.DNF
        else -> RootfsPackageManager.UNKNOWN
    }
}
