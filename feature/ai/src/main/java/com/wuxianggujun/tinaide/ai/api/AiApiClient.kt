package com.wuxianggujun.tinaide.ai.api

import com.wuxianggujun.tinaide.ai.api.stream.ChatStreamParser
import com.wuxianggujun.tinaide.ai.api.stream.OpenAiCompatibleStreamParser
import com.wuxianggujun.tinaide.ai.api.stream.SseReader
import com.wuxianggujun.tinaide.ai.api.stream.ToolCallAccumulator
import com.wuxianggujun.tinaide.ai.tools.ToolRegistry
import com.wuxianggujun.tinaide.core.config.ai.AiConfig
import com.wuxianggujun.tinaide.core.i18n.Strings
import com.wuxianggujun.tinaide.core.i18n.str
import com.wuxianggujun.tinaide.core.network.ApiResult
import com.wuxianggujun.tinaide.core.network.OkHttpClientProvider
import com.wuxianggujun.tinaide.core.serialization.JsonSerializer
import java.io.IOException
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.encodeToJsonElement
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import timber.log.Timber

/**
 * AI API client.
 *
 * 职责:
 * - 按 [AuthStrategy] 构造 HTTP 请求并附加 BYOK Bearer 头;
 * - 执行 streaming / non-streaming 聊天与模型列表请求;
 * - 通过 [AtomicReference] 安全管理可被取消的在途 Call。
 *
 * 历史遗留的 `config.copy(apiKey = "")` hack 已由 [AuthStrategy] 替代;
 * apiKey 的清洗在保存阶段完成 (见 `AiSettingsSectionSupport.sanitizeApiKey`),
 * 这里不再重复处理。
 */
class AiApiClient(
    private val config: AiConfig,
    private val endpoint: String,
    private val auth: AuthStrategy,
    private val client: OkHttpClient = OkHttpClientProvider.longConnection,
) {
    private val json = Json(JsonSerializer.default) { explicitNulls = false }
    private val currentCallRef = AtomicReference<Call?>(null)

    companion object {
        private const val TAG = "AiApiClient"
    }

    sealed class ChatStreamEvent {
        data class TextDelta(val text: String) : ChatStreamEvent()
        data class ReasoningDelta(val text: String) : ChatStreamEvent()
        data class ToolCallsUpdate(val toolCalls: List<ToolCall>) : ChatStreamEvent()
        data class Usage(val usage: ChatUsage) : ChatStreamEvent()
        data object Done : ChatStreamEvent()
    }

    private val streamParser: ChatStreamParser = OpenAiCompatibleStreamParser(json)

    suspend fun chatStream(
        messages: List<ChatMessage>,
        onEvent: (ChatStreamEvent) -> Unit,
        onError: (Throwable) -> Unit,
        useTool: Boolean = true
    ) = withContext(Dispatchers.IO) {
        val request = buildChatRequest(messages, stream = true, useTool = useTool)

        val call = client.newCall(request)
        // 取消前一次在途 Call,避免 getAndSet 后旧 Call 还在后台消费连接。
        currentCallRef.getAndSet(call)?.cancel()
        call.enqueue(object : Callback {
            override fun onResponse(call: Call, response: Response) {
                response.use { streamResponse ->
                    try {
                        if (!streamResponse.isSuccessful) {
                            val errorBody = try {
                                streamResponse.body?.string()
                            } catch (_: Exception) {
                                null
                            }
                            val errorMsg = parseErrorMessage(errorBody)
                                ?: streamResponse.message.takeIf { it.isNotBlank() }
                                ?: Strings.ai_error_http_status.str(streamResponse.code)

                            val apiException = ApiException(
                                code = streamResponse.code,
                                message = errorMsg,
                                retryAfterMillis = parseRetryAfterMillis(streamResponse.header("Retry-After")),
                            )
                            val formattedError = AiApiErrorHandler.handleError(apiException, "chatStream")

                            Timber.tag(TAG).e("Stream request failed: %s", formattedError.technicalMessage)
                            onError(apiException)
                            return
                        }

                        val responseBody = streamResponse.body
                        if (responseBody == null) {
                            onError(ApiException(-1, Strings.ai_error_empty_stream_response.str()))
                            return
                        }

                        val toolCalls = ToolCallAccumulator()
                        var sawRecognizedPayload = false
                        val reader = SseReader(responseBody.source())

                        fun emitDone() {
                            if (toolCalls.hasCalls()) {
                                onEvent(ChatStreamEvent.ToolCallsUpdate(toolCalls.toToolCalls()))
                            }
                            onEvent(ChatStreamEvent.Done)
                        }

                        try {
                            while (true) {
                                val event = reader.readEvent() ?: break
                                if (event is SseReader.Event.Done) {
                                    emitDone()
                                    return
                                }
                                val payload = (event as SseReader.Event.Payload).data
                                val parsed = streamParser.parse(payload)
                                if (parsed == null) {
                                    Timber.tag(TAG).w(
                                        "Ignoring unrecognized stream payload from provider: %s",
                                        payload.take(300)
                                    )
                                    continue
                                }
                                if (!parsed.errorMessage.isNullOrBlank()) {
                                    onError(ApiException(parsed.errorCode ?: -1, parsed.errorMessage))
                                    return
                                }
                                if (parsed.recognized) sawRecognizedPayload = true
                                parsed.usage?.let { onEvent(ChatStreamEvent.Usage(it)) }
                                parsed.toolCallDeltas.forEach { toolCalls.applyDelta(it) }
                                if (parsed.toolCallDeltas.isNotEmpty()) {
                                    onEvent(ChatStreamEvent.ToolCallsUpdate(toolCalls.toToolCalls()))
                                }
                                parsed.textDeltas.forEach { text ->
                                    if (text.isNotBlank()) onEvent(ChatStreamEvent.TextDelta(text))
                                }
                                parsed.reasoningDeltas.forEach { text ->
                                    if (text.isNotBlank()) onEvent(ChatStreamEvent.ReasoningDelta(text))
                                }
                            }

                            if (!sawRecognizedPayload && !toolCalls.hasCalls()) {
                                onError(ApiException(-1, Strings.ai_error_stream_unparseable.str()))
                                return
                            }

                            emitDone()
                        } catch (e: Exception) {
                            val formattedError = AiApiErrorHandler.handleError(e, "chatStream parsing")
                            Timber.tag(TAG).e(e, "Stream parsing failed: %s", formattedError.technicalMessage)
                            onError(e)
                        }
                    } finally {
                        currentCallRef.compareAndSet(call, null)
                    }
                }
            }

            override fun onFailure(call: Call, e: IOException) {
                currentCallRef.compareAndSet(call, null)
                val formattedError = AiApiErrorHandler.handleError(e, "chatStream network")
                Timber.tag(TAG).e(e, "Stream network failed: %s", formattedError.technicalMessage)
                onError(e)
            }
        })
    }

    suspend fun chat(messages: List<ChatMessage>, useTool: Boolean = true): ApiResult<ChatResponse> = withContext(Dispatchers.IO) {
        try {
            val request = buildChatRequest(messages, stream = false, useTool = useTool)
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    val errorBody = try {
                        response.body?.string()
                    } catch (_: Exception) {
                        null
                    }
                    val errorMsg = parseErrorMessage(errorBody)
                        ?: response.message.takeIf { it.isNotBlank() }
                        ?: Strings.ai_error_http_status.str(response.code)

                    val apiException = ApiException(
                        code = response.code,
                        message = errorMsg,
                        retryAfterMillis = parseRetryAfterMillis(response.header("Retry-After")),
                    )
                    val formattedError = AiApiErrorHandler.handleError(apiException, "chat")
                    Timber.tag(TAG).e("Chat request failed: %s", formattedError.technicalMessage)

                    return@withContext ApiResult.Error(response.code, errorMsg)
                }

                val body = response.body?.string()
                if (body.isNullOrBlank()) return@withContext ApiResult.Error(-1, Strings.ai_error_empty_response.str())

                val chatResponse = json.decodeFromString<ChatResponse>(body)
                ApiResult.Success(chatResponse)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val formattedError = AiApiErrorHandler.handleError(e, "chat")
            Timber.tag(TAG).e(e, "Chat failed: %s", formattedError.technicalMessage)
            ApiResult.NetworkError(e.message ?: Strings.ai_error_unknown.str())
        }
    }

    suspend fun listModels(): ApiResult<List<String>> = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url("${endpoint.trimEnd('/')}/models")
                .addHeader("Content-Type", "application/json")
                .apply { auth.apply(this) }
                .get()
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    val errorBody = try {
                        response.body?.string()
                    } catch (_: Exception) {
                        null
                    }
                    val errorMsg = parseErrorMessage(errorBody)
                        ?: response.message.takeIf { it.isNotBlank() }
                        ?: Strings.ai_error_http_status.str(response.code)

                    val apiException = ApiException(
                        code = response.code,
                        message = errorMsg,
                        retryAfterMillis = parseRetryAfterMillis(response.header("Retry-After")),
                    )
                    val formattedError = AiApiErrorHandler.handleError(apiException, "listModels")
                    Timber.tag(TAG).e("List models failed: %s", formattedError.technicalMessage)

                    return@withContext ApiResult.Error(response.code, errorMsg)
                }

                val body = response.body?.string()
                if (body.isNullOrBlank()) return@withContext ApiResult.Error(-1, Strings.ai_error_empty_response.str())

                ApiResult.Success(parseModelIds(body))
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val formattedError = AiApiErrorHandler.handleError(e, "listModels")
            Timber.tag(TAG).e(e, "List models failed: %s", formattedError.technicalMessage)
            ApiResult.NetworkError(e.message ?: Strings.ai_error_unknown.str())
        }
    }

    private fun buildChatRequest(messages: List<ChatMessage>, stream: Boolean, useTool: Boolean = true): Request {
        val tools = if (config.tools.enableTools && useTool) buildDefaultTools() else null
        val toolChoice: String? = if (config.tools.enableTools && useTool) "auto" else null
        val thinking = if (config.thinking.enableDeepThinking) {
            ChatRequestThinking(budgetTokens = config.thinking.budgetTokens)
        } else {
            null
        }

        val requestBody = ChatRequest(
            model = config.generation.model,
            messages = messages.map { msg ->
                when (msg.role) {
                    ChatRole.TOOL -> ChatRequestMessage(
                        role = msg.role.value,
                        content = JsonPrimitive(msg.content),
                        toolCallId = msg.toolCallId
                    )

                    ChatRole.ASSISTANT -> ChatRequestMessage(
                        role = msg.role.value,
                        content = if (msg.content.isNotBlank()) JsonPrimitive(msg.content) else null,
                        toolCalls = msg.toolCalls
                    )

                    else -> {
                        val contentJson = if (msg.contentParts != null) {
                            json.encodeToJsonElement(msg.contentParts)
                        } else {
                            JsonPrimitive(msg.content)
                        }
                        ChatRequestMessage(
                            role = msg.role.value,
                            content = contentJson
                        )
                    }
                }
            },
            maxTokens = config.generation.maxTokens,
            temperature = config.generation.temperature,
            stream = stream,
            streamOptions = if (stream) StreamOptions(includeUsage = true) else null,
            tools = tools,
            toolChoice = toolChoice,
            thinking = thinking
        )

        val requestBodyString = json.encodeToString(requestBody)
            .toRequestBody("application/json".toMediaType())

        return Request.Builder()
            .url("${endpoint.trimEnd('/')}/chat/completions")
            .addHeader("Content-Type", "application/json")
            .apply { auth.apply(this) }
            .post(requestBodyString)
            .build()
    }

    private fun buildDefaultTools(): List<ChatRequestTool> = ToolRegistry.getEnabledRequestTools()

    fun cancelRequest() {
        currentCallRef.getAndSet(null)?.cancel()
    }

    private fun parseErrorMessage(body: String?): String? {
        if (body.isNullOrBlank()) return null
        return try {
            when (val root = json.parseToJsonElement(body)) {
                is JsonObject -> listOf(
                    root["error"],
                    root["message"],
                    root["detail"],
                    root["error_description"],
                    root["errors"],
                ).firstNotNullOfOrNull { it.errorTextOrNull() }
                else -> root.errorTextOrNull()
            }
        } catch (_: Exception) {
            body.take(500).takeIf { it.isNotBlank() }
        }
    }

    private fun parseModelIds(body: String): List<String> {
        val root = json.parseToJsonElement(body)
        val data = when (root) {
            is JsonArray -> root
            is JsonObject -> root["data"] as? JsonArray ?: throw IllegalStateException(Strings.ai_error_parse_failed.str())
            is JsonPrimitive -> throw IllegalStateException(Strings.ai_error_parse_failed.str())
        }

        return data.mapNotNull { element ->
            when (element) {
                is JsonPrimitive -> element.contentOrNull
                is JsonObject -> element.stringField("id")
                    ?: element.stringField("model")
                    ?: element.stringField("name")
                else -> null
            }?.trim()?.takeIf { it.isNotBlank() }
        }.distinct()
    }

    private fun JsonElement?.errorTextOrNull(): String? = when (this) {
        null -> null
        is JsonPrimitive -> contentOrNull?.trim()?.takeIf { it.isNotBlank() }
        is JsonObject -> stringField("message")
            ?: stringField("detail")
            ?: stringField("error_description")
            ?: stringField("msg")
            ?: this["error"].errorTextOrNull()
            ?: this["errors"].errorTextOrNull()
        is JsonArray -> mapNotNull { it.errorTextOrNull() }
            .takeIf { it.isNotEmpty() }
            ?.joinToString("; ")
    }

    private fun JsonObject.stringField(name: String): String? = (this[name] as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotBlank() }

    /**
     * 解析 HTTP `Retry-After` 头,同时支持秒数 (delta-seconds) 与 HTTP-date 两种格式。
     * 失败或值非法时返回 null,由 [com.wuxianggujun.tinaide.ai.retry.RetryPolicy] 兜底。
     */
    private fun parseRetryAfterMillis(header: String?): Long? {
        if (header.isNullOrBlank()) return null
        val trimmed = header.trim()
        trimmed.toLongOrNull()?.let { seconds ->
            return if (seconds >= 0) seconds * 1000L else null
        }
        return runCatching {
            val target = java.time.ZonedDateTime.parse(
                trimmed,
                java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME,
            )
            val delta = target.toInstant().toEpochMilli() - System.currentTimeMillis()
            delta.takeIf { it > 0 }
        }.getOrNull()
    }
}
