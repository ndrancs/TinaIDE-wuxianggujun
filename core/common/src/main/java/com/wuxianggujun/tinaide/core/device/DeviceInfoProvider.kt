package com.wuxianggujun.tinaide.core.device

import android.content.Context
import android.os.Build
import timber.log.Timber

/**
 * 设备信息提供者
 * 统一管理设备信息的获取，确保上传接口和日志文件中的设备信息一致
 */
object DeviceInfoProvider {

    private const val TAG = "DeviceInfoProvider"

    @Volatile
    private var _versionName: String = "unknown"

    val versionName: String get() = _versionName

    fun initialize(versionName: String) {
        _versionName = versionName
    }

    /**
     * 获取设备信息（JSON 格式，用于上传接口）
     */
    suspend fun getDeviceInfo(context: Context): DeviceInfo = DeviceInfo(
        deviceId = DeviceFingerprint.get(context),
        model = getDeviceModel(),
        osVersion = getOsVersion(),
        appVersion = _versionName
    )

    /**
     * 获取设备型号（格式：品牌 型号）
     * 确保不为空
     */
    fun getDeviceModel(): String {
        val brand = Build.BRAND.takeIf { it.isNotBlank() } ?: "Unknown"
        val model = Build.MODEL.takeIf { it.isNotBlank() } ?: "Unknown"
        return "$brand $model"
    }

    /**
     * 获取系统版本（格式：Android 版本 (API 级别)）
     */
    fun getOsVersion(): String {
        val release = Build.VERSION.RELEASE.takeIf { it.isNotBlank() } ?: "Unknown"
        val sdk = Build.VERSION.SDK_INT
        return "Android $release (API $sdk)"
    }

    /**
     * 获取设备信息文本（用于导出到日志文件）
     */
    fun getDeviceInfoText(): String = buildString {
        appendLine("=== Device Info ===")
        appendLine("Brand: ${Build.BRAND.takeIf { it.isNotBlank() } ?: "Unknown"}")
        appendLine("Model: ${Build.MODEL.takeIf { it.isNotBlank() } ?: "Unknown"}")
        appendLine("Manufacturer: ${Build.MANUFACTURER.takeIf { it.isNotBlank() } ?: "Unknown"}")
        appendLine("Android release: ${Build.VERSION.RELEASE.takeIf { it.isNotBlank() } ?: "Unknown"}")
        appendLine("SDK: ${Build.VERSION.SDK_INT}")
        appendLine("CPU ABIs: ${Build.SUPPORTED_ABIS.joinToString()}")
        appendLine("Device: ${Build.DEVICE.takeIf { it.isNotBlank() } ?: "Unknown"}")
        appendLine("Board: ${Build.BOARD.takeIf { it.isNotBlank() } ?: "Unknown"}")
        appendLine("App version: $_versionName")
        appendLine()
    }

    /**
     * 记录设备信息到日志（用于调试）
     */
    fun logDeviceInfo() {
        Timber.tag(TAG).d("Device Model: %s", getDeviceModel())
        Timber.tag(TAG).d("OS Version: %s", getOsVersion())
        Timber.tag(TAG).d("App Version: %s", _versionName)
        Timber.tag(TAG).d("CPU ABIs: %s", Build.SUPPORTED_ABIS.joinToString())
    }
}
