package com.wuxianggujun.tinaide.core.network

import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Test

class ApiEnvelopeParserTest {

    @Test
    fun parse_shouldDecodeSuccessEnvelopeData() {
        val result = ApiEnvelopeParser.parse<JsonObject>(
            """{"code":"OK","data":{"id":"item-1","count":3}}"""
        )

        assertThat(result).isEqualTo(
            ApiEnvelopeParseResult.Success(
                JsonObject(
                    mapOf(
                        "id" to JsonPrimitive("item-1"),
                        "count" to JsonPrimitive(3)
                    )
                )
            )
        )
    }

    @Test
    fun parse_shouldReturnApiErrorWithoutParsingDataWhenCodeIsNotOk() {
        val result = ApiEnvelopeParser.parse<JsonObject>(
            """{"code":"INVALID_TOKEN","message":"expired","details":"refresh required"}"""
        )

        assertThat(result).isEqualTo(
            ApiEnvelopeParseResult.ApiError(
                apiCode = "INVALID_TOKEN",
                message = "expired",
                details = "refresh required"
            )
        )
    }

    @Test
    fun parse_shouldRejectMissingCodeOrData() {
        assertThat(ApiEnvelopeParser.parse<JsonObject>("{}"))
            .isEqualTo(ApiEnvelopeParseResult.InvalidFormat("Missing 'code' field"))
        assertThat(ApiEnvelopeParser.parse<JsonObject>("""{"code":"OK"}"""))
            .isEqualTo(ApiEnvelopeParseResult.InvalidFormat("Missing 'data' field"))
    }

    @Test
    fun checkOk_shouldMapEnvelopeCodeToApiResult() {
        assertThat(ApiEnvelopeParser.checkOk("""{"code":"OK"}""", httpCode = 204).isSuccess)
            .isTrue()

        val error = ApiEnvelopeParser.checkOk(
            """{"code":"DENIED","message":"no access"}""",
            httpCode = 403
        )

        assertThat(error).isEqualTo(ApiResult.Error(403, "DENIED: no access"))
    }

    @Test
    fun formatApiError_shouldTrimBlankMessages() {
        assertThat(ApiEnvelopeParser.formatApiError("E1", null)).isEqualTo("E1")
        assertThat(ApiEnvelopeParser.formatApiError("E1", "   ")).isEqualTo("E1")
        assertThat(ApiEnvelopeParser.formatApiError("E1", "failed")).isEqualTo("E1: failed")
    }
}
