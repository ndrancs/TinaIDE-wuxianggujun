package com.wuxianggujun.tinaide.ai.api

import okhttp3.Request

/**
 * 请求鉴权策略。
 *
 * 开源客户端只保留 BYOK 鉴权路径，请求由用户配置的上游 API Key 附加 Bearer 头。
 */
sealed interface AuthStrategy {

    /** 在请求构造完成前调用;实现可以增加 Header、签名等。 */
    fun apply(builder: Request.Builder)

    /**
     * 用户自带 API Key 的 BYOK 模式。
     *
     * 注意:[apiKey] 在构造时就应该是干净的(已经 trim + 去换行),
     * 请求路径不再重复做清洗——DRY 原则要求"清洗在保存时发生"。
     */
    data class Bearer(val apiKey: String) : AuthStrategy {
        override fun apply(builder: Request.Builder) {
            if (apiKey.isNotEmpty()) {
                builder.addHeader("Authorization", "Bearer $apiKey")
            }
        }
    }
}
