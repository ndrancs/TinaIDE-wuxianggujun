package com.wuxianggujun.tinaide.core.config.ai

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * [AiConfigStrategy] 单元测试，覆盖访问模式解析与开源版配置归一化。
 */
class AiConfigStrategyTest {

    @Test
    fun `resolveAccessMode without persisted mode returns CUSTOM_BYOK`() {
        val result = AiConfigStrategy.resolveAccessMode(persistedAccessMode = null)

        assertThat(result).isEqualTo(AiAccessMode.CUSTOM_BYOK)
    }

    @Test
    fun `resolveAccessMode honors persisted BYOK mode`() {
        val result = AiConfigStrategy.resolveAccessMode(
            persistedAccessMode = AiAccessMode.CUSTOM_BYOK.name,
        )

        assertThat(result).isEqualTo(AiAccessMode.CUSTOM_BYOK)
    }

    @Test
    fun `resolveAccessMode falls back to custom BYOK on unknown mode string`() {
        val result = AiConfigStrategy.resolveAccessMode(
            persistedAccessMode = "FUTURE_MODE",
        )

        assertThat(result).isEqualTo(AiAccessMode.CUSTOM_BYOK)
    }

    // ==================== normalizeForOpenSource ====================

    // AiConfig 的所有子配置都有无依赖 Context 的默认值,此处直接 new 即可。

    private fun sampleConfig(accessMode: AiAccessMode): AiConfig = AiConfig(accessMode = accessMode)

    @Test
    fun `normalizeForOpenSource leaves BYOK untouched`() {
        val input = sampleConfig(AiAccessMode.CUSTOM_BYOK)
        val result = AiConfigStrategy.normalizeForOpenSource(input)
        assertThat(result).isSameInstanceAs(input)
    }

    @Test
    fun `normalizeForOpenSource leaves Gateway unchanged`() {
        val input = sampleConfig(AiAccessMode.TINA_GATEWAY)
        assertThat(AiConfigStrategy.normalizeForOpenSource(input))
            .isSameInstanceAs(input)
    }
}
