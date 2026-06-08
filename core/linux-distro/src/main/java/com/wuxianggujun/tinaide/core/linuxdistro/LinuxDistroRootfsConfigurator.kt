package com.wuxianggujun.tinaide.core.linuxdistro

import java.io.File

data class LinuxDistroRootfsConfig(
    val nameservers: List<String> = listOf("1.1.1.1", "8.8.8.8"),
    val hostname: String = "tinaide",
    val environment: Map<String, String> = mapOf(
        "LANG" to "C.UTF-8",
        "TERM" to "xterm-256color",
    ),
)

interface LinuxDistroRootfsConfigurator {
    fun configure(rootfsDir: File, config: LinuxDistroRootfsConfig = LinuxDistroRootfsConfig())
}

class BasicLinuxDistroRootfsConfigurator : LinuxDistroRootfsConfigurator {
    override fun configure(rootfsDir: File, config: LinuxDistroRootfsConfig) {
        require(rootfsDir.isDirectory) { "Rootfs directory does not exist: ${rootfsDir.absolutePath}" }
        writeResolvConf(rootfsDir, config.nameservers)
        writeHosts(rootfsDir, config.hostname)
        writeProfileEnvironment(rootfsDir, config.environment)
    }

    private fun writeResolvConf(rootfsDir: File, nameservers: List<String>) {
        val target = File(rootfsDir, "etc/resolv.conf")
        target.parentFile?.mkdirs()
        target.writeText(
            nameservers.joinToString(separator = "\n", postfix = "\n") { nameserver -> "nameserver $nameserver" },
            Charsets.UTF_8,
        )
    }

    private fun writeHosts(rootfsDir: File, hostname: String) {
        val safeHostname = hostname.takeIf { it.isSafeId() } ?: "tinaide"
        val target = File(rootfsDir, "etc/hosts")
        target.parentFile?.mkdirs()
        target.writeText(
            "127.0.0.1 localhost\n127.0.1.1 $safeHostname\n::1 localhost ip6-localhost ip6-loopback\n",
            Charsets.UTF_8,
        )
    }

    private fun writeProfileEnvironment(rootfsDir: File, environment: Map<String, String>) {
        if (environment.isEmpty()) return
        val target = File(rootfsDir, "etc/profile.d/tinaide.sh")
        target.parentFile?.mkdirs()
        val content = buildString {
            appendLine("# TinaIDE Linux distro environment")
            environment.entries
                .sortedBy { entry -> entry.key }
                .forEach { (key, value) -> appendLine("export ${shellName(key)}=${shellSingleQuoted(value)}") }
        }
        target.writeText(content, Charsets.UTF_8)
        target.setReadable(true, false)
    }

    private fun shellName(value: String): String {
        require(value.isNotBlank() && value.all { char -> char.isLetterOrDigit() || char == '_' }) {
            "Unsafe shell variable name: $value"
        }
        return value
    }

    private fun shellSingleQuoted(value: String): String = "'" + value.replace("'", "'\\''") + "'"
}
