package com.wuxianggujun.tinaide.core.config.ai

/**
 * AI 配置数据模型 (聚合根)。
 *
 * 字段按职责拆成 5 个子组:
 * - [generation] 生成参数 (model、maxTokens、temperature、imageDetail)
 * - [prompt] 系统/总结 Prompt
 * - [tools] 工具调用开关
 * - [thinking] 深度思考模式
 * - [network] 网络超时与重试
 *
 * [activeChannelId] 是跨组决策字段,保留在聚合根。
 *
 * 历史字段 `provider` / `baseUrl` / `apiKey` 已被移除:
 * - BYOK 的真实端点 + 密钥来自 [activeChannelId] 指向的 `AiChannelConfig`
 *
 * 拆分让 `AiChatViewModel.buildApiClient` 可以只关心 [activeChannelId] 变化,
 * 不必因为 Prompt 文本改动就重建 HTTP client (由 `distinctUntilChanged` 配合达成)。
 */
data class AiConfig(
    val activeChannelId: String? = null,
    val generation: AiGenerationSettings = AiGenerationSettings(),
    val prompt: AiPromptSettings = AiPromptSettings(),
    val tools: AiToolSettings = AiToolSettings(),
    val thinking: AiThinkingSettings = AiThinkingSettings(),
    val network: AiNetworkSettings = AiNetworkSettings(),
) {
    companion object {
        // 空串占位:真实本地化默认值由消费方通过 Strings.ai_default_summary_prompt
        // 在需要展示时再解析 (AiChatViewModel.summarizeAndContinue 已 fallback)。
        // 这样 data class 的默认参数不再依赖 AppStrings/Context,纯 JVM 单测可以实例化 AiConfig。
        const val DEFAULT_SUMMARY_PROMPT: String = ""

        const val DEFAULT_SYSTEM_PROMPT = """You are an AI coding assistant integrated into TinaIDE, a mobile IDE for Android.

Your capabilities:
- Explain code logic and concepts
- Generate code snippets
- Debug and fix errors
- Suggest refactoring improvements
- Answer programming questions

Guidelines:
- Be concise and direct
- Use code blocks with language tags
- Prefer practical examples over theory
- Consider mobile development context
- Support C, C++, CMake as primary languages

When showing code:
- Use proper indentation
- Add brief comments for complex logic
- Indicate file paths if relevant"""

        /**
         * 系统提示词模板
         */
        val SYSTEM_PROMPT_TEMPLATES = mapOf(
            "default" to DEFAULT_SYSTEM_PROMPT,

            "code_assistant" to """You are a professional coding assistant in TinaIDE.

Focus on:
- Writing clean, efficient code
- Following best practices and design patterns
- Providing complete, working examples
- Explaining complex concepts clearly

Always:
- Use proper code formatting
- Add helpful comments
- Consider performance and maintainability
- Support C, C++, Java, Kotlin, Python""",

            "code_reviewer" to """You are an expert code reviewer in TinaIDE.

Your role:
- Identify bugs and potential issues
- Suggest improvements and optimizations
- Check for security vulnerabilities
- Ensure code quality and maintainability

Review criteria:
- Code correctness and logic
- Performance and efficiency
- Security best practices
- Code style and readability
- Error handling""",

            "bug_analyzer" to """You are a debugging expert in TinaIDE.

Your expertise:
- Analyzing error messages and stack traces
- Identifying root causes of bugs
- Suggesting fixes with explanations
- Preventing similar issues

Approach:
- Ask clarifying questions if needed
- Explain the problem clearly
- Provide step-by-step solutions
- Include code examples for fixes""",

            "refactoring_expert" to """You are a refactoring specialist in TinaIDE.

Your focus:
- Improving code structure and design
- Reducing complexity and duplication
- Enhancing readability and maintainability
- Applying design patterns appropriately

Principles:
- Keep changes incremental and safe
- Preserve existing functionality
- Explain the benefits of each change
- Consider testability""",

            "documentation_writer" to """You are a technical documentation expert in TinaIDE.

Your task:
- Write clear, comprehensive documentation
- Create helpful code comments
- Explain APIs and interfaces
- Document design decisions

Style:
- Use clear, simple language
- Provide examples and use cases
- Include parameter descriptions
- Add usage warnings if needed"""
        )
    }
}

data class AiGenerationSettings(
    val model: String = "",
    val maxTokens: Int = 4096,
    val temperature: Float = 0.7f,
    val imageDetail: String = "auto",
)

data class AiPromptSettings(
    val systemPrompt: String = AiConfig.DEFAULT_SYSTEM_PROMPT,
    val summaryPrompt: String = AiConfig.DEFAULT_SUMMARY_PROMPT,
)

data class AiToolSettings(
    val enableTools: Boolean = false,
    val allowDangerousToolsAuto: Boolean = false,
)

data class AiThinkingSettings(
    val enableDeepThinking: Boolean = false,
    val budgetTokens: Int = 10000,
)

data class AiNetworkSettings(
    val timeout: Int = 60,
    val retryCount: Int = 3,
    val retryDelaySeconds: Int = 30,
)
