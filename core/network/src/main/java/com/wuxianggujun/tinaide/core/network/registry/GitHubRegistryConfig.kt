package com.wuxianggujun.tinaide.core.network.registry

object GitHubRegistryConfig {
    const val OWNER = "wuxianggujun"
    const val REPOSITORY = "TinaIDE-Registry"
    const val BRANCH = "main"
    const val REGISTRY_SCHEMA_VERSION = 2
    const val REGISTRY_V2_INTRODUCED_APP_VERSION = "0.17.11"
    const val REGISTRY_V1_FALLBACK_REMOVED_APP_VERSION = "0.20.0"

    const val GITHUB_RAW_BASE_URL = "https://raw.githubusercontent.com/$OWNER/$REPOSITORY/$BRANCH"
    const val JSDELIVR_BASE_URL = "https://cdn.jsdelivr.net/gh/$OWNER/$REPOSITORY@$BRANCH"

    const val RAW_BASE_URL = GITHUB_RAW_BASE_URL
    const val PRIMARY_BASE_URL = GITHUB_RAW_BASE_URL

    const val PLUGINS_INDEX_V2_PATH = "plugins/index.v2.json"
    const val PACKAGES_INDEX_V2_PATH = "packages/index.v2.json"

    val REGISTRY_ENDPOINTS: List<RegistryEndpoint> = listOf(
        RegistryEndpoint(name = "GitHub Raw", baseUrl = GITHUB_RAW_BASE_URL),
        RegistryEndpoint(name = "jsDelivr CDN", baseUrl = JSDELIVR_BASE_URL),
    )

    fun pluginIndexV2Urls(): List<RegistryUrl> = indexUrls(PLUGINS_INDEX_V2_PATH)

    fun packageIndexV2Urls(): List<RegistryUrl> = indexUrls(PACKAGES_INDEX_V2_PATH)

    fun resolveRawUrl(urlOrPath: String, baseUrl: String = PRIMARY_BASE_URL): String {
        val value = urlOrPath.trim()
        return if (value.startsWith("http://") || value.startsWith("https://")) {
            value
        } else {
            "${baseUrl.trimEnd('/')}/${value.removePrefix("/")}"
        }
    }

    private fun indexUrls(path: String): List<RegistryUrl> = REGISTRY_ENDPOINTS.map { endpoint ->
        RegistryUrl(
            endpoint = endpoint,
            url = resolveRawUrl(path, endpoint.baseUrl),
        )
    }
}

data class RegistryEndpoint(
    val name: String,
    val baseUrl: String,
)

data class RegistryUrl(
    val endpoint: RegistryEndpoint,
    val url: String,
)
