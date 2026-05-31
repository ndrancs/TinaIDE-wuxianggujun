package com.wuxianggujun.tinaide.ai.config

import android.content.Context
import android.content.SharedPreferences
import com.wuxianggujun.tinaide.core.config.ai.AiConfig
import com.wuxianggujun.tinaide.core.config.ai.AiConfigProvider
import com.wuxianggujun.tinaide.core.config.ai.AiConfigStrategy
import com.wuxianggujun.tinaide.core.config.ai.AiGenerationSettings
import com.wuxianggujun.tinaide.core.config.ai.AiNetworkSettings
import com.wuxianggujun.tinaide.core.config.ai.AiPromptSettings
import com.wuxianggujun.tinaide.core.config.ai.AiThinkingSettings
import com.wuxianggujun.tinaide.core.config.ai.AiToolSettings
import com.wuxianggujun.tinaide.core.serialization.JsonSerializer
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString

/**
 * AI 配置存储。
 *
 * 底层 SharedPreferences key 保持扁平，外部统一暴露 [AiConfig] 聚合模型。
 * 渠道密钥由 AiChannelApiKeyStore 管理，不在这里读取或写入。
 */
class AiPreferences(
    context: Context,
) : AiConfigProvider {

    private val prefs: SharedPreferences = context.getSharedPreferences(
        "ai_config_prefs",
        Context.MODE_PRIVATE
    )

    private val _configFlow = MutableStateFlow(loadConfig())
    override val configFlow: Flow<AiConfig> = _configFlow.asStateFlow()

    private val json = JsonSerializer.default

    private fun loadConfig(): AiConfig {
        val modeRaw = prefs.getString(KEY_ACCESS_MODE, null)
        val accessMode = AiConfigStrategy.resolveAccessMode(modeRaw)

        if (modeRaw == null) {
            prefs.edit().putString(KEY_ACCESS_MODE, accessMode.name).apply()
        }

        val fallbackSummaryPrompt = AiConfig.DEFAULT_SUMMARY_PROMPT

        return AiConfig(
            accessMode = accessMode,
            activeChannelId = prefs.getString(KEY_ACTIVE_CHANNEL_ID, null)?.takeIf { it.isNotBlank() },
            generation = AiGenerationSettings(
                model = prefs.getString(KEY_MODEL, "") ?: "",
                maxTokens = prefs.getInt(KEY_MAX_TOKENS, 4096),
                temperature = prefs.getFloat(KEY_TEMPERATURE, 0.7f),
                imageDetail = prefs.getString(KEY_IMAGE_DETAIL, "auto") ?: "auto",
            ),
            prompt = AiPromptSettings(
                systemPrompt = prefs.getString(KEY_SYSTEM_PROMPT, AiConfig.DEFAULT_SYSTEM_PROMPT)
                    ?: AiConfig.DEFAULT_SYSTEM_PROMPT,
                summaryPrompt = prefs.getString(KEY_SUMMARY_PROMPT, fallbackSummaryPrompt)
                    ?: fallbackSummaryPrompt,
            ),
            tools = AiToolSettings(
                enableTools = prefs.getBoolean(KEY_ENABLE_TOOLS, false),
                allowDangerousToolsAuto = prefs.getBoolean(KEY_ALLOW_DANGEROUS_TOOLS_AUTO, false),
            ),
            thinking = AiThinkingSettings(
                enableDeepThinking = prefs.getBoolean(KEY_ENABLE_DEEP_THINKING, false),
                budgetTokens = prefs.getInt(KEY_BUDGET_TOKENS, 10000),
            ),
            network = AiNetworkSettings(
                timeout = prefs.getInt(KEY_TIMEOUT, 60),
                retryCount = prefs.getInt(KEY_RETRY_COUNT, 3),
                retryDelaySeconds = prefs.getInt(KEY_RETRY_DELAY_SECONDS, 30),
            ),
        )
    }

    override fun saveConfig(config: AiConfig) {
        val effective = AiConfigStrategy.normalizeForOpenSource(config)

        prefs.edit().apply {
            putString(KEY_ACCESS_MODE, effective.accessMode.name)
            if (effective.activeChannelId.isNullOrBlank()) {
                remove(KEY_ACTIVE_CHANNEL_ID)
            } else {
                putString(KEY_ACTIVE_CHANNEL_ID, effective.activeChannelId)
            }
            putString(KEY_MODEL, effective.generation.model)
            putInt(KEY_MAX_TOKENS, effective.generation.maxTokens)
            putFloat(KEY_TEMPERATURE, effective.generation.temperature)
            putString(KEY_IMAGE_DETAIL, effective.generation.imageDetail)
            putString(KEY_SYSTEM_PROMPT, effective.prompt.systemPrompt)
            putString(KEY_SUMMARY_PROMPT, effective.prompt.summaryPrompt)
            putBoolean(KEY_ENABLE_TOOLS, effective.tools.enableTools)
            putBoolean(KEY_ALLOW_DANGEROUS_TOOLS_AUTO, effective.tools.allowDangerousToolsAuto)
            putBoolean(KEY_ENABLE_DEEP_THINKING, effective.thinking.enableDeepThinking)
            putInt(KEY_BUDGET_TOKENS, effective.thinking.budgetTokens)
            putInt(KEY_TIMEOUT, effective.network.timeout)
            putInt(KEY_RETRY_COUNT, effective.network.retryCount)
            putInt(KEY_RETRY_DELAY_SECONDS, effective.network.retryDelaySeconds)
            apply()
        }

        _configFlow.value = effective
    }

    override fun getCurrentConfig(): AiConfig = _configFlow.value

    /**
     * 保存工具启用状态。
     */
    fun saveToolEnabledStates(states: Map<String, Boolean>) {
        val jsonString = json.encodeToString(states)
        prefs.edit().putString(KEY_TOOL_ENABLED_STATES, jsonString).apply()
    }

    /**
     * 加载工具启用状态。
     */
    fun loadToolEnabledStates(): Map<String, Boolean> {
        val jsonString = prefs.getString(KEY_TOOL_ENABLED_STATES, null) ?: return emptyMap()
        return try {
            json.decodeFromString<Map<String, Boolean>>(jsonString)
        } catch (e: Exception) {
            emptyMap()
        }
    }

    companion object {
        private const val KEY_ACCESS_MODE = "access_mode"
        private const val KEY_MODEL = "model"
        private const val KEY_ACTIVE_CHANNEL_ID = "active_channel_id"
        private const val KEY_MAX_TOKENS = "max_tokens"
        private const val KEY_TEMPERATURE = "temperature"
        private const val KEY_SYSTEM_PROMPT = "system_prompt"
        private const val KEY_SUMMARY_PROMPT = "summary_prompt"
        private const val KEY_IMAGE_DETAIL = "image_detail"
        private const val KEY_ENABLE_TOOLS = "enable_tools"
        private const val KEY_ALLOW_DANGEROUS_TOOLS_AUTO = "allow_dangerous_tools_auto"
        private const val KEY_ENABLE_DEEP_THINKING = "enable_deep_thinking"
        private const val KEY_BUDGET_TOKENS = "budget_tokens"
        private const val KEY_TIMEOUT = "timeout"
        private const val KEY_TOOL_ENABLED_STATES = "tool_enabled_states"
        private const val KEY_RETRY_COUNT = "retry_count"
        private const val KEY_RETRY_DELAY_SECONDS = "retry_delay_seconds"
    }
}
