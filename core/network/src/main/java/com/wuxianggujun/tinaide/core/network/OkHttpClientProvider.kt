package com.wuxianggujun.tinaide.core.network

import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient

/**
 * 统一的 OkHttpClient 提供者
 *
 * 避免在多个地方重复创建 OkHttpClient 实例，提高性能和资源利用率。
 * OkHttpClient 内部维护连接池和线程池，应该被复用。
 */
object OkHttpClientProvider {

    /**
     * 默认的 HTTP 客户端
     * 适用于大多数 HTTP 请求场景
     */
    val default: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .build()
    }

    /**
     * 快速探测客户端
     * 用于网络探测、健康检查等需要快速响应的场景
     */
    val probe: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .dns(SmartDns)
            .followRedirects(true)
            .followSslRedirects(true)
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .writeTimeout(10, TimeUnit.SECONDS)
            .build()
    }

    /**
     * 长连接客户端
     * 用于 LSP、WebSocket 等需要保持长连接的场景
     */
    val longConnection: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.MILLISECONDS) // 无读取超时
            .writeTimeout(0, TimeUnit.MILLISECONDS) // 无写入超时
            .pingInterval(30, TimeUnit.SECONDS) // 保持连接活跃
            .build()
    }

    /**
     * 下载客户端
     * 用于大文件下载，支持断点续传
     */
    val download: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }

    /**
     * 创建自定义配置的客户端
     *
     * @param baseClient 基础客户端，默认使用 [default]
     * @param configure 配置 lambda
     * @return 配置后的客户端实例
     */
    fun custom(
        baseClient: OkHttpClient = default,
        configure: OkHttpClient.Builder.() -> Unit
    ): OkHttpClient = baseClient.newBuilder()
        .apply(configure)
        .build()
}
