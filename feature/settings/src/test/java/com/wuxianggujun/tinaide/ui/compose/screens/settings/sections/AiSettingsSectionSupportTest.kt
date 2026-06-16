package com.wuxianggujun.tinaide.ui.compose.screens.settings.sections

import com.google.common.truth.Truth.assertThat
import com.wuxianggujun.tinaide.core.config.ai.AiProvider
import com.wuxianggujun.tinaide.core.i18n.Strings
import org.junit.Test

class AiSettingsSectionSupportTest {

    @Test
    fun mappingHelpers_shouldReturnStableOptionsAndLabels() {
        assertThat(
            AiSettingsSectionSupport.buildProviderOptions()
        ).containsExactly(
            AiSettingsOptionSpec(
                value = AiProvider.OPENAI.name,
                labelRes = Strings.settings_ai_provider_openai,
            ),
            AiSettingsOptionSpec(
                value = AiProvider.DEEPSEEK.name,
                labelRes = Strings.settings_ai_provider_deepseek,
            ),
            AiSettingsOptionSpec(
                value = AiProvider.QWEN.name,
                labelRes = Strings.settings_ai_provider_qwen,
            ),
            AiSettingsOptionSpec(
                value = AiProvider.ZHIPU.name,
                labelRes = Strings.settings_ai_provider_zhipu,
            ),
            AiSettingsOptionSpec(
                value = AiProvider.OLLAMA.name,
                labelRes = Strings.settings_ai_provider_ollama,
            ),
            AiSettingsOptionSpec(
                value = AiProvider.CUSTOM.name,
                labelRes = Strings.settings_ai_provider_custom,
            ),
        ).inOrder()

        assertThat(
            AiSettingsSectionSupport.resolveProviderDisplayNameRes(AiProvider.OLLAMA)
        ).isEqualTo(Strings.settings_ai_provider_ollama)
        assertThat(
            AiSettingsSectionSupport.resolveImageDetailLabelRes("low")
        ).isEqualTo(Strings.settings_ai_image_detail_low)
        assertThat(
            AiSettingsSectionSupport.resolveImageDetailLabelRes("unexpected")
        ).isEqualTo(Strings.settings_ai_image_detail_auto)
    }

    @Test
    fun displayAndInputHelpers_shouldSanitizeTruncateAndClamp() {
        assertThat(
            AiSettingsSectionSupport.resolveBaseUrlPreview(
                "https://example.com/v1/chat/completions"
            )
        ).isEqualTo("https://example.com/v1/chat/co...")
        assertThat(
            AiSettingsSectionSupport.resolvePromptPreview("a".repeat(52))
        ).isEqualTo("a".repeat(50) + "...")
        assertThat(
            AiSettingsSectionSupport.sanitizeApiKey(" \n sk-\r123 \n ")
        ).isEqualTo("sk-123")

        assertThat(
            AiSettingsSectionSupport.parseMaxTokensInput("999999", currentValue = 4096)
        ).isEqualTo(128000)
        assertThat(
            AiSettingsSectionSupport.parseBudgetTokensInput("8", currentValue = 10000)
        ).isEqualTo(1000)
        assertThat(
            AiSettingsSectionSupport.parseTimeoutInput("abc", currentValue = 60)
        ).isEqualTo(60)
        assertThat(
            AiSettingsSectionSupport.parseRetryDelayInput("999", currentValue = 30)
        ).isEqualTo(300)
        assertThat(
            AiSettingsSectionSupport.normalizeRetryCountSliderValue(0.4f)
        ).isEqualTo(1)
        assertThat(
            AiSettingsSectionSupport.normalizeRetryCountSliderValue(10.8f)
        ).isEqualTo(10)
    }

    @Test
    fun modelHelpers_shouldNormalizeFallbackAndResolveDialogModes() {
        assertThat(
            AiSettingsSectionSupport.normalizeCustomModels(
                listOf("gpt-4o", "", "gpt-4o", "custom-model")
            )
        ).containsExactly("gpt-4o", "custom-model").inOrder()
        assertThat(
            AiSettingsSectionSupport.resolveCustomModelFallback(
                fallbackModels = emptyList(),
                provider = AiProvider.QWEN,
            )
        ).containsExactlyElementsIn(AiProvider.QWEN.defaultModels).inOrder()

        assertThat(
            AiSettingsSectionSupport.resolveModelDialogSpec(
                provider = AiProvider.OPENAI,
                currentModel = "gpt-4o",
                customModelsLoading = false,
                customModels = null,
            )
        ).isEqualTo(
            AiSettingsModelDialogSpec.Selectable(AiProvider.OPENAI.defaultModels)
        )
        assertThat(
            AiSettingsSectionSupport.resolveModelDialogSpec(
                provider = AiProvider.CUSTOM,
                currentModel = "manual-model",
                customModelsLoading = false,
                customModels = emptyList(),
            )
        ).isEqualTo(
            AiSettingsModelDialogSpec.ManualInput("manual-model")
        )
    }

    @Test
    fun validateChannelInput_rejectsBlankName() {
        val result = AiSettingsSectionSupport.validateChannelInput(
            name = "   ",
            baseUrl = "https://api.openai.com/v1",
            apiKey = "sk-abc",
            apiKeyRequired = true,
        )
        assertThat(result).isEqualTo(AiChannelInputValidation.NameBlank)
    }

    @Test
    fun validateChannelInput_rejectsSchemelessUrl() {
        val result = AiSettingsSectionSupport.validateChannelInput(
            name = "My Channel",
            baseUrl = "api.openai.com/v1",
            apiKey = "sk-abc",
            apiKeyRequired = true,
        )
        assertThat(result).isEqualTo(AiChannelInputValidation.BaseUrlInvalid)
    }

    @Test
    fun validateChannelInput_acceptsHttpForLocalOllama() {
        val result = AiSettingsSectionSupport.validateChannelInput(
            name = "Local Ollama",
            baseUrl = "http://localhost:11434/v1",
            apiKey = "n/a",
            apiKeyRequired = true,
        )
        assertThat(result).isEqualTo(AiChannelInputValidation.Valid)
    }

    @Test
    fun validateChannelInput_allowsEditingWithoutApiKey() {
        val resultRequired = AiSettingsSectionSupport.validateChannelInput(
            name = "Edit Case",
            baseUrl = "https://api.openai.com/v1",
            apiKey = "",
            apiKeyRequired = true,
        )
        assertThat(resultRequired).isEqualTo(AiChannelInputValidation.ApiKeyBlank)

        val resultOptional = AiSettingsSectionSupport.validateChannelInput(
            name = "Edit Case",
            baseUrl = "https://api.openai.com/v1",
            apiKey = "",
            apiKeyRequired = false,
        )
        assertThat(resultOptional).isEqualTo(AiChannelInputValidation.Valid)
    }

    @Test
    fun validateChannelInput_rejectsWhitespaceApiKeyWhenRequired() {
        val result = AiSettingsSectionSupport.validateChannelInput(
            name = "Edit Case",
            baseUrl = "https://api.openai.com/v1",
            apiKey = "   \n\r ",
            apiKeyRequired = true,
        )
        assertThat(result).isEqualTo(AiChannelInputValidation.ApiKeyBlank)
    }
}
