package com.wuxianggujun.tinaide.core.network.server

import android.content.Context
import android.os.Build
import com.wuxianggujun.tinaide.core.device.DeviceInfo
import com.wuxianggujun.tinaide.core.device.DeviceInfoProvider
import com.wuxianggujun.tinaide.core.network.OkHttpClientProvider
import java.util.UUID
import okhttp3.OkHttpClient

/**
 * TinaServer 匿名配置管理。
 *
 * 开源版客户端不再维护账号登录态，这里只保存服务端地址、设备标识和匿名 API 客户端。
 */
class TinaServerConfig private constructor(private val context: Context) {

    companion object {
        private const val PREFS_NAME = "tina_server_config"
        private const val KEY_SERVER_URL = "server_url"
        private const val KEY_DEVICE_ID = "device_id"

        private const val DEFAULT_SERVER_URL = "https://tinaide.wuxianggujun.com"

        const val URL_SERVICE_AGREEMENT = "https://tinaide.wuxianggujun.com/legal/terms"
        const val URL_PRIVACY_POLICY = "https://tinaide.wuxianggujun.com/legal/privacy"
        const val URL_HELP = "https://tinaide.wuxianggujun.com/help"

        @Volatile
        private var instance: TinaServerConfig? = null

        fun getInstance(context: Context): TinaServerConfig = instance ?: synchronized(this) {
            instance ?: TinaServerConfig(context.applicationContext).also { instance = it }
        }

        fun getBaseUrl(): String = DEFAULT_SERVER_URL
    }

    private val prefs by lazy {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    private val apiClient: OkHttpClient by lazy {
        TinaServerHttpClientFactory.anonymous(OkHttpClientProvider.default)
    }

    suspend fun getServerUrl(): String = prefs.getString(KEY_SERVER_URL, null)
        ?.takeIf { it.isNotBlank() }
        ?: getDefaultServerUrl()

    suspend fun setServerUrl(url: String?) {
        prefs.edit().apply {
            if (url.isNullOrBlank()) {
                remove(KEY_SERVER_URL)
            } else {
                putString(KEY_SERVER_URL, url.trimEnd('/'))
            }
        }.apply()
        TinaServerApi.resetInstance()
    }

    fun getDefaultServerUrl(): String = DEFAULT_SERVER_URL

    suspend fun getDeviceId(): String {
        prefs.getString(KEY_DEVICE_ID, null)?.takeIf { it.isNotBlank() }?.let { return it }
        val newId = generateDeviceId()
        prefs.edit().putString(KEY_DEVICE_ID, newId).apply()
        return newId
    }

    private fun generateDeviceId(): String {
        val deviceInfo = buildString {
            append(Build.BRAND)
            append(Build.MODEL)
            append(Build.DEVICE)
            append(Build.BOARD)
            append(Build.HARDWARE)
            append(System.currentTimeMillis())
        }
        return UUID.nameUUIDFromBytes(deviceInfo.toByteArray()).toString()
    }

    suspend fun getDeviceInfo(): DeviceInfo = DeviceInfoProvider.getDeviceInfo(context)

    suspend fun getApi(): TinaServerApi = TinaServerApi.getInstance(getServerUrl(), apiClient)

    suspend fun checkServerConnection(): Boolean = try {
        getApi().healthCheck().isSuccess
    } catch (_: Exception) {
        false
    }
}
