package com.wuxianggujun.tinaide.core.network.server

import com.wuxianggujun.tinaide.core.network.OkHttpClientProvider
import okhttp3.OkHttpClient

object TinaServerHttpClientFactory {
    fun anonymous(
        baseClient: OkHttpClient = OkHttpClientProvider.default,
        configure: OkHttpClient.Builder.() -> Unit = {}
    ): OkHttpClient = baseClient.newBuilder()
        .apply(configure)
        .build()
}
