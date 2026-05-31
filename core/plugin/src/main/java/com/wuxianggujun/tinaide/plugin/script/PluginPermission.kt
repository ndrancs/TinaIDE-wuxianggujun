package com.wuxianggujun.tinaide.plugin.script

enum class PermissionLevel {
    L0_NO_RISK,
    L1_LOW_RISK,
    L2_MEDIUM_RISK,
    L3_HIGH_RISK
}

enum class PluginPermission(
    val id: String,
    val level: PermissionLevel,
    val description: String,
    val aliases: Set<String> = emptySet()
) {
    EDITOR_READ("editor.read", PermissionLevel.L0_NO_RISK, "Read editor state"),
    EDITOR_SELECTION("editor.selection", PermissionLevel.L0_NO_RISK, "Read selection"),
    DIAGNOSTICS_READ("diagnostics.read", PermissionLevel.L0_NO_RISK, "Read diagnostics"),
    UI_NOTIFICATION("ui.notification", PermissionLevel.L0_NO_RISK, "Show notifications"),

    EDITOR_WRITE("editor.write", PermissionLevel.L1_LOW_RISK, "Modify editor content"),
    CLIPBOARD_READ("clipboard.read", PermissionLevel.L1_LOW_RISK, "Read clipboard"),
    CLIPBOARD_WRITE("clipboard.write", PermissionLevel.L1_LOW_RISK, "Write clipboard"),
    COMMAND_EXECUTE(
        "command.execute",
        PermissionLevel.L1_LOW_RISK,
        "Execute host commands",
        aliases = setOf("commands.execute")
    ),

    FILE_READ(
        "file.read",
        PermissionLevel.L2_MEDIUM_RISK,
        "Read project files",
        aliases = setOf("workspace.read")
    ),
    FILE_WRITE(
        "file.write",
        PermissionLevel.L2_MEDIUM_RISK,
        "Write project files",
        aliases = setOf("workspace.write")
    ),
    NETWORK_FETCH("network.fetch", PermissionLevel.L2_MEDIUM_RISK, "Network requests (whitelisted)"),
    STORAGE_LOCAL("storage.local", PermissionLevel.L2_MEDIUM_RISK, "Plugin local storage"),
    STORAGE_DATABASE("storage.database", PermissionLevel.L2_MEDIUM_RISK, "Access SQLite database"),

    FILE_SYSTEM("file.system", PermissionLevel.L3_HIGH_RISK, "Access files outside project"),
    SHELL_EXECUTE("shell.execute", PermissionLevel.L3_HIGH_RISK, "Execute shell commands"),
    NETWORK_UNRESTRICTED("network.unrestricted", PermissionLevel.L3_HIGH_RISK, "Unrestricted network");

    companion object {
        private val lookup: Map<String, PluginPermission> = buildMap {
            PluginPermission.entries.forEach { permission ->
                put(permission.id, permission)
                permission.aliases.forEach { alias ->
                    put(alias, permission)
                }
            }
        }

        fun fromId(id: String): PluginPermission? = lookup[id.trim()]

        fun parseList(permissions: List<String>?): Set<PluginPermission> {
            return permissions?.mapNotNull { fromId(it) }?.toSet() ?: emptySet()
        }

        fun findUnknownIds(permissions: List<String>?): Set<String> {
            return permissions.orEmpty()
                .asSequence()
                .map(String::trim)
                .filter(String::isNotBlank)
                .filter { fromId(it) == null }
                .toSet()
        }
    }
}
