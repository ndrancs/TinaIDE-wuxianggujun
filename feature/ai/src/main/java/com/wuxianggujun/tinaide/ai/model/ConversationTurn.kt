package com.wuxianggujun.tinaide.ai.model

import com.wuxianggujun.tinaide.ai.api.ChatMessage
import com.wuxianggujun.tinaide.ai.api.ChatRole
import com.wuxianggujun.tinaide.ai.api.ToolCall
import java.util.UUID
import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable

/**
 * 对话回合
 * 一个回合包含：用户消息 + AI回复（可能包含多次工具调用和继续回复）
 */
@Serializable
data class ConversationTurn(
    val id: String = UUID.randomUUID().toString(),
    @Contextual val userMessage: ChatMessage,
    val assistantResponses: List<AssistantResponse> = emptyList(),
    val status: TurnStatus = TurnStatus.USER_SENT,
    val timestamp: Long = System.currentTimeMillis()
) {
    /**
     * 获取该回合的所有消息（用于发送给API）
     */
    fun getAllMessages(): List<ChatMessage> {
        val messages = mutableListOf(userMessage)
        assistantResponses.forEach { response ->
            messages.add(response.message)
            response.actions.forEach { action ->
                messages.addAll(action.getToolResultMessages())
            }
        }
        return messages
    }

    /**
     * 是否有待执行的动作
     */
    fun hasPendingActions(): Boolean = assistantResponses.lastOrNull()?.let { lastResponse ->
        lastResponse.actions.any { it.status == ActionStatus.PENDING }
    } ?: false

    /**
     * 是否完成（没有待执行的动作）
     */
    fun isCompleted(): Boolean = status == TurnStatus.COMPLETED || !hasPendingActions()
}

/**
 * AI助手的一次响应（可能包含多个动作）
 */
@Serializable
data class AssistantResponse(
    val id: String = UUID.randomUUID().toString(),
    @Contextual val message: ChatMessage,
    val actions: List<ToolAction> = emptyList(),
    val timestamp: Long = System.currentTimeMillis()
)
/**
 * 工具动作 - 封装工具调用的执行层
 */
@Serializable
data class ToolAction(
    val id: String = UUID.randomUUID().toString(),
    val name: String, // 动作名称（如 "获取当前文件"）
    val description: String? = null, // 动作描述
    val toolCalls: List<ToolCall> = emptyList(), // 实际的工具调用
    val toolResults: List<ToolResult> = emptyList(), // 工具执行结果
    val status: ActionStatus = ActionStatus.PENDING,
    val timestamp: Long = System.currentTimeMillis()
) {
    /**
     * 获取工具结果消息（用于发送给API）
     */
    fun getToolResultMessages(): List<ChatMessage> = toolResults.map { result ->
        ChatMessage(
            role = ChatRole.TOOL,
            content = result.content,
            toolCallId = result.toolCallId
        )
    }

    /**
     * 是否所有工具调用都已完成
     */
    fun isAllToolsCompleted(): Boolean = toolCalls.size == toolResults.size
}

/**
 * 工具执行结果
 */
@Serializable
data class ToolResult(
    val toolCallId: String,
    val content: String,
    val success: Boolean = true,
    val timestamp: Long = System.currentTimeMillis()
)
/**
 * 动作状态
 */
@Serializable
enum class ActionStatus {
    PENDING, // 待执行
    EXECUTING, // 执行中
    COMPLETED, // 已完成
    FAILED, // 失败
    CANCELLED // 已取消
}

/**
 * 回合状态
 */
@Serializable
enum class TurnStatus {
    USER_SENT, // 用户已发送消息
    AI_RESPONDING, // AI正在回复
    ACTION_PENDING, // 有动作待执行
    ACTION_EXECUTING, // 动作正在执行
    AI_CONTINUING, // AI继续回复（动作执行后）
    COMPLETED, // 回合完成
    ERROR // 出错
}

/**
 * 工具执行模式配置
 */
@Serializable
enum class ToolExecutionMode {
    AUTO, // 自动执行工具并继续AI回复
    MANUAL // 手动触发工具执行和AI回复
}

/**
 * AI面板配置
 */
@Serializable
data class AiPanelConfig(
    val toolExecutionMode: ToolExecutionMode = ToolExecutionMode.AUTO,
    val autoScrollToBottom: Boolean = true,
    val showTokenUsage: Boolean = true
)
