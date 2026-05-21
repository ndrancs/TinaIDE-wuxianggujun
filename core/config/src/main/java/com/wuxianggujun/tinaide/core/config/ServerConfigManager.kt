package com.wuxianggujun.tinaide.core.config

import android.content.Context
import android.util.Base64
import com.wuxianggujun.tinaide.core.network.ApiResult
import com.wuxianggujun.tinaide.core.network.server.ClientConfig
import com.wuxianggujun.tinaide.core.network.server.FeatureFlags
import com.wuxianggujun.tinaide.core.network.server.ServerConfigResponse
import com.wuxianggujun.tinaide.core.network.server.TinaServerConfig
import com.wuxianggujun.tinaide.core.serialization.MessagePackCodec
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import timber.log.Timber

/**
 * 匿名服务端配置管理器。
 *
 * 开源版保留功能开关、公告、反馈等服务端能力，但不再读取登录或会员配置。
 */
object ServerConfigManager {
    private const val TAG = "ServerConfigManager"

    private val _config = MutableStateFlow<ServerConfigResponse?>(null)
    val config: StateFlow<ServerConfigResponse?> = _config.asStateFlow()

    private val _isSyncing = MutableStateFlow(false)
    val isSyncing: StateFlow<Boolean> = _isSyncing.asStateFlow()

    private var lastSyncTime: Long = 0
    private var lastConfigVersion: Long = 0

    private const val DEFAULT_SYNC_INTERVAL_MS = 5 * 60 * 1000L
    private const val MIN_SYNC_INTERVAL_MS = 60 * 1000L

    private fun getSyncIntervalMs(): Long {
        val serverInterval = _config.value?.configRefreshIntervalSecs ?: 0
        return if (serverInterval > 0) {
            (serverInterval * 1000L).coerceAtLeast(MIN_SYNC_INTERVAL_MS)
        } else {
            DEFAULT_SYNC_INTERVAL_MS
        }
    }

    suspend fun syncConfig(context: Context, force: Boolean = false): Boolean {
        if (!force && System.currentTimeMillis() - lastSyncTime < getSyncIntervalMs()) {
            Timber.tag(TAG).d("Skip sync: too frequent (interval: ${getSyncIntervalMs()}ms)")
            return true
        }

        if (_isSyncing.value) {
            Timber.tag(TAG).d("Skip sync: already syncing")
            return false
        }

        _isSyncing.value = true
        return try {
            val result = TinaServerConfig.getInstance(context).getApi().getServerConfig()
            when (result) {
                is ApiResult.Success -> {
                    val newConfig = result.data
                    if (lastConfigVersion > 0 && newConfig.version < lastConfigVersion) {
                        Timber.tag(TAG).w(
                            "Reject config rollback: version ${newConfig.version} < last=$lastConfigVersion"
                        )
                        false
                    } else {
                        if (newConfig.version != lastConfigVersion) {
                            Timber.tag(TAG).i(
                                "Config updated: version $lastConfigVersion -> ${newConfig.version}"
                            )
                            lastConfigVersion = newConfig.version
                        }
                        _config.value = newConfig
                        lastSyncTime = System.currentTimeMillis()
                        saveConfigToLocal(context, newConfig)
                        true
                    }
                }
                is ApiResult.Error -> {
                    Timber.tag(TAG).w("Failed to sync config: ${result.message}")
                    if (_config.value == null) loadConfigFromLocal(context)
                    false
                }
                is ApiResult.NetworkError -> {
                    Timber.tag(TAG).w("Network error while syncing config: ${result.message}")
                    if (_config.value == null) loadConfigFromLocal(context)
                    false
                }
            }
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Exception while syncing config")
            if (_config.value == null) loadConfigFromLocal(context)
            false
        } finally {
            _isSyncing.value = false
        }
    }

    fun getCurrentConfig(): ServerConfigResponse = _config.value ?: getDefaultConfig()

    fun getConfigVersion(): Long = _config.value?.version ?: 0

    fun isFeedbackEnabled(): Boolean = getCurrentConfig().features.feedbackEnabled

    fun isPluginMarketEnabled(): Boolean = getCurrentConfig().features.pluginMarketEnabled

    fun isPackageManagerEnabled(): Boolean = getCurrentConfig().features.packageManagerEnabled

    fun isDeveloperOptionsEnabled(): Boolean = getCurrentConfig().features.developerOptionsEnabled

    fun isForceUpdateRequired(): Boolean = getCurrentConfig().client.forceUpdate

    fun getMinClientVersion(): String = getCurrentConfig().client.minClientVersion

    fun getRecommendedClientVersion(): String = getCurrentConfig().client.recommendedClientVersion

    private fun saveConfigToLocal(context: Context, config: ServerConfigResponse) {
        try {
            val prefs = context.getSharedPreferences("server_config", Context.MODE_PRIVATE)
            val bytes = MessagePackCodec.encode(config)
            val b64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
            prefs.edit()
                .putString("config_msgpack_b64", b64)
                .putLong("config_version", config.version)
                .apply()
            Timber.tag(TAG).d("Config saved to local (version: ${config.version})")
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Failed to save config to local")
        }
    }

    private fun loadConfigFromLocal(context: Context) {
        try {
            val prefs = context.getSharedPreferences("server_config", Context.MODE_PRIVATE)
            val b64 = prefs.getString("config_msgpack_b64", null)
            if (b64 != null) {
                val bytes = Base64.decode(b64, Base64.DEFAULT)
                val config = MessagePackCodec.decode<ServerConfigResponse>(bytes)
                _config.value = config
                lastConfigVersion = config.version
                Timber.tag(TAG).i("Config loaded from local cache (version: ${config.version})")
            }
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Failed to load config from local")
            runCatching {
                context.getSharedPreferences("server_config", Context.MODE_PRIVATE)
                    .edit()
                    .remove("config_msgpack_b64")
                    .remove("config_version")
                    .apply()
                Timber.tag(TAG).w("Cleared invalid local server config cache")
            }
        }
    }

    fun loadCachedConfig(context: Context) {
        if (_config.value == null) {
            loadConfigFromLocal(context)
        }
    }

    private fun getDefaultConfig(): ServerConfigResponse {
        return ServerConfigResponse(
            version = 0,
            updatedAt = null,
            configRefreshIntervalSecs = 300,
            features = FeatureFlags(
                feedbackEnabled = true,
                pluginMarketEnabled = true,
                packageManagerEnabled = true,
                developerOptionsEnabled = true
            ),
            client = ClientConfig(
                minClientVersion = "1.0.0",
                recommendedClientVersion = "1.0.0",
                forceUpdate = false
            )
        )
    }
}
