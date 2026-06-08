package com.wuxianggujun.tinaide.core.proot

import com.wuxianggujun.tinaide.core.linux.LinuxEnvironment

internal enum class LinuxDistroRootfsBootstrapPhase {
    CHECKING_PACKAGE_MANAGER,
    CHECKING_COMMANDS,
    UPDATING_INDEX,
    RESOLVING_PACKAGES,
    INSTALLING_PACKAGES,
    VERIFYING_COMMANDS,
    COMPLETED,
}

internal data class LinuxDistroRootfsBootstrapProgress(
    val phase: LinuxDistroRootfsBootstrapPhase,
    val progress: Float,
    val commandGroupId: String? = null,
    val packages: List<String> = emptyList(),
)

internal data class LinuxDistroRootfsBootstrapResult(
    val packageManager: RootfsPackageManager,
    val installedPackages: List<String>,
    val skippedOptionalGroups: List<String>,
)

internal class LinuxDistroRootfsBootstrapper(
    private val updateTimeoutMs: Long = DEFAULT_UPDATE_TIMEOUT_MS,
    private val installTimeoutMs: Long = DEFAULT_INSTALL_TIMEOUT_MS,
    private val commandProbeTimeoutMs: Long = DEFAULT_COMMAND_PROBE_TIMEOUT_MS,
    private val packageProbeTimeoutMs: Long = DEFAULT_PACKAGE_PROBE_TIMEOUT_MS,
) {

    suspend fun bootstrap(
        linuxEnvironment: LinuxEnvironment,
        packageManager: RootfsPackageManager,
        progress: (LinuxDistroRootfsBootstrapProgress) -> Unit = {},
    ): LinuxDistroRootfsBootstrapResult {
        val commandGroups = GuestSystemPackageManager.bootstrapCommandGroups(packageManager)
        check(commandGroups.isNotEmpty()) {
            "Unsupported Linux distro package manager for bootstrap: $packageManager"
        }

        progress(
            LinuxDistroRootfsBootstrapProgress(
                phase = LinuxDistroRootfsBootstrapPhase.CHECKING_PACKAGE_MANAGER,
                progress = CHECK_PACKAGE_MANAGER_PROGRESS,
            )
        )
        val missingPackageManagerCommands = GuestSystemPackageManager.findMissingPackageManagerCommands(
            linuxEnvironment = linuxEnvironment,
            packageManager = packageManager,
            timeoutMs = commandProbeTimeoutMs,
        )
        check(missingPackageManagerCommands.isEmpty()) {
            "Linux distro package manager commands are unavailable: ${missingPackageManagerCommands.joinToString()}"
        }

        val missingGroups = mutableListOf<GuestPackageCommandGroup>()
        commandGroups.forEachIndexed { index, group ->
            progress(
                LinuxDistroRootfsBootstrapProgress(
                    phase = LinuxDistroRootfsBootstrapPhase.CHECKING_COMMANDS,
                    progress = CHECK_COMMANDS_START + CHECK_COMMANDS_RANGE * index / commandGroups.size,
                    commandGroupId = group.id,
                )
            )
            if (!GuestSystemPackageManager.isCommandGroupAvailable(
                    linuxEnvironment = linuxEnvironment,
                    commands = group.commands,
                    timeoutMs = commandProbeTimeoutMs,
                )
            ) {
                missingGroups += group
            }
        }
        if (missingGroups.isEmpty()) {
            progress(
                LinuxDistroRootfsBootstrapProgress(
                    phase = LinuxDistroRootfsBootstrapPhase.COMPLETED,
                    progress = 1f,
                )
            )
            return LinuxDistroRootfsBootstrapResult(
                packageManager = packageManager,
                installedPackages = emptyList(),
                skippedOptionalGroups = emptyList(),
            )
        }

        progress(
            LinuxDistroRootfsBootstrapProgress(
                phase = LinuxDistroRootfsBootstrapPhase.UPDATING_INDEX,
                progress = UPDATE_INDEX_PROGRESS,
            )
        )
        val updateResult = GuestSystemPackageManager.updateIndex(
            linuxEnvironment = linuxEnvironment,
            packageManager = packageManager,
            timeoutMs = updateTimeoutMs,
        )
        check(updateResult.isSuccess) {
            updateResult.combinedOutput.ifBlank { "Linux distro package index update failed: $packageManager" }
        }

        val unresolvedRequiredGroups = mutableListOf<GuestPackageCommandGroup>()
        val skippedOptionalGroups = mutableListOf<GuestPackageCommandGroup>()
        val packagesToInstall = mutableListOf<String>()

        missingGroups.forEachIndexed { index, group ->
            progress(
                LinuxDistroRootfsBootstrapProgress(
                    phase = LinuxDistroRootfsBootstrapPhase.RESOLVING_PACKAGES,
                    progress = RESOLVE_PACKAGES_START + RESOLVE_PACKAGES_RANGE * index / missingGroups.size,
                    commandGroupId = group.id,
                )
            )
            val packageName = resolvePackageCandidate(
                linuxEnvironment = linuxEnvironment,
                packageManager = packageManager,
                group = group,
            )
            when {
                packageName != null -> packagesToInstall += packageName
                group.required -> unresolvedRequiredGroups += group
                else -> skippedOptionalGroups += group
            }
        }

        check(unresolvedRequiredGroups.isEmpty()) {
            val groupIds = unresolvedRequiredGroups.joinToString { group -> group.id }
            "Required Linux distro bootstrap packages are unavailable: $groupIds"
        }

        val distinctPackages = packagesToInstall.distinct()
        if (distinctPackages.isNotEmpty()) {
            progress(
                LinuxDistroRootfsBootstrapProgress(
                    phase = LinuxDistroRootfsBootstrapPhase.INSTALLING_PACKAGES,
                    progress = INSTALL_PACKAGES_PROGRESS,
                    packages = distinctPackages,
                )
            )
            val installResult = GuestSystemPackageManager.installPackages(
                linuxEnvironment = linuxEnvironment,
                packageManager = packageManager,
                packages = distinctPackages,
                timeoutMs = installTimeoutMs,
            )
            check(installResult.isSuccess) {
                installResult.combinedOutput.ifBlank {
                    "Linux distro bootstrap package installation failed: ${distinctPackages.joinToString()}"
                }
            }
        }

        val stillMissingRequiredGroups = mutableListOf<GuestPackageCommandGroup>()
        commandGroups.filter { group -> group.required }.forEachIndexed { index, group ->
            progress(
                LinuxDistroRootfsBootstrapProgress(
                    phase = LinuxDistroRootfsBootstrapPhase.VERIFYING_COMMANDS,
                    progress = VERIFY_COMMANDS_START + VERIFY_COMMANDS_RANGE * index / commandGroups.size,
                    commandGroupId = group.id,
                )
            )
            if (!GuestSystemPackageManager.isCommandGroupAvailable(
                    linuxEnvironment = linuxEnvironment,
                    commands = group.commands,
                    timeoutMs = commandProbeTimeoutMs,
                )
            ) {
                stillMissingRequiredGroups += group
            }
        }
        check(stillMissingRequiredGroups.isEmpty()) {
            val groupIds = stillMissingRequiredGroups.joinToString { group -> group.id }
            "Linux distro bootstrap commands are still unavailable after installation: $groupIds"
        }

        progress(
            LinuxDistroRootfsBootstrapProgress(
                phase = LinuxDistroRootfsBootstrapPhase.COMPLETED,
                progress = 1f,
                packages = distinctPackages,
            )
        )
        return LinuxDistroRootfsBootstrapResult(
            packageManager = packageManager,
            installedPackages = distinctPackages,
            skippedOptionalGroups = skippedOptionalGroups.map { group -> group.id },
        )
    }

    private suspend fun resolvePackageCandidate(
        linuxEnvironment: LinuxEnvironment,
        packageManager: RootfsPackageManager,
        group: GuestPackageCommandGroup,
    ): String? = group.packageCandidates.firstOrNull { candidate ->
        GuestSystemPackageManager.packageExists(
            linuxEnvironment = linuxEnvironment,
            packageManager = packageManager,
            packageName = candidate,
            timeoutMs = packageProbeTimeoutMs,
        )
    }

    companion object {
        private const val CHECK_PACKAGE_MANAGER_PROGRESS = 0.02f
        private const val CHECK_COMMANDS_START = 0.06f
        private const val CHECK_COMMANDS_RANGE = 0.12f
        private const val UPDATE_INDEX_PROGRESS = 0.22f
        private const val RESOLVE_PACKAGES_START = 0.32f
        private const val RESOLVE_PACKAGES_RANGE = 0.32f
        private const val INSTALL_PACKAGES_PROGRESS = 0.72f
        private const val VERIFY_COMMANDS_START = 0.88f
        private const val VERIFY_COMMANDS_RANGE = 0.10f

        private const val DEFAULT_UPDATE_TIMEOUT_MS = 120_000L
        private const val DEFAULT_INSTALL_TIMEOUT_MS = 300_000L
        private const val DEFAULT_COMMAND_PROBE_TIMEOUT_MS = 10_000L
        private const val DEFAULT_PACKAGE_PROBE_TIMEOUT_MS = 30_000L
    }
}
