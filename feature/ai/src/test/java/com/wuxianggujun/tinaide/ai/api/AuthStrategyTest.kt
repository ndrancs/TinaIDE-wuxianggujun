package com.wuxianggujun.tinaide.ai.api

import com.google.common.truth.Truth.assertThat
import okhttp3.Request
import org.junit.Test

class AuthStrategyTest {

    @Test
    fun `bearer strategy adds authorization header for non empty key`() {
        val request = Request.Builder()
            .url("https://example.com/v1/chat/completions")
            .apply { AuthStrategy.Bearer("secret-key").apply(this) }
            .build()

        assertThat(request.header("Authorization")).isEqualTo("Bearer secret-key")
    }

    @Test
    fun `bearer strategy skips authorization header for empty key`() {
        val request = Request.Builder()
            .url("https://example.com/v1/chat/completions")
            .apply { AuthStrategy.Bearer("").apply(this) }
            .build()

        assertThat(request.header("Authorization")).isNull()
    }
}
