package com.wuxianggujun.tinaide.plugin.marketplace

import android.content.Context
import com.wuxianggujun.tinaide.core.network.ApiResult
import com.wuxianggujun.tinaide.plugin.InstalledPlugin
import com.wuxianggujun.tinaide.plugin.PluginHostLogSources
import com.wuxianggujun.tinaide.plugin.PluginLogManager
import com.wuxianggujun.tinaide.plugin.PluginManager
import com.wuxianggujun.tinaide.plugin.PluginStateSnapshot
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import timber.log.Timber

class PluginMarketplaceRepository(
    private val context: Context,
    private val api: PluginMarketplaceApi = PluginMarketplaceApi.create(context.applicationContext),
    private val pluginManager: PluginManager = PluginManager.getInstance(context)
) {
    companion object {
        private const val TAG = "PluginMarketRepo"
    }

    private val pluginLogManager = PluginLogManager.getInstance(context.applicationContext)
    private val downloadDir: File by lazy {
        File(context.cacheDir, "plugin_downloads").also { it.mkdirs() }
    }

    init {
        Timber.tag(TAG).i(
            "PluginMarketplaceRepository using PluginManager instance=%s",
            pluginManager.instanceId
        )
        pluginLogManager.info(
            PluginHostLogSources.Marketplace,
            "Marketplace repository using PluginManager instance=${pluginManager.instanceId}"
        )
    }

    val pluginStateFlow: StateFlow<PluginStateSnapshot>
        get() = pluginManager.pluginStateFlow

    suspend fun listPlugins(
        page: Int = 1,
        limit: Int = 20,
        category: String? = null,
        search: String? = null,
        sort: String? = null
    ): ApiResult<PluginListData> = api.listPlugins(page, limit, category, search, sort)

    suspend fun getPluginDetail(pluginId: String): ApiResult<PluginDetail> = api.getPluginDetail(pluginId)

    suspend fun checkUpdates(): ApiResult<CheckUpdateData> {
        val installed = pluginManager.listInstalledPlugins()
        if (installed.isEmpty()) {
            return ApiResult.Success(CheckUpdateData(emptyList()))
        }
        val items = installed.map { plugin ->
            CheckUpdateItem(plugin.manifest.id, plugin.manifest.version)
        }
        return api.checkUpdates(items)
    }

    suspend fun downloadAndInstallPlugin(
        pluginId: String,
        version: String? = null,
        onProgress: ((downloaded: Long, total: Long) -> Unit)? = null
    ): Result<InstalledPlugin> = withContext(Dispatchers.IO) {
        try {
            val installedPlugin = pluginManager.getInstalledPlugin(pluginId)
            if (installedPlugin != null && version != null && installedPlugin.manifest.version == version) {
                Timber.tag(TAG).d(
                    "Skip download for installed plugin: pluginId=%s, version=%s",
                    pluginId,
                    version
                )
                pluginLogManager.debug(
                    PluginHostLogSources.Marketplace,
                    "Skip download because plugin is already installed pluginId=$pluginId version=$version manager=${pluginManager.instanceId}"
                )
                return@withContext Result.success(installedPlugin)
            }

            val targetFile = File(downloadDir, "$pluginId.tinaplug")
            Timber.tag(TAG).i(
                "Download and install plugin started pluginId=%s version=%s manager=%s",
                pluginId,
                version ?: "",
                pluginManager.instanceId
            )
            pluginLogManager.info(
                PluginHostLogSources.Marketplace,
                "Download and install started pluginId=$pluginId version=${version.orEmpty()} manager=${pluginManager.instanceId}"
            )

            val downloadResult = api.downloadPlugin(
                pluginId = pluginId,
                version = version,
                targetFile = targetFile,
                onProgress = onProgress
            )

            when (downloadResult) {
                is ApiResult.Success -> {
                    val installResult = pluginManager.install(downloadResult.data)
                    if (installResult.isSuccess) {
                        val installed = installResult.getOrNull()
                        Timber.tag(TAG).i(
                            "Download and install plugin succeeded pluginId=%s version=%s installedVersion=%s manager=%s",
                            pluginId,
                            version ?: "",
                            installed?.manifest?.version ?: "",
                            pluginManager.instanceId
                        )
                        pluginLogManager.info(
                            PluginHostLogSources.Marketplace,
                            "Download and install succeeded pluginId=$pluginId requestedVersion=${version.orEmpty()} installedVersion=${installed?.manifest?.version.orEmpty()} manager=${pluginManager.instanceId}"
                        )
                    }
                    if (installResult.isFailure) {
                        Timber.tag(TAG)
                            .e(
                                installResult.exceptionOrNull(),
                                "Install plugin failed: pluginId=%s, version=%s",
                                pluginId,
                                version ?: ""
                            )
                        pluginLogManager.error(
                            source = PluginHostLogSources.Marketplace,
                            message = "Install failed after download pluginId=$pluginId version=${version.orEmpty()} manager=${pluginManager.instanceId} reason=${installResult.exceptionOrNull()?.message.orEmpty()}",
                            stackTrace = installResult.exceptionOrNull()?.stackTraceToString()
                        )
                    }
                    targetFile.delete()
                    installResult
                }
                is ApiResult.Error -> {
                    Timber.tag(TAG).w(
                        "Download plugin failed: pluginId=%s, version=%s, code=%d, message=%s",
                        pluginId,
                        version ?: "",
                        downloadResult.code,
                        downloadResult.message
                    )
                    pluginLogManager.warn(
                        PluginHostLogSources.Marketplace,
                        "Download failed pluginId=$pluginId version=${version.orEmpty()} code=${downloadResult.code} message=${downloadResult.message}"
                    )
                    Result.failure(Exception(downloadResult.message))
                }
                is ApiResult.NetworkError -> {
                    Timber.tag(TAG).w(
                        "Download plugin failed (network): pluginId=%s, version=%s, message=%s",
                        pluginId,
                        version ?: "",
                        downloadResult.message
                    )
                    pluginLogManager.warn(
                        PluginHostLogSources.Marketplace,
                        "Download failed by network pluginId=$pluginId version=${version.orEmpty()} message=${downloadResult.message}"
                    )
                    Result.failure(Exception(downloadResult.message))
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Download and install failed")
            pluginLogManager.error(
                source = PluginHostLogSources.Marketplace,
                message = "Download and install crashed pluginId=$pluginId version=${version.orEmpty()} manager=${pluginManager.instanceId} reason=${e.message.orEmpty()}",
                stackTrace = e.stackTraceToString()
            )
            Result.failure(e)
        }
    }

    fun getInstalledVersion(pluginId: String): String? = pluginManager.getInstalledVersion(pluginId)

    fun resolveInstallState(plugins: List<PluginSummary>): PluginMarketplaceInstallState = PluginMarketplaceInstallStateResolver.resolve(
        plugins = plugins,
        installedVersions = pluginManager.pluginStateFlow.value.installedVersions,
    )
}
