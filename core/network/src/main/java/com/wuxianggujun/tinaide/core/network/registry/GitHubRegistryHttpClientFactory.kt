package com.wuxianggujun.tinaide.core.network.registry

import android.content.Context
import com.wuxianggujun.tinaide.core.network.OkHttpClientProvider
import java.net.InetSocketAddress
import java.net.Proxy
import okhttp3.OkHttpClient

object GitHubRegistryHttpClientFactory {
    fun probe(context: Context): OkHttpClient = withProxy(
        context = context,
        baseClient = OkHttpClientProvider.probe,
    )

    fun download(context: Context): OkHttpClient = withProxy(
        context = context,
        baseClient = OkHttpClientProvider.download,
    )

    private fun withProxy(
        context: Context,
        baseClient: OkHttpClient,
    ): OkHttpClient {
        val settings = GitHubRegistryProxyConfig.load(context)
        if (!settings.isUsable) return baseClient

        val proxy = Proxy(
            Proxy.Type.HTTP,
            InetSocketAddress(settings.host, settings.port),
        )
        return baseClient.newBuilder()
            .proxy(proxy)
            .build()
    }
}
