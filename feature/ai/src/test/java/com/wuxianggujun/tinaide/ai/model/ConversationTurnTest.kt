package com.wuxianggujun.tinaide.ai.model

import com.google.common.truth.Truth.assertThat
import com.wuxianggujun.tinaide.ai.api.ChatMessage
import com.wuxianggujun.tinaide.ai.api.ChatRole
import com.wuxianggujun.tinaide.ai.api.ToolCall
import org.junit.Test

class ConversationTurnTest {

    @Test
    fun `getAllMessages returns user assistant and tool result messages in order`() {
        val user = ChatMessage(id = "user-1", role = ChatRole.USER, content = "read file")
        val assistant = ChatMessage(id = "assistant-1", role = ChatRole.ASSISTANT, content = "calling tool")
        val action = ToolAction(
            name = "Read File",
            toolResults = listOf(ToolResult(toolCallId = "call-1", content = "file content")),
            status = ActionStatus.COMPLETED,
        )
        val turn = ConversationTurn(
            userMessage = user,
            assistantResponses = listOf(AssistantResponse(message = assistant, actions = listOf(action))),
        )

        val messages = turn.getAllMessages()

        assertThat(messages.map { it.role }).containsExactly(ChatRole.USER, ChatRole.ASSISTANT, ChatRole.TOOL).inOrder()
        assertThat(messages[2].toolCallId).isEqualTo("call-1")
        assertThat(messages[2].content).isEqualTo("file content")
    }

    @Test
    fun `pending actions are evaluated from the latest assistant response`() {
        val pendingTurn = ConversationTurn(
            userMessage = ChatMessage(role = ChatRole.USER, content = "go"),
            assistantResponses = listOf(
                AssistantResponse(
                    message = ChatMessage(role = ChatRole.ASSISTANT, content = "wait"),
                    actions = listOf(ToolAction(name = "Build", status = ActionStatus.PENDING)),
                ),
            ),
        )
        val completedTurn = pendingTurn.copy(
            status = TurnStatus.COMPLETED,
            assistantResponses = listOf(
                AssistantResponse(
                    message = ChatMessage(role = ChatRole.ASSISTANT, content = "done"),
                    actions = listOf(ToolAction(name = "Build", status = ActionStatus.COMPLETED)),
                ),
            ),
        )

        assertThat(pendingTurn.hasPendingActions()).isTrue()
        assertThat(pendingTurn.isCompleted()).isFalse()
        assertThat(completedTurn.hasPendingActions()).isFalse()
        assertThat(completedTurn.isCompleted()).isTrue()
    }

    @Test
    fun `tool action completion compares calls and results`() {
        val call1 = ToolCall(id = "call-1")
        val call2 = ToolCall(id = "call-2")

        val partial = ToolAction(
            name = "Read Files",
            toolCalls = listOf(call1, call2),
            toolResults = listOf(ToolResult(toolCallId = "call-1", content = "one")),
        )
        val complete = partial.copy(
            toolResults = listOf(
                ToolResult(toolCallId = "call-1", content = "one"),
                ToolResult(toolCallId = "call-2", content = "two"),
            ),
        )

        assertThat(partial.isAllToolsCompleted()).isFalse()
        assertThat(complete.isAllToolsCompleted()).isTrue()
    }
}
