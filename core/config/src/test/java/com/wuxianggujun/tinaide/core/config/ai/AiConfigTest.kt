package com.wuxianggujun.tinaide.core.config.ai

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * AiConfig 纯 JVM 单元测试。
 *
 * 迭代 3 (2026-04-20) 将原扁平字段拆成 5 个子 Settings,
 * 本测试同步验证默认值与自定义组合行为。
 */
class AiConfigTest {

    // ==================== 默认值测试 ====================

    @Test
    fun `default activeChannelId is null`() {
        val config = AiConfig()
        assertThat(config.activeChannelId).isNull()
    }

    @Test
    fun `default generation settings are BYOK friendly`() {
        val gen = AiConfig().generation
        assertThat(gen.model).isEmpty()
        assertThat(gen.maxTokens).isEqualTo(4096)
        assertThat(gen.temperature).isWithin(0.001f).of(0.7f)
        assertThat(gen.imageDetail).isEqualTo("auto")
    }

    @Test
    fun `default prompt settings use DEFAULT_SYSTEM_PROMPT`() {
        val prompt = AiConfig().prompt
        assertThat(prompt.systemPrompt).isEqualTo(AiConfig.DEFAULT_SYSTEM_PROMPT)
        assertThat(prompt.summaryPrompt).isEqualTo(AiConfig.DEFAULT_SUMMARY_PROMPT)
    }

    @Test
    fun `default tool settings are disabled`() {
        val tools = AiConfig().tools
        assertThat(tools.enableTools).isFalse()
        assertThat(tools.allowDangerousToolsAuto).isFalse()
    }

    @Test
    fun `default thinking settings are disabled`() {
        val thinking = AiConfig().thinking
        assertThat(thinking.enableDeepThinking).isFalse()
        assertThat(thinking.budgetTokens).isEqualTo(10000)
    }

    @Test
    fun `default network settings pick sane timeouts`() {
        val net = AiConfig().network
        assertThat(net.timeout).isEqualTo(60)
        assertThat(net.retryCount).isEqualTo(3)
        assertThat(net.retryDelaySeconds).isEqualTo(30)
    }

    // ==================== 自定义配置测试 ====================

    @Test
    fun `activeChannelId can be customized`() {
        val config = AiConfig(
            activeChannelId = "ch-1",
        )
        assertThat(config.activeChannelId).isEqualTo("ch-1")
    }

    @Test
    fun `sub-settings can be updated independently via copy`() {
        val base = AiConfig()
        val updated = base.copy(
            generation = base.generation.copy(maxTokens = 8192, temperature = 0.2f),
            tools = base.tools.copy(enableTools = true),
        )
        // 未改动的子组保持同一实例引用 → 便于 distinctUntilChanged 识别"未变更"
        assertThat(updated.prompt).isSameInstanceAs(base.prompt)
        assertThat(updated.thinking).isSameInstanceAs(base.thinking)
        assertThat(updated.network).isSameInstanceAs(base.network)
        assertThat(updated.generation.maxTokens).isEqualTo(8192)
        assertThat(updated.tools.enableTools).isTrue()
    }

    // ==================== 系统提示词模板测试 ====================

    @Test
    fun `system prompt templates contain default template`() {
        assertThat(AiConfig.SYSTEM_PROMPT_TEMPLATES).containsKey("default")
    }

    @Test
    fun `system prompt templates contain all expected keys`() {
        val expectedKeys = setOf(
            "default",
            "code_assistant",
            "code_reviewer",
            "bug_analyzer",
            "refactoring_expert",
            "documentation_writer",
        )
        assertThat(AiConfig.SYSTEM_PROMPT_TEMPLATES.keys).containsExactlyElementsIn(expectedKeys)
    }

    @Test
    fun `default template matches DEFAULT_SYSTEM_PROMPT constant`() {
        assertThat(AiConfig.SYSTEM_PROMPT_TEMPLATES["default"])
            .isEqualTo(AiConfig.DEFAULT_SYSTEM_PROMPT)
    }

    @Test
    fun `all templates are non-empty`() {
        AiConfig.SYSTEM_PROMPT_TEMPLATES.forEach { (_, value) ->
            assertThat(value).isNotEmpty()
        }
    }

    // ==================== AiProvider 测试 ====================

    @Test
    fun `AiProvider fromName returns correct provider`() {
        assertThat(AiProvider.fromName("OPENAI")).isEqualTo(AiProvider.OPENAI)
        assertThat(AiProvider.fromName("DEEPSEEK")).isEqualTo(AiProvider.DEEPSEEK)
        assertThat(AiProvider.fromName("QWEN")).isEqualTo(AiProvider.QWEN)
    }

    @Test
    fun `AiProvider fromName returns DEEPSEEK for unknown name`() {
        assertThat(AiProvider.fromName("NONEXISTENT")).isEqualTo(AiProvider.DEEPSEEK)
    }

    @Test
    fun `CUSTOM provider has empty baseUrl and no default models`() {
        assertThat(AiProvider.CUSTOM.defaultBaseUrl).isEmpty()
        assertThat(AiProvider.CUSTOM.defaultModels).isEmpty()
    }

    @Test
    fun `OLLAMA provider uses localhost`() {
        assertThat(AiProvider.OLLAMA.defaultBaseUrl).contains("localhost")
    }
}
