package com.wuxianggujun.tinaide.core.network

import com.wuxianggujun.tinaide.core.i18n.Strings
import com.wuxianggujun.tinaide.core.i18n.str
import com.wuxianggujun.tinaide.core.network.ApiResult
import com.wuxianggujun.tinaide.core.serialization.JsonSerializer
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import timber.log.Timber

sealed class ApiEnvelopeParseResult<out T> {
    data class Success<T>(val data: T) : ApiEnvelopeParseResult<T>()
    data class ApiError(
        val apiCode: String,
        val message: String?,
        val details: String?
    ) : ApiEnvelopeParseResult<Nothing>()

    data class InvalidFormat(val reason: String) : ApiEnvelopeParseResult<Nothing>()
}

object ApiEnvelopeParser {

    const val CODE_OK = "OK"

    val json = JsonSerializer.default

    /**
     * 解析 API 信封并直接转换为 ApiResult
     *
     * 标准处理流程：
     * - Success → ApiResult.Success
     * - ApiError → ApiResult.Error（格式化错误码+消息）
     * - InvalidFormat → 记录日志 + ApiResult.Error
     *
     * @param body JSON 响应体
     * @param httpCode HTTP 状态码
     * @param tag 日志标签（可选，传入后会记录 InvalidFormat 日志）
     */
    inline fun <reified T> parseToApiResult(
        body: String,
        httpCode: Int,
        tag: String? = null
    ): ApiResult<T> = when (val parsed = parse<T>(body)) {
        is ApiEnvelopeParseResult.Success -> ApiResult.Success(parsed.data)
        is ApiEnvelopeParseResult.ApiError -> ApiResult.Error(
            httpCode,
            formatApiError(parsed.apiCode, parsed.message)
        )
        is ApiEnvelopeParseResult.InvalidFormat -> {
            if (tag != null) {
                Timber.tag(tag).e(
                    "Invalid API envelope (http=%d): %s",
                    httpCode,
                    parsed.reason
                )
            }
            ApiResult.Error(
                if (httpCode in 200..299) -1 else httpCode,
                Strings.error_response_parse_failed.str()
            )
        }
    }

    /**
     * 解析 API 信封格式的响应
     * @param body JSON 响应体
     */
    inline fun <reified T> parse(body: String): ApiEnvelopeParseResult<T> {
        val root = body.toJsonObjectOrNull() ?: return ApiEnvelopeParseResult.InvalidFormat("Invalid JSON")

        val code = root.getStringOrNull("code") ?: return ApiEnvelopeParseResult.InvalidFormat("Missing 'code' field")

        if (code != CODE_OK) {
            return ApiEnvelopeParseResult.ApiError(
                apiCode = code,
                message = root.getStringOrNull("message"),
                details = root.getStringOrNull("details")
            )
        }

        val dataElement = root["data"] ?: return ApiEnvelopeParseResult.InvalidFormat("Missing 'data' field")

        return runCatching {
            val data: T = json.decodeFromJsonElement<T>(dataElement)
            ApiEnvelopeParseResult.Success(data)
        }.getOrElse { e ->
            ApiEnvelopeParseResult.InvalidFormat("Failed to parse data: ${e.message}")
        }
    }

    /**
     * 解析错误响应
     * @param body JSON 响应体
     */
    fun parseError(body: String): ApiEnvelopeParseResult.ApiError? {
        val root = body.toJsonObjectOrNull() ?: return null
        val code = root.getStringOrNull("code") ?: return null
        if (code == CODE_OK) return null
        return ApiEnvelopeParseResult.ApiError(
            apiCode = code,
            message = root.getStringOrNull("message"),
            details = root.getStringOrNull("details")
        )
    }

    /**
     * 仅检查信封 code 是否为 OK（不解析 data 字段）
     *
     * 适用于返回类型为 Unit 的 API 请求（如添加/删除收藏）
     */
    fun checkOk(body: String, httpCode: Int): ApiResult<Unit> {
        val root = body.toJsonObjectOrNull()
            ?: return ApiResult.Error(
                if (httpCode in 200..299) -1 else httpCode,
                Strings.error_response_parse_failed.str()
            )

        val code = root.getStringOrNull("code")
            ?: return ApiResult.Error(
                if (httpCode in 200..299) -1 else httpCode,
                Strings.error_response_parse_failed.str()
            )

        return if (code == CODE_OK) {
            ApiResult.Success(Unit)
        } else {
            ApiResult.Error(
                httpCode,
                formatApiError(code, root.getStringOrNull("message"))
            )
        }
    }

    /**
     * 格式化 API 错误消息
     *
     * 将 apiCode 和 message 组合为用户可读的错误信息
     */
    fun formatApiError(apiCode: String, message: String?): String {
        val msg = message?.trim()?.takeIf { it.isNotEmpty() }
        return if (msg == null) apiCode else "$apiCode: $msg"
    }

    fun String.toJsonObjectOrNull(): JsonObject? = runCatching {
        json.parseToJsonElement(this).jsonObject
    }.getOrNull()

    fun JsonObject.getStringOrNull(name: String): String? {
        val element: JsonElement = get(name) ?: return null
        return runCatching {
            element.jsonPrimitive.content
        }.getOrNull()
    }
}
