package com.wuxianggujun.tinaide.ai.config

import com.google.common.truth.Truth.assertThat
import com.wuxianggujun.tinaide.core.config.ai.AiConfig
import com.wuxianggujun.tinaide.core.config.ai.AiGenerationSettings
import com.wuxianggujun.tinaide.core.config.ai.AiNetworkSettings
import com.wuxianggujun.tinaide.core.config.ai.AiThinkingSettings
import com.wuxianggujun.tinaide.core.config.ai.AiToolSettings
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class AiPreferencesTest {

    private val context get() = RuntimeEnvironment.getApplication()

    @Before
    fun setUp() {
        clearPrefs()
    }

    @After
    fun tearDown() {
        clearPrefs()
    }

    @Test
    fun `default load returns default values without legacy mode key`() {
        val preferences = AiPreferences(context)

        assertThat(preferences.getCurrentConfig()).isEqualTo(AiConfig())
        assertThat(rawPrefs().contains("access_mode")).isFalse()
        assertThat(rawPrefs().contains("active_channel_id")).isFalse()
    }

    @Test
    fun `load removes legacy mode key`() {
        rawPrefs().edit()
            .putString("access_mode", "OLD_MODE")
            .commit()

        AiPreferences(context)

        assertThat(rawPrefs().contains("access_mode")).isFalse()
    }

    @Test
    fun `blank active channel is removed on save and reload`() {
        val preferences = AiPreferences(context)

        preferences.saveConfig(AiConfig(activeChannelId = "channel-1"))
        assertThat(rawPrefs().getString("active_channel_id", null)).isEqualTo("channel-1")

        preferences.saveConfig(AiConfig(activeChannelId = "   "))

        assertThat(preferences.getCurrentConfig().activeChannelId).isEqualTo("   ")
        assertThat(rawPrefs().contains("active_channel_id")).isFalse()

        val reloaded = AiPreferences(context).getCurrentConfig()
        assertThat(reloaded.activeChannelId).isNull()
    }

    @Test
    fun `save and load config round trip all groups`() {
        val preferences = AiPreferences(context)
        val config = AiConfig(
            activeChannelId = "channel-1",
            generation = AiGenerationSettings(model = "model-a", maxTokens = 77, temperature = 0.3f, imageDetail = "high"),
            tools = AiToolSettings(enableTools = true, allowDangerousToolsAuto = true),
            thinking = AiThinkingSettings(enableDeepThinking = true, budgetTokens = 1234),
            network = AiNetworkSettings(timeout = 10, retryCount = 4, retryDelaySeconds = 5),
        )

        preferences.saveConfig(config)
        val reloaded = AiPreferences(context).getCurrentConfig()

        assertThat(reloaded).isEqualTo(config)
    }

    @Test
    fun `tool enabled states round trip and malformed json returns empty map`() {
        val preferences = AiPreferences(context)

        preferences.saveToolEnabledStates(mapOf("read_file" to true, "delete_file" to false))

        assertThat(preferences.loadToolEnabledStates())
            .containsExactly("read_file", true, "delete_file", false)

        context.getSharedPreferences("ai_config_prefs", android.content.Context.MODE_PRIVATE)
            .edit()
            .putString("tool_enabled_states", "not json")
            .commit()

        assertThat(preferences.loadToolEnabledStates()).isEmpty()
    }

    private fun rawPrefs() = context.getSharedPreferences("ai_config_prefs", android.content.Context.MODE_PRIVATE)

    private fun clearPrefs() {
        rawPrefs().edit().clear().commit()
    }
}
