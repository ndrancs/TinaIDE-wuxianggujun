package com.wuxianggujun.tinaide.ui.compose.screens.main.market

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import com.wuxianggujun.tinaide.core.i18n.Strings
import com.wuxianggujun.tinaide.core.i18n.str
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * 开源版不再提供账号发布能力。
 *
 * 插件市场浏览、下载历史、收藏等本地能力保留，发布入口统一返回不可用状态。
 */
class MyPublishViewModel(application: Application) : AndroidViewModel(application) {

    private val publishUnavailableMessage: String
        get() = Strings.market_publish_unavailable.str()

    private val _myPluginsState = MutableStateFlow(
        MyPluginsState(error = publishUnavailableMessage)
    )
    val myPluginsState: StateFlow<MyPluginsState> = _myPluginsState.asStateFlow()

    private val _uploadState = MutableStateFlow(UploadState())
    val uploadState: StateFlow<UploadState> = _uploadState.asStateFlow()

    fun refresh() {
        loadMyPlugins()
    }

    fun loadMyPlugins() {
        _myPluginsState.update {
            it.copy(isLoading = false, plugins = emptyList(), error = publishUnavailableMessage)
        }
    }

    fun uploadPlugin(
        fileUri: Uri,
        changelog: String?,
        onComplete: (success: Boolean, message: String?) -> Unit
    ) {
        val message = publishUnavailableMessage
        _uploadState.update {
            it.copy(isUploading = false, progress = 0f, error = message)
        }
        onComplete(false, message)
    }

    fun clearUploadError() {
        _uploadState.update { it.copy(error = null) }
    }
}

data class MyPluginsState(
    val isLoading: Boolean = false,
    val plugins: List<MyPluginSummary> = emptyList(),
    val error: String? = null
)
data class UploadState(
    val isUploading: Boolean = false,
    val progress: Float = 0f,
    val error: String? = null
)
@Serializable
data class MyPluginSummary(
    val id: String,
    @SerialName("plugin_id")
    val pluginId: String,
    val name: String,
    val status: String,
    @SerialName("latest_version")
    val latestVersion: String? = null,
    @SerialName("download_count")
    val downloadCount: Long = 0,
    @SerialName("created_at")
    val createdAt: String,
    @SerialName("updated_at")
    val updatedAt: String
)
