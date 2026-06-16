package com.wuxianggujun.tinaide.ai.api

import android.content.Context
import com.google.common.truth.Truth.assertThat
import com.wuxianggujun.tinaide.ai.tools.AiTool
import com.wuxianggujun.tinaide.ai.tools.ToolCategory
import com.wuxianggujun.tinaide.ai.tools.ToolExecutionContext
import com.wuxianggujun.tinaide.ai.tools.ToolExecutionResult
import com.wuxianggujun.tinaide.ai.tools.ToolRegistry
import com.wuxianggujun.tinaide.core.config.ai.AiConfig
import com.wuxianggujun.tinaide.core.config.ai.AiGenerationSettings
import com.wuxianggujun.tinaide.core.config.ai.AiThinkingSettings
import com.wuxianggujun.tinaide.core.config.ai.AiToolSettings
import com.wuxianggujun.tinaide.core.i18n.AppStrings
import com.wuxianggujun.tinaide.core.i18n.Strings
import com.wuxianggujun.tinaide.core.i18n.str
import com.wuxianggujun.tinaide.core.network.ApiResult
import io.mockk.every
import io.mockk.mockk
import java.io.IOException
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import okio.BufferedSource
import okio.Source
import okio.Timeout
import okio.buffer
import org.junit.After
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AiApiClientTest {

    @Before
    fun setUp() {
        resetAppStrings()
        val context = mockk<Context>()
        every { context.applicationContext } returns context
        every { context.getString(any<Int>()) } answers { "string-${firstArg<Int>()}" }
        every { context.getString(any<Int>(), *anyVararg()) } answers { "string-${firstArg<Int>()}-formatted" }
        AppStrings.initialize(context)
        ToolRegistry.clear()
    }

    @After
    fun tearDown() {
        ToolRegistry.clear()
        resetAppStrings()
    }

    @Test
    fun `chat posts normalized OpenAI compatible request`() = runTest {
        val capturedRequest = AtomicReference<Request>()
        val client = newClient(
            capturedRequest = capturedRequest,
            responseBody = """
                {
                  "id":"resp-1",
                  "choices":[{"index":0,"message":{"role":"assistant","content":"ok"},"finish_reason":"stop"}],
                  "usage":{"prompt_tokens":1,"completion_tokens":2,"total_tokens":3}
                }
            """.trimIndent()
        )

        val result = client.chat(
            messages = listOf(ChatMessage(role = ChatRole.USER, content = "hello")),
            useTool = false
        )

        assertThat(result).isInstanceOf(ApiResult.Success::class.java)
        val response = (result as ApiResult.Success).data
        assertThat(response.choices.single().message.content).isEqualTo("ok")

        val request = capturedRequest.get()
        assertThat(request.method).isEqualTo("POST")
        assertThat(request.url.toString()).isEqualTo("https://example.test/v1/chat/completions")
        assertThat(request.header("Authorization")).isEqualTo("Bearer secret")

        val body = request.bodyToJsonObject()
        assertThat(body["model"]?.jsonPrimitive?.content).isEqualTo("gpt-test")
        assertThat(body["stream"]?.jsonPrimitive?.content).isEqualTo("false")
        assertThat(body["tool_choice"]).isNull()
        assertThat(body["tools"]).isNull()
        assertThat(body["thinking"]?.jsonObject?.get("budget_tokens")?.jsonPrimitive?.content)
            .isEqualTo("2048")
        assertThat(body["messages"]?.jsonArray?.single()?.jsonObject?.get("content")?.jsonPrimitive?.content)
            .isEqualTo("hello")
    }

    @Test
    fun `chat returns api error message from nested error body`() = runTest {
        val client = newClient(
            responseCode = 429,
            responseMessage = "Too Many Requests",
            responseBody = """{"error":{"message":"slow down"}}"""
        )

        val result = client.chat(
            messages = listOf(ChatMessage(role = ChatRole.USER, content = "hello")),
            useTool = false
        )

        assertThat(result).isEqualTo(ApiResult.Error(429, "slow down"))
    }

    @Test
    fun `chat returns api error message from primitive error body`() = runTest {
        val client = newClient(
            responseCode = 400,
            responseMessage = "Bad Request",
            responseBody = """{"error":"bad request from proxy"}"""
        )

        val result = client.chat(
            messages = listOf(ChatMessage(role = ChatRole.USER, content = "hello")),
            useTool = false
        )

        assertThat(result).isEqualTo(ApiResult.Error(400, "bad request from proxy"))
    }

    @Test
    fun `chat returns validation messages from array detail body`() = runTest {
        val client = newClient(
            responseCode = 422,
            responseMessage = "Unprocessable Entity",
            responseBody = """{"detail":[{"msg":"model is required"},{"message":"temperature is invalid"}]}"""
        )

        val result = client.chat(
            messages = listOf(ChatMessage(role = ChatRole.USER, content = "hello")),
            useTool = false
        )

        assertThat(result).isEqualTo(ApiResult.Error(422, "model is required; temperature is invalid"))
    }

    @Test
    fun `chat falls back to response message and formatted http status`() = runTest {
        val messageClient = newClient(
            responseCode = 502,
            responseMessage = "Bad Gateway",
            responseBody = "",
        )
        val statusClient = newClient(
            responseCode = 500,
            responseMessage = "",
            responseBody = "",
        )

        val messageResult = messageClient.chat(
            messages = listOf(ChatMessage(role = ChatRole.USER, content = "hello")),
            useTool = false,
        )
        val statusResult = statusClient.chat(
            messages = listOf(ChatMessage(role = ChatRole.USER, content = "hello")),
            useTool = false,
        )

        assertThat(messageResult).isEqualTo(ApiResult.Error(502, "Bad Gateway"))
        assertThat(statusResult).isEqualTo(ApiResult.Error(500, Strings.ai_error_http_status.str(500)))
    }

    @Test
    fun `chat returns empty response error when response body is blank`() = runTest {
        val client = newClient(responseBody = "")

        val result = client.chat(
            messages = listOf(ChatMessage(role = ChatRole.USER, content = "hello")),
            useTool = false
        )

        assertThat(result).isEqualTo(ApiResult.Error(-1, Strings.ai_error_empty_response.str()))
    }

    @Test
    fun `chat returns network error for malformed success body`() = runTest {
        val client = newClient(responseBody = "not-json")

        val result = client.chat(
            messages = listOf(ChatMessage(role = ChatRole.USER, content = "hello")),
            useTool = false
        )

        assertThat(result).isInstanceOf(ApiResult.NetworkError::class.java)
    }

    @Test
    fun `chat rethrows cancellation exception`() = runTest {
        val client = newClient(
            responseBody = "{}",
            networkError = CancellationException("cancelled"),
        )

        try {
            client.chat(
                messages = listOf(ChatMessage(role = ChatRole.USER, content = "hello")),
                useTool = false,
            )
            throw AssertionError("CancellationException was not rethrown")
        } catch (e: CancellationException) {
            assertThat(e.message).isEqualTo("cancelled")
        }
    }

    @Test
    fun `chat includes registered tools when enabled`() = runTest {
        ToolRegistry.register(fakeTool("lookup_code"))
        val capturedRequest = AtomicReference<Request>()
        val client = newClient(
            capturedRequest = capturedRequest,
            responseBody = successfulChatResponse,
            enableTools = true,
        )

        val result = client.chat(
            messages = listOf(ChatMessage(role = ChatRole.USER, content = "hello")),
            useTool = true,
        )

        assertThat(result).isInstanceOf(ApiResult.Success::class.java)
        val body = capturedRequest.get().bodyToJsonObject()
        assertThat(body["tool_choice"]?.jsonPrimitive?.content).isEqualTo("auto")
        val tool = body["tools"]?.jsonArray?.single()?.jsonObject
        assertThat(tool?.get("function")?.jsonObject?.get("name")?.jsonPrimitive?.content)
            .isEqualTo("lookup_code")
        assertThat(tool?.get("function")?.jsonObject?.get("parameters")?.jsonObject).isNotNull()
    }

    @Test
    fun `chat serializes assistant tool and content part messages`() = runTest {
        val capturedRequest = AtomicReference<Request>()
        val client = newClient(
            capturedRequest = capturedRequest,
            responseBody = successfulChatResponse,
        )

        val result = client.chat(
            messages = listOf(
                ChatMessage(
                    role = ChatRole.ASSISTANT,
                    content = "",
                    toolCalls = listOf(
                        ToolCall(
                            id = "call-1",
                            type = "function",
                            function = ToolFunction(name = "lookup", arguments = "{}"),
                        )
                    ),
                ),
                ChatMessage(role = ChatRole.TOOL, content = "lookup result", toolCallId = "call-1"),
                ChatMessage(
                    role = ChatRole.USER,
                    content = "",
                    contentParts = listOf(
                        OpenAiContentPart(type = "text", text = "see this"),
                        OpenAiContentPart(
                            type = "image_url",
                            imageUrl = OpenAiImageUrl(url = "data:image/png;base64,abc", detail = "low"),
                        ),
                    ),
                ),
            ),
            useTool = false,
        )

        assertThat(result).isInstanceOf(ApiResult.Success::class.java)
        val messages = capturedRequest.get().bodyToJsonObject()["messages"]!!.jsonArray

        val assistant = messages[0].jsonObject
        assertThat(assistant["role"]?.jsonPrimitive?.content).isEqualTo("assistant")
        assertThat(assistant["content"]).isNull()
        assertThat(assistant["tool_calls"]?.jsonArray?.single()?.jsonObject?.get("id")?.jsonPrimitive?.content)
            .isEqualTo("call-1")

        val tool = messages[1].jsonObject
        assertThat(tool["role"]?.jsonPrimitive?.content).isEqualTo("tool")
        assertThat(tool["content"]?.jsonPrimitive?.content).isEqualTo("lookup result")
        assertThat(tool["tool_call_id"]?.jsonPrimitive?.content).isEqualTo("call-1")

        val userContent = messages[2].jsonObject["content"]!!.jsonArray
        assertThat(userContent[0].jsonObject["text"]?.jsonPrimitive?.content).isEqualTo("see this")
        assertThat(userContent[1].jsonObject["image_url"]?.jsonObject?.get("detail")?.jsonPrimitive?.content)
            .isEqualTo("low")
    }

    @Test
    fun `chat stream emits text reasoning usage tool calls and done`() = runTest {
        val capturedRequest = AtomicReference<Request>()
        val client = newClient(
            capturedRequest = capturedRequest,
            responseContentType = "text/event-stream",
            responseBody = """
                data: {"id":"stream-1","choices":[{"index":0,"delta":{"reasoning_content":"thinking ","content":"Hello "}}]}

                data: {"id":"stream-1","choices":[{"index":0,"delta":{"tool_calls":[{"index":0,"id":"call_1","type":"function","function":{"name":"lookup","arguments":"{\"q\":\""}}]}}]}

                data: {"id":"stream-1","choices":[{"index":0,"delta":{"tool_calls":[{"index":0,"function":{"arguments":"hi\"}"}}]}}],"usage":{"prompt_tokens":3,"completion_tokens":4,"total_tokens":7}}

                data: {"id":"stream-1","choices":[{"index":0,"delta":{"content":"world"}}]}

                data: [DONE]

            """.trimIndent(),
        )
        val events = CopyOnWriteArrayList<AiApiClient.ChatStreamEvent>()
        val error = AtomicReference<Throwable?>()
        val done = CountDownLatch(1)

        client.chatStream(
            messages = listOf(ChatMessage(role = ChatRole.USER, content = "hello")),
            onEvent = { event ->
                events += event
                if (event is AiApiClient.ChatStreamEvent.Done) done.countDown()
            },
            onError = { throwable ->
                error.set(throwable)
                done.countDown()
            },
            useTool = false,
        )

        assertThat(done.await(3, TimeUnit.SECONDS)).isTrue()
        assertThat(error.get()).isNull()

        val request = capturedRequest.get()
        assertThat(request.method).isEqualTo("POST")
        assertThat(request.url.toString()).isEqualTo("https://example.test/v1/chat/completions")
        assertThat(request.header("Authorization")).isEqualTo("Bearer secret")
        assertThat(request.bodyToJsonObject()["stream"]?.jsonPrimitive?.content).isEqualTo("true")

        assertThat(events.filterIsInstance<AiApiClient.ChatStreamEvent.TextDelta>().map { it.text })
            .containsExactly("Hello ", "world")
            .inOrder()
        assertThat(events.filterIsInstance<AiApiClient.ChatStreamEvent.ReasoningDelta>().single().text)
            .isEqualTo("thinking ")
        assertThat(events.filterIsInstance<AiApiClient.ChatStreamEvent.Usage>().single().usage.totalTokens)
            .isEqualTo(7)

        val toolCall = events
            .filterIsInstance<AiApiClient.ChatStreamEvent.ToolCallsUpdate>()
            .last()
            .toolCalls
            .single()
        assertThat(toolCall.id).isEqualTo("call_1")
        assertThat(toolCall.type).isEqualTo("function")
        assertThat(toolCall.function?.name).isEqualTo("lookup")
        assertThat(toolCall.function?.arguments).isEqualTo("""{"q":"hi"}""")
        assertThat(events.last()).isEqualTo(AiApiClient.ChatStreamEvent.Done)
    }

    @Test
    fun `chat stream reports source failure while parsing`() = runTest {
        val client = newClient(
            responseBody = null,
            responseContentType = "text/event-stream",
            customResponseBody = ThrowingResponseBody(IOException("stream broke")),
        )
        val error = AtomicReference<Throwable?>()
        val done = CountDownLatch(1)

        client.chatStream(
            messages = listOf(ChatMessage(role = ChatRole.USER, content = "hello")),
            onEvent = {},
            onError = { throwable ->
                error.set(throwable)
                done.countDown()
            },
            useTool = false,
        )

        assertThat(done.await(3, TimeUnit.SECONDS)).isTrue()
        assertThat(error.get()).isInstanceOf(IOException::class.java)
        assertThat(error.get()?.message).isEqualTo("stream broke")
    }

    @Test
    fun `chat stream falls back to http status for blank error body and invalid retry after`() = runTest {
        val client = newClient(
            responseCode = 500,
            responseMessage = "",
            responseBody = "",
            responseHeaders = mapOf("Retry-After" to "-1"),
        )
        val error = AtomicReference<Throwable?>()
        val done = CountDownLatch(1)

        client.chatStream(
            messages = listOf(ChatMessage(role = ChatRole.USER, content = "hello")),
            onEvent = {},
            onError = { throwable ->
                error.set(throwable)
                done.countDown()
            },
            useTool = false,
        )

        assertThat(done.await(3, TimeUnit.SECONDS)).isTrue()
        val apiException = error.get() as ApiException
        assertThat(apiException.code).isEqualTo(500)
        assertThat(apiException.message).isEqualTo(Strings.ai_error_http_status.str(500))
        assertThat(apiException.retryAfterMillis).isNull()
    }

    @Test
    fun `chat stream reports http api error`() = runTest {
        val client = newClient(
            responseCode = 429,
            responseMessage = "Too Many Requests",
            responseBody = """{"error":{"message":"slow down"}}""",
        )
        val error = AtomicReference<Throwable?>()
        val done = CountDownLatch(1)

        client.chatStream(
            messages = listOf(ChatMessage(role = ChatRole.USER, content = "hello")),
            onEvent = {},
            onError = { throwable ->
                error.set(throwable)
                done.countDown()
            },
            useTool = false,
        )

        assertThat(done.await(3, TimeUnit.SECONDS)).isTrue()
        assertThat(error.get()).isInstanceOf(ApiException::class.java)
        val apiException = error.get() as ApiException
        assertThat(apiException.code).isEqualTo(429)
        assertThat(apiException.message).isEqualTo("slow down")
    }

    @Test
    fun `chat stream parses detail error and retry after seconds`() = runTest {
        val client = newClient(
            responseCode = 429,
            responseMessage = "Too Many Requests",
            responseBody = """{"detail":"retry later"}""",
            responseHeaders = mapOf("Retry-After" to "2"),
        )
        val error = AtomicReference<Throwable?>()
        val done = CountDownLatch(1)

        client.chatStream(
            messages = listOf(ChatMessage(role = ChatRole.USER, content = "hello")),
            onEvent = {},
            onError = { throwable ->
                error.set(throwable)
                done.countDown()
            },
            useTool = false,
        )

        assertThat(done.await(3, TimeUnit.SECONDS)).isTrue()
        val apiException = error.get() as ApiException
        assertThat(apiException.code).isEqualTo(429)
        assertThat(apiException.message).isEqualTo("retry later")
        assertThat(apiException.retryAfterMillis).isEqualTo(2_000L)
    }

    @Test
    fun `chat stream parses error description and retry after http date`() = runTest {
        val retryAfter = ZonedDateTime.now()
            .plusSeconds(30)
            .format(DateTimeFormatter.RFC_1123_DATE_TIME)
        val client = newClient(
            responseCode = 429,
            responseMessage = "",
            responseBody = """{"error_description":"retry after date"}""",
            responseHeaders = mapOf("Retry-After" to retryAfter),
        )
        val error = AtomicReference<Throwable?>()
        val done = CountDownLatch(1)

        client.chatStream(
            messages = listOf(ChatMessage(role = ChatRole.USER, content = "hello")),
            onEvent = {},
            onError = { throwable ->
                error.set(throwable)
                done.countDown()
            },
            useTool = false,
        )

        assertThat(done.await(3, TimeUnit.SECONDS)).isTrue()
        val apiException = error.get() as ApiException
        assertThat(apiException.code).isEqualTo(429)
        assertThat(apiException.message).isEqualTo("retry after date")
        assertThat(apiException.retryAfterMillis).isGreaterThan(0L)
    }

    @Test
    fun `chat stream completes on eof after recognized payload`() = runTest {
        val client = newClient(
            responseContentType = "text/event-stream",
            responseBody = """
                data: {"id":"stream-1","choices":[{"index":0,"delta":{"content":"partial"}}]}

            """.trimIndent(),
        )
        val events = CopyOnWriteArrayList<AiApiClient.ChatStreamEvent>()
        val error = AtomicReference<Throwable?>()
        val done = CountDownLatch(1)

        client.chatStream(
            messages = listOf(ChatMessage(role = ChatRole.USER, content = "hello")),
            onEvent = { event ->
                events += event
                if (event is AiApiClient.ChatStreamEvent.Done) done.countDown()
            },
            onError = { throwable ->
                error.set(throwable)
                done.countDown()
            },
            useTool = false,
        )

        assertThat(done.await(3, TimeUnit.SECONDS)).isTrue()
        assertThat(error.get()).isNull()
        assertThat(events.filterIsInstance<AiApiClient.ChatStreamEvent.TextDelta>().single().text)
            .isEqualTo("partial")
        assertThat(events.last()).isEqualTo(AiApiClient.ChatStreamEvent.Done)
    }

    @Test
    fun `chat stream reports unparseable eof`() = runTest {
        val client = newClient(
            responseContentType = "text/event-stream",
            responseBody = """
                data: not-json

            """.trimIndent(),
        )
        val events = CopyOnWriteArrayList<AiApiClient.ChatStreamEvent>()
        val error = AtomicReference<Throwable?>()
        val done = CountDownLatch(1)

        client.chatStream(
            messages = listOf(ChatMessage(role = ChatRole.USER, content = "hello")),
            onEvent = { events += it },
            onError = { throwable ->
                error.set(throwable)
                done.countDown()
            },
            useTool = false,
        )

        assertThat(done.await(3, TimeUnit.SECONDS)).isTrue()
        assertThat(events).isEmpty()
        assertThat(error.get()).isInstanceOf(ApiException::class.java)
        assertThat(error.get()?.message).isEqualTo(Strings.ai_error_stream_unparseable.str())
    }

    @Test
    fun `chat stream reports provider error payload`() = runTest {
        val client = newClient(
            responseContentType = "text/event-stream",
            responseBody = """
                data: {"error":{"message":"provider refused","code":400}}

            """.trimIndent(),
        )
        val events = CopyOnWriteArrayList<AiApiClient.ChatStreamEvent>()
        val error = AtomicReference<Throwable?>()
        val done = CountDownLatch(1)

        client.chatStream(
            messages = listOf(ChatMessage(role = ChatRole.USER, content = "hello")),
            onEvent = { events += it },
            onError = { throwable ->
                error.set(throwable)
                done.countDown()
            },
            useTool = false,
        )

        assertThat(done.await(3, TimeUnit.SECONDS)).isTrue()
        assertThat(events).isEmpty()
        assertThat(error.get()).isInstanceOf(ApiException::class.java)
        val apiException = error.get() as ApiException
        assertThat(apiException.code).isEqualTo(400)
        assertThat(apiException.message).isEqualTo("provider refused")
    }

    @Test
    fun `chat stream reports network failure`() = runTest {
        val client = newClient(
            responseBody = "{}",
            networkError = IOException("offline"),
        )
        val error = AtomicReference<Throwable?>()
        val done = CountDownLatch(1)

        client.chatStream(
            messages = listOf(ChatMessage(role = ChatRole.USER, content = "hello")),
            onEvent = {},
            onError = { throwable ->
                error.set(throwable)
                done.countDown()
            },
            useTool = false,
        )

        assertThat(done.await(3, TimeUnit.SECONDS)).isTrue()
        assertThat(error.get()).isInstanceOf(IOException::class.java)
        assertThat(error.get()?.message).isEqualTo("offline")
    }

    @Test
    fun `http errors fall back when error body cannot be read`() = runTest {
        val chatClient = newClient(
            responseCode = 500,
            responseMessage = "",
            responseBody = null,
            customResponseBody = ThrowingResponseBody(IOException("broken error body")),
        )
        val listClient = newClient(
            responseCode = 502,
            responseMessage = "",
            responseBody = null,
            customResponseBody = ThrowingResponseBody(IOException("broken model error body")),
        )

        val chatResult = chatClient.chat(
            messages = listOf(ChatMessage(role = ChatRole.USER, content = "hello")),
            useTool = false,
        )
        val listResult = listClient.listModels()

        assertThat(chatResult).isEqualTo(ApiResult.Error(500, Strings.ai_error_http_status.str(500)))
        assertThat(listResult).isEqualTo(ApiResult.Error(502, Strings.ai_error_http_status.str(502)))
    }

    @Test
    fun `list models maps model ids and applies auth header`() = runTest {
        val capturedRequest = AtomicReference<Request>()
        val client = newClient(
            capturedRequest = capturedRequest,
            responseBody = """
                {
                  "object":"list",
                  "data":[
                    {"id":"model-a","object":"model","created":1,"owned_by":"owner"},
                    {"id":"model-b","object":"model","created":2,"owned_by":"owner"}
                  ]
                }
            """.trimIndent()
        )

        val result = client.listModels()

        assertThat(result).isEqualTo(ApiResult.Success(listOf("model-a", "model-b")))
        assertThat(capturedRequest.get().method).isEqualTo("GET")
        assertThat(capturedRequest.get().url.toString()).isEqualTo("https://example.test/v1/models")
        assertThat(capturedRequest.get().header("Authorization")).isEqualTo("Bearer secret")
    }

    @Test
    fun `list models accepts provider compatible minimal model items`() = runTest {
        val client = newClient(
            responseBody = """
                {
                  "object":"list",
                  "data":[
                    {"id":"provider-model-a"},
                    {"id":"provider-model-b"}
                  ]
                }
            """.trimIndent()
        )

        val result = client.listModels()

        assertThat(result).isEqualTo(ApiResult.Success(listOf("provider-model-a", "provider-model-b")))
    }

    @Test
    fun `list models accepts primitive and aliased model items`() = runTest {
        val client = newClient(
            responseBody = """
                {
                  "data":[
                    "model-a",
                    {"model":"model-b"},
                    {"name":"model-c"},
                    {"id":"model-a"},
                    {"id":"   "}
                  ]
                }
            """.trimIndent()
        )

        val result = client.listModels()

        assertThat(result).isEqualTo(ApiResult.Success(listOf("model-a", "model-b", "model-c")))
    }

    @Test
    fun `list models accepts root array and ignores unsupported items`() = runTest {
        val client = newClient(
            responseBody = """
                [
                  " model-a ",
                  {"name":"model-b"},
                  [],
                  {"id":"model-a"}
                ]
            """.trimIndent()
        )

        val result = client.listModels()

        assertThat(result).isEqualTo(ApiResult.Success(listOf("model-a", "model-b")))
    }

    @Test
    fun `list models returns http error message from top level message`() = runTest {
        val client = newClient(
            responseCode = 503,
            responseMessage = "Service Unavailable",
            responseBody = """{"message":"models temporarily unavailable"}"""
        )

        val result = client.listModels()

        assertThat(result).isEqualTo(ApiResult.Error(503, "models temporarily unavailable"))
    }

    @Test
    fun `list models returns raw invalid error body and empty success body error`() = runTest {
        val invalidErrorClient = newClient(
            responseCode = 502,
            responseMessage = "",
            responseBody = "plain upstream failure",
        )
        val emptySuccessClient = newClient(responseBody = "")

        val invalidError = invalidErrorClient.listModels()
        val emptySuccess = emptySuccessClient.listModels()

        assertThat(invalidError).isEqualTo(ApiResult.Error(502, "plain upstream failure"))
        assertThat(emptySuccess).isEqualTo(ApiResult.Error(-1, Strings.ai_error_empty_response.str()))
    }

    @Test
    fun `list models rethrows cancellation exception`() = runTest {
        val client = newClient(
            responseBody = "{}",
            networkError = CancellationException("cancelled"),
        )

        try {
            client.listModels()
            throw AssertionError("CancellationException was not rethrown")
        } catch (e: CancellationException) {
            assertThat(e.message).isEqualTo("cancelled")
        }
    }

    @Test
    fun `list models returns network error for malformed response`() = runTest {
        val client = newClient(responseBody = "not-json")

        val result = client.listModels()

        assertThat(result).isInstanceOf(ApiResult.NetworkError::class.java)
    }

    @Test
    fun `list models returns network error for unsupported json root`() = runTest {
        val primitiveClient = newClient(responseBody = """"model-a"""")
        val missingDataClient = newClient(responseBody = """{"object":"list"}""")

        val primitiveResult = primitiveClient.listModels()
        val missingDataResult = missingDataClient.listModels()

        assertThat(primitiveResult).isInstanceOf(ApiResult.NetworkError::class.java)
        assertThat(missingDataResult).isInstanceOf(ApiResult.NetworkError::class.java)
    }

    private fun newClient(
        capturedRequest: AtomicReference<Request> = AtomicReference(),
        responseCode: Int = 200,
        responseMessage: String = "OK",
        responseBody: String?,
        responseContentType: String = "application/json",
        customResponseBody: ResponseBody? = null,
        responseHeaders: Map<String, String> = emptyMap(),
        networkError: Throwable? = null,
        enableTools: Boolean = false,
    ): AiApiClient {
        val okHttp = OkHttpClient.Builder()
            .addInterceptor(
                Interceptor { chain ->
                    networkError?.let { throw it }
                    val request = chain.request()
                    capturedRequest.set(request)
                    val responseBuilder = Response.Builder()
                        .request(request)
                        .protocol(Protocol.HTTP_1_1)
                        .code(responseCode)
                        .message(responseMessage)
                    responseHeaders.forEach { (name, value) -> responseBuilder.addHeader(name, value) }
                    val body = customResponseBody ?: responseBody?.toResponseBody(responseContentType.toMediaType())
                    if (body != null) responseBuilder.body(body)
                    responseBuilder.build()
                }
            )
            .build()

        return AiApiClient(
            config = AiConfig(
                generation = AiGenerationSettings(
                    model = "gpt-test",
                    maxTokens = 123,
                    temperature = 0.25f,
                ),
                tools = AiToolSettings(enableTools = enableTools),
                thinking = AiThinkingSettings(enableDeepThinking = true, budgetTokens = 2048),
            ),
            endpoint = "https://example.test/v1/",
            auth = AuthStrategy.Bearer("secret"),
            client = okHttp,
        )
    }

    private fun Request.bodyToJsonObject() = Json.parseToJsonElement(
        okio.Buffer().also { buffer -> body!!.writeTo(buffer) }.readUtf8()
    ).jsonObject

    private fun fakeTool(toolName: String): AiTool = object : AiTool {
        override val name: String = toolName
        override val description: String = "Test tool"
        override val category: ToolCategory = ToolCategory.CUSTOM

        override fun getParameters(): JsonElement = buildJsonObject {}

        override suspend fun execute(toolCall: ToolCall, context: ToolExecutionContext): ToolExecutionResult = ToolExecutionResult.Success("ok")
    }

    private val successfulChatResponse: String
        get() = """
            {
              "id":"resp-1",
              "choices":[{"index":0,"message":{"role":"assistant","content":"ok"},"finish_reason":"stop"}],
              "usage":{"prompt_tokens":1,"completion_tokens":2,"total_tokens":3}
            }
        """.trimIndent()

    private fun resetAppStrings() {
        val field = AppStrings::class.java.getDeclaredField("appContext")
        field.isAccessible = true
        field.set(AppStrings, null)
    }

    private class ThrowingResponseBody(private val failure: IOException) : ResponseBody() {
        override fun contentType() = "text/event-stream".toMediaType()

        override fun contentLength(): Long = -1L

        override fun source(): BufferedSource = object : Source {
            override fun read(sink: Buffer, byteCount: Long): Long = throw failure

            override fun timeout(): Timeout = Timeout.NONE

            override fun close() = Unit
        }.buffer()
    }
}
