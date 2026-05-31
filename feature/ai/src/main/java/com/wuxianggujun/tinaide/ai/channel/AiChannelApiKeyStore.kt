package com.wuxianggujun.tinaide.ai.channel

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * 渠道 API Key 加密存储。
 *
 * 每个渠道的 key 都存入 "ai_channel_api_keys" 下的独立加密条目。
 */
class AiChannelApiKeyStore(context: Context) {

    private val masterKey = MasterKey.Builder(context)
        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
        .build()

    private val prefs: SharedPreferences = EncryptedSharedPreferences.create(
        context,
        PREFS_NAME,
        masterKey,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
    )

    fun getApiKey(channelId: String): String = (prefs.getString(buildKey(channelId), "") ?: "").trim()

    fun putApiKey(channelId: String, apiKey: String) {
        prefs.edit().putString(buildKey(channelId), apiKey.trim()).apply()
    }

    fun removeApiKey(channelId: String) {
        prefs.edit().remove(buildKey(channelId)).apply()
    }

    private fun buildKey(channelId: String): String = "$KEY_PREFIX$channelId"

    companion object {
        private const val PREFS_NAME = "ai_channel_api_keys"
        private const val KEY_PREFIX = "byok_channel_"
    }
}
