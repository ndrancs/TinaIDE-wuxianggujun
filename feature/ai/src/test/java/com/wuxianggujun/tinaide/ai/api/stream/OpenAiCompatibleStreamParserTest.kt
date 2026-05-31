package com.wuxianggujun.tinaide.ai.api.stream

import com.google.common.truth.Truth.assertThat
import com.wuxianggujun.tinaide.core.serialization.JsonSerializer
import kotlinx.serialization.json.Json
import org.junit.Test

class OpenAiCompatibleStreamParserTest {

    private val parser = OpenAiCompatibleStreamParser(
        Json(JsonSerializer.default) { explicitNulls = false }
    )

    @Test
    fun `parses OpenAI style delta with text content`() {
        val payload = """
            {"id":"c-1","choices":[{"delta":{"content":"Hello"}}]}
        """.trimIndent()
        val parsed = parser.parse(payload)!!
        assertThat(parsed.chunkId).isEqualTo("c-1")
        assertThat(parsed.textDeltas).containsExactly("Hello")
        assertThat(parsed.reasoningDeltas).isEmpty()
        assertThat(parsed.recognized).isTrue()
    }

    @Test
    fun `parses reasoning_content for thinking models`() {
        val payload = """
            {"choices":[{"delta":{"reasoning_content":"analysing"}}]}
        """.trimIndent()
        val parsed = parser.parse(payload)!!
        assertThat(parsed.reasoningDeltas).containsExactly("analysing")
        assertThat(parsed.textDeltas).isEmpty()
    }

    @Test
    fun `parses tool call deltas`() {
        val payload = """
            {"choices":[{"delta":{"tool_calls":[
                {"index":0,"id":"call_1","type":"function",
                 "function":{"name":"lookup","arguments":"{\"q\":\"hi\"}"}}
            ]}}]}
        """.trimIndent()
        val parsed = parser.parse(payload)!!
        assertThat(parsed.toolCallDeltas).hasSize(1)
        assertThat(parsed.toolCallDeltas[0].id).isEqualTo("call_1")
        assertThat(parsed.toolCallDeltas[0].function?.name).isEqualTo("lookup")
    }

    @Test
    fun `parses top level error to errorMessage`() {
        val payload = """
            {"error":{"message":"rate limited","code":429}}
        """.trimIndent()
        val parsed = parser.parse(payload)!!
        assertThat(parsed.errorMessage).isEqualTo("rate limited")
        assertThat(parsed.errorCode).isEqualTo(429)
        assertThat(parsed.recognized).isTrue()
    }

    @Test
    fun `returns null for unrelated JSON`() {
        val payload = """{"hello":"world"}"""
        assertThat(parser.parse(payload)).isNull()
    }

    @Test
    fun `returns null for malformed JSON`() {
        assertThat(parser.parse("not json")).isNull()
    }

    @Test
    fun `usage-only payload is recognized`() {
        val payload = """
            {"usage":{"prompt_tokens":10,"completion_tokens":5,"total_tokens":15}}
        """.trimIndent()
        val parsed = parser.parse(payload)!!
        assertThat(parsed.usage).isNotNull()
        assertThat(parsed.usage!!.totalTokens).isEqualTo(15)
        assertThat(parsed.recognized).isTrue()
    }

    @Test
    fun `parses message and message_delta content shapes`() {
        val payload = """
            {"choices":[
                {"message":{"content":[{"text":"Hello"},{"output_text":" world"}],"reasoning":"think"}},
                {"message_delta":{"content":{"value":"!"},"reasoning_text":"plan"}}
            ]}
        """.trimIndent()

        val parsed = parser.parse(payload)!!

        assertThat(parsed.textDeltas).containsExactly("Hello", " world", "!").inOrder()
        assertThat(parsed.reasoningDeltas).containsExactly("think", "plan").inOrder()
        assertThat(parsed.recognized).isTrue()
    }

    @Test
    fun `parses function_call delta with default choice index`() {
        val payload = """
            {"choices":[{"delta":{"function_call":{"name":"lookup","arguments":"{\"q\":\"hi\"}"}}}]}
        """.trimIndent()

        val parsed = parser.parse(payload)!!
        val delta = parsed.toolCallDeltas.single()

        assertThat(delta.index).isEqualTo(0)
        assertThat(delta.type).isEqualTo("function")
        assertThat(delta.function?.name).isEqualTo("lookup")
        assertThat(delta.function?.arguments).isEqualTo("{\"q\":\"hi\"}")
    }

    @Test
    fun `parses top level message with tool calls and reasoning alias`() {
        val payload = """
            {"id":"top-1","message":{
                "content":{"output_text":"answer"},
                "reasoningContent":"hidden",
                "tool_calls":[{"id":"call-top","type":"function","function":{"name":"read","arguments":"{}"}}]
            }}
        """.trimIndent()

        val parsed = parser.parse(payload)!!

        assertThat(parsed.chunkId).isEqualTo("top-1")
        assertThat(parsed.textDeltas).containsExactly("answer")
        assertThat(parsed.reasoningDeltas).containsExactly("hidden")
        assertThat(parsed.toolCallDeltas.single().id).isEqualTo("call-top")
    }

    @Test
    fun `error payload falls back to top level message and ignores invalid code`() {
        val payload = """
            {"message":"upstream failed","error":{"code":"not-a-number"}}
        """.trimIndent()

        val parsed = parser.parse(payload)!!

        assertThat(parsed.errorMessage).isEqualTo("upstream failed")
        assertThat(parsed.errorCode).isNull()
        assertThat(parsed.recognized).isTrue()
    }

    @Test
    fun `error payload falls back to raw payload when message is missing`() {
        val payload = """{"error":{"code":500}}"""

        val parsed = parser.parse(payload)!!

        assertThat(parsed.errorMessage).isEqualTo(payload)
        assertThat(parsed.errorCode).isEqualTo(500)
        assertThat(parsed.recognized).isTrue()
    }

    @Test
    fun `skips non object choices and malformed tool calls`() {
        val payload = """
            {"choices":[
                "ignored",
                {"delta":{"tool_calls":{"bad":true}}},
                {"text":"fallback text"}
            ]}
        """.trimIndent()

        val parsed = parser.parse(payload)!!

        assertThat(parsed.textDeltas).containsExactly("fallback text")
        assertThat(parsed.toolCallDeltas).isEmpty()
        assertThat(parsed.recognized).isTrue()
    }

    @Test
    fun `returns null for non object JSON roots`() {
        assertThat(parser.parse("[]")).isNull()
    }
}
