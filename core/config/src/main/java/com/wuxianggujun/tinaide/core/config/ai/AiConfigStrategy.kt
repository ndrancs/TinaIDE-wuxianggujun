package com.wuxianggujun.tinaide.core.config.ai

/**
 * AI 配置的策略纯函数集合，无 Android 依赖，便于 JVM 单元测试。
 *
 * [AiConfigProvider] 的实现绑定 SharedPreferences，访问模式解析放在这里
 * 可以用纯 JVM 测试覆盖。
 */
object AiConfigStrategy {

    fun resolveAccessMode(
        persistedAccessMode: String?,
    ): AiAccessMode = persistedAccessMode
        ?.let { raw -> runCatching { AiAccessMode.valueOf(raw) }.getOrNull() }
        ?: AiAccessMode.CUSTOM_BYOK

    /**
     * 开源版不再做 VIP 守卫。这里保留纯函数入口，方便以后集中处理
     * 备份恢复、脚本调用等非 UI 路径的配置归一化。
     */
    fun normalizeForOpenSource(config: AiConfig): AiConfig {
        return config
    }
}
