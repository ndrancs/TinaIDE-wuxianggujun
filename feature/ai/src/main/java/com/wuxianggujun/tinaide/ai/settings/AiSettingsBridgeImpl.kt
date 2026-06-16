package com.wuxianggujun.tinaide.ai.settings

import android.content.Context
import com.wuxianggujun.tinaide.ai.api.AiApiClient
import com.wuxianggujun.tinaide.ai.api.AuthStrategy
import com.wuxianggujun.tinaide.ai.channel.AiChannelRepository
import com.wuxianggujun.tinaide.ai.config.AiPreferences
import com.wuxianggujun.tinaide.ai.tools.config.ToolConfigManager
import com.wuxianggujun.tinaide.core.config.ai.AiConfig
import com.wuxianggujun.tinaide.core.config.ai.AiGenerationSettings
import com.wuxianggujun.tinaide.core.config.ai.AiModelLoadResult
import com.wuxianggujun.tinaide.core.config.ai.AiProvider
import com.wuxianggujun.tinaide.core.config.ai.AiSettingsBridge
import com.wuxianggujun.tinaide.core.config.ai.AiToolSettingsItem
import com.wuxianggujun.tinaide.core.network.ApiResult
import java.util.concurrent.ConcurrentHashMap
import timber.log.Timber

class AiSettingsBridgeImpl(
    private val context: Context,
    private val aiPreferences: AiPreferences,
    private val channelRepository: AiChannelRepository,
    private val apiClientFactory: (AiConfig, String, AuthStrategy) -> AiApiClient = { config, endpoint, auth ->
        AiApiClient(config = config, endpoint = endpoint, auth = auth)
    },
) : AiSettingsBridge {

    companion object {
        private const val TAG = "AiSettingsBridge"
        private const val CACHE_TTL_MS = 5 * 60 * 1000L
    }

    private data class CachedModels(val models: List<String>, val fetchedAt: Long) {
        fun isFresh(now: Long): Boolean = now - fetchedAt < CACHE_TTL_MS
    }

    private val modelCache = ConcurrentHashMap<String, CachedModels>()

    override suspend fun loadModels(config: AiConfig): AiModelLoadResult = loadCustomModels(config)

    override fun getToolItems(context: Context): List<AiToolSettingsItem> = ToolConfigManager.getAllToolConfigs(context)
        .sortedWith(compareBy({ it.category.ordinal }, { it.name }))
        .map { tool ->
            AiToolSettingsItem(
                name = tool.name,
                displayName = tool.displayName,
                description = tool.description,
                categoryLabel = tool.category.getDisplayName(context),
                enabledByDefault = tool.enabledByDefault
            )
        }

    override fun getToolEnabledStates(): Map<String, Boolean> = ToolConfigManager.getAllToolConfigs(context)
        .associate { tool -> tool.name to tool.enabled }

    override fun applyToolEnabledStates(states: Map<String, Boolean>) {
        ToolConfigManager.saveToolEnabledStates(states)
    }

    override fun persistToolEnabledStates(states: Map<String, Boolean>) {
        aiPreferences.saveToolEnabledStates(states)
    }

    private suspend fun loadCustomModels(config: AiConfig): AiModelLoadResult {
        val channelId = config.activeChannelId ?: return AiModelLoadResult.ConfigurationRequired
        val channel = channelRepository.getById(channelId)
            ?: return AiModelLoadResult.ConfigurationRequired
        val apiKey = channelRepository.getApiKey(channelId)

        return fetchModels(
            provider = channel.provider,
            endpoint = channel.baseUrl,
            model = config.generation.model,
            apiKey = apiKey,
        )
    }

    private suspend fun fetchModels(
        provider: AiProvider,
        endpoint: String,
        model: String,
        apiKey: String,
    ): AiModelLoadResult {
        val now = System.currentTimeMillis()
        val cacheKey = "byok:$endpoint:${apiKey.hashCode()}"
        modelCache[cacheKey]?.takeIf { it.isFresh(now) }?.let {
            return AiModelLoadResult.Success(it.models)
        }

        val effectiveConfig = AiConfig(
            generation = AiGenerationSettings(
                model = model.ifBlank { provider.defaultModels.firstOrNull().orEmpty() },
            ),
        )

        val client = apiClientFactory(
            effectiveConfig,
            endpoint,
            AuthStrategy.Bearer(apiKey),
        )
        return when (val result = client.listModels()) {
            is ApiResult.Success -> {
                val models = result.data.filter { it.isNotBlank() }.distinct()
                modelCache[cacheKey] = CachedModels(models, now)
                AiModelLoadResult.Success(models)
            }
            is ApiResult.Error -> {
                Timber.tag(TAG).e("Load custom models failed: %s", result.message)
                AiModelLoadResult.Failure(
                    message = result.message,
                    fallbackModels = provider.defaultModels,
                )
            }
            is ApiResult.NetworkError -> {
                Timber.tag(TAG).e("Load custom models network error: %s", result.message)
                AiModelLoadResult.Failure(
                    message = result.message,
                    fallbackModels = provider.defaultModels,
                )
            }
        }
    }
}
