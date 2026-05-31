package com.wuxianggujun.tinaide.core.config.ai

/**
 * AI 服务商枚举。
 *
 * 这里只保留配置持久化和请求所需的稳定字段，用户可见名称由 UI 层通过 i18n 资源解析。
 */
enum class AiProvider(
    val defaultBaseUrl: String,
    val defaultModels: List<String>,
) {
    OPENAI(
        defaultBaseUrl = "https://api.openai.com/v1",
        defaultModels = listOf("gpt-4o", "gpt-4o-mini", "gpt-4-turbo", "gpt-3.5-turbo"),
    ),
    DEEPSEEK(
        defaultBaseUrl = "https://api.deepseek.com/v1",
        defaultModels = listOf("deepseek-chat", "deepseek-coder"),
    ),
    QWEN(
        defaultBaseUrl = "https://dashscope.aliyuncs.com/compatible-mode/v1",
        defaultModels = listOf("qwen-turbo", "qwen-plus", "qwen-max"),
    ),
    ZHIPU(
        defaultBaseUrl = "https://open.bigmodel.cn/api/paas/v4",
        defaultModels = listOf("glm-4", "glm-4-flash", "glm-3-turbo"),
    ),
    OLLAMA(
        defaultBaseUrl = "http://localhost:11434/v1",
        defaultModels = listOf("llama3", "codellama", "deepseek-coder"),
    ),
    CUSTOM(
        defaultBaseUrl = "",
        defaultModels = emptyList(),
    );

    companion object {
        fun fromName(name: String): AiProvider {
            return entries.find { it.name == name } ?: DEEPSEEK
        }
    }
}
