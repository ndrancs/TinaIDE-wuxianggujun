package com.wuxianggujun.tinaide.core.network.server

/**
 * TinaServer 匿名客户端运行时配置。
 *
 * 这里只保留服务器配置签名校验密钥，不再承载登录态或账号 Token。
 */
object TinaServerEnvironment {
    @Volatile
    var serverConfigHmacSecret: String = ""
        private set

    fun initialize(serverConfigHmacSecret: String) {
        this.serverConfigHmacSecret = serverConfigHmacSecret.trim()
    }
}
