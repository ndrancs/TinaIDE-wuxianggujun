package com.wuxianggujun.tinaide.core.network.registry

object GitHubRegistryConfig {
    const val OWNER = "wuxianggujun"
    const val REPOSITORY = "TinaIDE-Registry"
    const val BRANCH = "main"

    const val RAW_BASE_URL = "https://raw.githubusercontent.com/$OWNER/$REPOSITORY/$BRANCH"
    const val PLUGINS_INDEX_URL = "$RAW_BASE_URL/plugins/index.json"
    const val PACKAGES_INDEX_URL = "$RAW_BASE_URL/packages/index.json"

    fun resolveRawUrl(urlOrPath: String): String {
        val value = urlOrPath.trim()
        return if (value.startsWith("http://") || value.startsWith("https://")) {
            value
        } else {
            "$RAW_BASE_URL/${value.removePrefix("/")}"
        }
    }
}
