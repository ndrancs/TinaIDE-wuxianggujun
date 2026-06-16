package com.wuxianggujun.tinaide.data.repository

import android.content.Context
import android.os.Build
import com.wuxianggujun.tinaide.core.device.DeviceInfoProvider
import com.wuxianggujun.tinaide.core.network.ApiResult
import com.wuxianggujun.tinaide.core.network.server.TinaServerConfig
import com.wuxianggujun.tinaide.data.model.FeedbackDeviceInfo
import com.wuxianggujun.tinaide.data.model.FeedbackRequest
import com.wuxianggujun.tinaide.data.model.FeedbackResponse

/**
 * 反馈提交结果
 */
sealed class FeedbackResult {
    data class Success(val response: FeedbackResponse) : FeedbackResult()
    data class Error(val message: String, val code: Int? = null) : FeedbackResult()
    data class NetworkError(val message: String) : FeedbackResult()
}

class FeedbackRepository(
    private val context: Context
) {

    suspend fun submitFeedback(
        category: String,
        title: String,
        content: String,
        relatedLogId: String? = null
    ): FeedbackResult {
        val request = FeedbackRequest(
            category = category,
            title = title,
            content = content,
            deviceInfo = collectDeviceInfo(),
            relatedLogId = relatedLogId
        )

        val api = TinaServerConfig.getInstance(context).getApi()

        return when (val result = api.submitFeedback(request)) {
            is ApiResult.Success -> FeedbackResult.Success(result.data)
            is ApiResult.Error -> FeedbackResult.Error(result.message, result.code)
            is ApiResult.NetworkError -> FeedbackResult.NetworkError(result.message)
        }
    }

    private fun collectDeviceInfo(): FeedbackDeviceInfo = FeedbackDeviceInfo(
        model = Build.MODEL,
        brand = Build.BRAND,
        androidVersion = Build.VERSION.RELEASE,
        appVersion = DeviceInfoProvider.versionName,
        abi = Build.SUPPORTED_ABIS.firstOrNull() ?: "unknown",
        sdkInt = Build.VERSION.SDK_INT
    )
}
