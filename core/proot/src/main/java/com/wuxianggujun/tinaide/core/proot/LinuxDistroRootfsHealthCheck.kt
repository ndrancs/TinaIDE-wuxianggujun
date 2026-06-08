package com.wuxianggujun.tinaide.core.proot

import com.wuxianggujun.tinaide.core.linux.LinuxEnvironment
import com.wuxianggujun.tinaide.core.linux.LinuxExecutionResult
import kotlinx.coroutines.CancellationException

/**
 * 自研 Linux rootfs 的运行时自检项。
 *
 * 这里只输出稳定的结构化 ID 与命令结果，UI 层后续再做国际化展示。
 */
enum class LinuxDistroRootfsHealthProbe {
    ROOTFS_AVAILABLE,
    PACKAGE_MANAGER_COMMANDS,
    PACKAGE_MANAGER_VERSION,
    REQUIRED_BOOTSTRAP_COMMANDS,
    OPTIONAL_BOOTSTRAP_COMMANDS,
    ARCHITECTURE,
    OS_RELEASE,
}

data class LinuxDistroRootfsHealthCheck(
    val probe: LinuxDistroRootfsHealthProbe,
    val passed: Boolean,
    val required: Boolean,
    val checkedItems: List<String> = emptyList(),
    val missingItems: List<String> = emptyList(),
    val output: String = "",
) {
    val usable: Boolean
        get() = passed || !required
}

data class LinuxDistroRootfsHealthReport(
    val packageManager: RootfsPackageManager,
    val checks: List<LinuxDistroRootfsHealthCheck>,
    val architecture: String? = null,
    val osRelease: Map<String, String> = emptyMap(),
) {
    val isUsable: Boolean
        get() = checks.all { check -> check.usable }

    val allChecksPassed: Boolean
        get() = checks.all { check -> check.passed }

    val requiredFailures: List<LinuxDistroRootfsHealthCheck>
        get() = checks.filter { check -> check.required && !check.passed }
}

internal class LinuxDistroRootfsHealthChecker(
    private val commandTimeoutMs: Long = DEFAULT_COMMAND_TIMEOUT_MS,
    private val metadataTimeoutMs: Long = DEFAULT_METADATA_TIMEOUT_MS,
) {
    suspend fun check(
        linuxEnvironment: LinuxEnvironment,
        packageManager: RootfsPackageManager,
    ): LinuxDistroRootfsHealthReport {
        val checks = mutableListOf<LinuxDistroRootfsHealthCheck>()
        val rootfsAvailable = runCatching { linuxEnvironment.isAvailable() }.getOrDefault(false)
        checks += LinuxDistroRootfsHealthCheck(
            probe = LinuxDistroRootfsHealthProbe.ROOTFS_AVAILABLE,
            passed = rootfsAvailable,
            required = true,
        )
        if (!rootfsAvailable) {
            return LinuxDistroRootfsHealthReport(
                packageManager = packageManager,
                checks = checks,
            )
        }

        val supportedPackageManager = GuestSystemPackageManager.isPackageManagerSupported(packageManager)
        if (supportedPackageManager) {
            checks += checkPackageManagerCommands(linuxEnvironment, packageManager)
            checks += checkPackageManagerVersion(linuxEnvironment, packageManager)
            checks += checkBootstrapCommands(linuxEnvironment, packageManager, required = true)
            checks += checkBootstrapCommands(linuxEnvironment, packageManager, required = false)
        } else {
            checks += LinuxDistroRootfsHealthCheck(
                probe = LinuxDistroRootfsHealthProbe.PACKAGE_MANAGER_COMMANDS,
                passed = false,
                required = true,
                missingItems = listOf(packageManager.name),
            )
        }

        val architectureResult = executeProbe(
            linuxEnvironment = linuxEnvironment,
            command = listOf("uname", "-m"),
            timeoutMs = metadataTimeoutMs,
        )
        val architecture = architectureResult.firstOutputLine()
        checks += LinuxDistroRootfsHealthCheck(
            probe = LinuxDistroRootfsHealthProbe.ARCHITECTURE,
            passed = architectureResult.isSuccess && !architecture.isNullOrBlank(),
            required = true,
            output = architecture.orEmpty(),
        )

        val osReleaseResult = executeProbe(
            linuxEnvironment = linuxEnvironment,
            command = listOf("/bin/sh", "-lc", "cat /etc/os-release"),
            timeoutMs = metadataTimeoutMs,
        )
        val osRelease = parseOsRelease(osReleaseResult.stdout)
        checks += LinuxDistroRootfsHealthCheck(
            probe = LinuxDistroRootfsHealthProbe.OS_RELEASE,
            passed = osReleaseResult.isSuccess && osRelease.isNotEmpty(),
            required = true,
            checkedItems = osRelease.keys.sorted(),
            output = osRelease["PRETTY_NAME"] ?: osRelease["NAME"] ?: osReleaseResult.firstOutputLine().orEmpty(),
        )

        return LinuxDistroRootfsHealthReport(
            packageManager = packageManager,
            checks = checks,
            architecture = architecture,
            osRelease = osRelease,
        )
    }

    private suspend fun checkPackageManagerCommands(
        linuxEnvironment: LinuxEnvironment,
        packageManager: RootfsPackageManager,
    ): LinuxDistroRootfsHealthCheck {
        val expectedCommands = GuestPackageManagerSpecs.require(packageManager).packageManagerCommands
            .map(String::trim)
            .filter(String::isNotEmpty)
            .distinct()
        val missingCommands = GuestSystemPackageManager.findMissingPackageManagerCommands(
            linuxEnvironment = linuxEnvironment,
            packageManager = packageManager,
            timeoutMs = commandTimeoutMs,
        )
        return LinuxDistroRootfsHealthCheck(
            probe = LinuxDistroRootfsHealthProbe.PACKAGE_MANAGER_COMMANDS,
            passed = missingCommands.isEmpty(),
            required = true,
            checkedItems = expectedCommands,
            missingItems = missingCommands,
        )
    }

    private suspend fun checkPackageManagerVersion(
        linuxEnvironment: LinuxEnvironment,
        packageManager: RootfsPackageManager,
    ): LinuxDistroRootfsHealthCheck {
        val result = GuestSystemPackageManager.probePackageManagerVersion(
            linuxEnvironment = linuxEnvironment,
            packageManager = packageManager,
            timeoutMs = commandTimeoutMs,
        )
        return LinuxDistroRootfsHealthCheck(
            probe = LinuxDistroRootfsHealthProbe.PACKAGE_MANAGER_VERSION,
            passed = result.isSuccess,
            required = true,
            output = result.firstOutputLine().orEmpty(),
        )
    }

    private suspend fun checkBootstrapCommands(
        linuxEnvironment: LinuxEnvironment,
        packageManager: RootfsPackageManager,
        required: Boolean,
    ): LinuxDistroRootfsHealthCheck {
        val allGroups = GuestSystemPackageManager.bootstrapCommandGroups(packageManager)
        if (required && allGroups.isEmpty()) {
            return LinuxDistroRootfsHealthCheck(
                probe = LinuxDistroRootfsHealthProbe.REQUIRED_BOOTSTRAP_COMMANDS,
                passed = false,
                required = true,
                missingItems = listOf(packageManager.name),
            )
        }

        val groups = allGroups.filter { group -> group.required == required }
        val missingGroups = mutableListOf<String>()
        for (group in groups) {
            val available = GuestSystemPackageManager.isCommandGroupAvailable(
                linuxEnvironment = linuxEnvironment,
                commands = group.commands,
                timeoutMs = commandTimeoutMs,
            )
            if (!available) missingGroups += group.id
        }
        return LinuxDistroRootfsHealthCheck(
            probe = if (required) {
                LinuxDistroRootfsHealthProbe.REQUIRED_BOOTSTRAP_COMMANDS
            } else {
                LinuxDistroRootfsHealthProbe.OPTIONAL_BOOTSTRAP_COMMANDS
            },
            passed = missingGroups.isEmpty(),
            required = required,
            checkedItems = groups.map { group -> group.id },
            missingItems = missingGroups,
        )
    }

    private suspend fun executeProbe(
        linuxEnvironment: LinuxEnvironment,
        command: List<String>,
        timeoutMs: Long,
    ): LinuxExecutionResult = try {
        linuxEnvironment.execute(
            command = command,
            workDir = "/",
            timeout = timeoutMs,
        )
    } catch (t: Throwable) {
        if (t is CancellationException) throw t
        LinuxExecutionResult(
            exitCode = -1,
            stdout = "",
            stderr = t.message.orEmpty(),
            durationMs = 0L,
        )
    }

    private fun LinuxExecutionResult.firstOutputLine(): String? = combinedOutput
        .lineSequence()
        .map { line -> line.trim() }
        .firstOrNull { line -> line.isNotEmpty() }

    private fun parseOsRelease(content: String): Map<String, String> = content.lineSequence()
        .map { line -> line.trim() }
        .filter { line -> line.isNotEmpty() && !line.startsWith('#') && '=' in line }
        .mapNotNull { line ->
            val key = line.substringBefore('=').trim()
            val value = line.substringAfter('=').trim().decodeOsReleaseValue()
            key.takeIf { it.isNotEmpty() }?.let { it to value }
        }
        .toMap()

    private fun String.decodeOsReleaseValue(): String {
        val trimmed = trim()
        if (trimmed.length >= 2 && trimmed.first() == '"' && trimmed.last() == '"') {
            return trimmed.substring(1, trimmed.length - 1)
                .replace("\\\"", "\"")
                .replace("\\\\", "\\")
        }
        return trimmed
    }

    companion object {
        private const val DEFAULT_COMMAND_TIMEOUT_MS = 10_000L
        private const val DEFAULT_METADATA_TIMEOUT_MS = 10_000L
    }
}
