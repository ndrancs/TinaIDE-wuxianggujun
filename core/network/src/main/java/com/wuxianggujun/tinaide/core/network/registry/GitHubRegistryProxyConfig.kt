package com.wuxianggujun.tinaide.core.network.registry

import android.content.Context

data class GitHubRegistryProxySettings(
    val enabled: Boolean = false,
    val host: String = "",
    val port: Int = 0,
    val customMirrorUrl: String = "",
) {
    val isUsable: Boolean
        get() = enabled && host.isNotBlank() && port in 1..65535

    val customMirrorPrefix: String?
        get() = GitHubRegistryConfig.normalizeGitHubProxyPrefix(customMirrorUrl)
}

object GitHubRegistryProxyConfig {
    private const val PREFS_NAME = "tinaide_github_registry_proxy"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_HOST = "host"
    private const val KEY_PORT = "port"
    private const val KEY_CUSTOM_MIRROR_URL = "custom_mirror_url"

    fun load(context: Context): GitHubRegistryProxySettings {
        val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return GitHubRegistryProxySettings(
            enabled = prefs.getBoolean(KEY_ENABLED, false),
            host = prefs.getString(KEY_HOST, "").orEmpty(),
            port = prefs.getInt(KEY_PORT, 0),
            customMirrorUrl = prefs.getString(KEY_CUSTOM_MIRROR_URL, "").orEmpty(),
        )
    }

    fun save(context: Context, settings: GitHubRegistryProxySettings) {
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_ENABLED, settings.enabled)
            .putString(KEY_HOST, settings.host.trim())
            .putInt(KEY_PORT, settings.port)
            .putString(KEY_CUSTOM_MIRROR_URL, settings.customMirrorPrefix.orEmpty())
            .apply()
    }
}
