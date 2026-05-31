package com.wuxianggujun.tinaide.ai.api

import java.util.UUID
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/**
 * 聊天角色
 */
@Serializable
enum class ChatRole(val value: String) {
    SYSTEM("system"),
    USER("user"),
    ASSISTANT("assistant"),
    TOOL("tool")
}

/**
 * 聊天消息
 */
@Serializable
data class ChatMessage(
    val id: String = UUID.randomUUID().toString(),
    val role: ChatRole,
    val content: String,
    val contentParts: List<OpenAiContentPart>? = null,
    val reasoningContent: String? = null,
    val toolCalls: List<ToolCall>? = null,
    val toolCallId: String? = null,
    val usage: ChatUsage? = null,
    val timestamp: Long = System.currentTimeMillis()
)

@Serializable
data class OpenAiContentPart(
    val type: String,
    val text: String? = null,
    @SerialName("image_url") val imageUrl: OpenAiImageUrl? = null,
)

@Serializable
data class OpenAiImageUrl(
    val url: String,
    val detail: String? = null,
)

/**
 * 聊天对话
 */
data class ChatConversation(
    val id: String = UUID.randomUUID().toString(),
    val title: String = "",
    val messages: List<ChatMessage> = emptyList(),
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)

/**
 * 消息上下文
 */
sealed class MessageContext {
    data class CurrentFile(
        val fileName: String,
        val language: String,
        val content: String
    ) : MessageContext()

    data class SelectedCode(
        val fileName: String,
        val language: String,
        val content: String,
        val startLine: Int,
        val endLine: Int
    ) : MessageContext()

    data class Error(
        val errorMessage: String
    ) : MessageContext()
}

// ========== API 请求/响应模型 ==========

/**
 * API 请求
 */
@Serializable
data class ChatRequest(
    val model: String,
    val messages: List<ChatRequestMessage>,
    @SerialName("max_tokens") val maxTokens: Int,
    val temperature: Float,
    val stream: Boolean,
    @SerialName("stream_options") val streamOptions: StreamOptions? = null,
    val tools: List<ChatRequestTool>? = null,
    @SerialName("tool_choice") val toolChoice: String? = null,
    val thinking: ChatRequestThinking? = null
)

@Serializable
data class StreamOptions(
    @SerialName("include_usage") val includeUsage: Boolean = true
)

@Serializable
data class ChatRequestThinking(
    val type: String = "enabled",
    @SerialName("budget_tokens") val budgetTokens: Int = 10000
)

@Serializable
data class ChatRequestMessage(
    val role: String,
    val content: JsonElement? = null,
    @SerialName("tool_call_id") val toolCallId: String? = null,
    val name: String? = null,
    @SerialName("tool_calls") val toolCalls: List<ToolCall>? = null
)

@Serializable
data class ChatRequestTool(
    val type: String = "function",
    val function: ChatRequestToolFunction
)

@Serializable
data class ChatRequestToolFunction(
    val name: String,
    val description: String? = null,
    val parameters: JsonElement? = null
)

/**
 * API 响应（非流式）
 */
@Serializable
data class ChatResponse(
    val id: String,
    val choices: List<ChatChoice>,
    val usage: ChatUsage?
)

@Serializable
data class ChatChoice(
    val index: Int,
    val message: ChatResponseMessage,
    @SerialName("finish_reason") val finishReason: String?
)

@Serializable
data class ChatResponseMessage(
    val role: String,
    val content: String? = null,
    @SerialName("reasoning_content") val reasoningContent: String? = null,
    @SerialName("tool_calls") val toolCalls: List<ToolCall>? = null
)

@Serializable
data class ChatUsage(
    @SerialName("prompt_tokens") val promptTokens: Int,
    @SerialName("completion_tokens") val completionTokens: Int,
    @SerialName("total_tokens") val totalTokens: Int
)

@Serializable
data class ToolCall(
    val id: String? = null,
    val type: String? = null,
    val function: ToolFunction? = null,
    // 执行状态和结果（不发送给 API，仅用于 UI 显示）
    @kotlinx.serialization.Transient
    val executionStatus: ToolExecutionStatus = ToolExecutionStatus.PENDING,
    @kotlinx.serialization.Transient
    val executionResult: String? = null,
    @kotlinx.serialization.Transient
    val executionError: String? = null
)

/**
 * 工具执行状态
 */
enum class ToolExecutionStatus {
    PENDING, // 待执行
    EXECUTING, // 执行中
    SUCCESS, // 执行成功
    FAILED, // 执行失败
    CANCELLED // 已取消
}

/**
 * 工具执行状态数据（用于序列化）
 */
@Serializable
data class ToolExecutionState(
    val status: String, // ToolExecutionStatus.name
    val result: String? = null,
    val error: String? = null
)

@Serializable
data class ToolFunction(
    val name: String? = null,
    val arguments: String? = null
)

@Serializable
data class ToolCallDelta(
    val index: Int? = null,
    val id: String? = null,
    val type: String? = null,
    val function: ToolFunctionDelta? = null
)

@Serializable
data class ToolFunctionDelta(
    val name: String? = null,
    val arguments: String? = null
)

/**
 * API 异常
 *
 * @param retryAfterMillis HTTP 429 响应可能带 `Retry-After` 头；解析后塞到这里，
 *                          [com.wuxianggujun.tinaide.ai.retry.RetryPolicy] 优先使用它。
 */
class ApiException(
    val code: Int,
    message: String,
    val retryAfterMillis: Long? = null,
) : Exception(message)
