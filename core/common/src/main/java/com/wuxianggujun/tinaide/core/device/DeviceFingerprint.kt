package com.wuxianggujun.tinaide.core.device

import android.content.Context
import android.os.Build
import android.provider.Settings

/**
 * 设备指纹（用于服务端设备绑定/日志上传等）
 *
 * 注意：此指纹不应包含可逆的敏感信息；这里沿用历史实现：基于 ANDROID_ID + 设备信息生成 UUID。
 */
object DeviceFingerprint {

    fun get(context: Context): String {
        val androidId = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ANDROID_ID
        ) ?: ""

        val deviceInfo = buildString {
            append(androidId)
            append(Build.BRAND)
            append(Build.MODEL)
            append(Build.DEVICE)
        }

        return java.util.UUID.nameUUIDFromBytes(deviceInfo.toByteArray()).toString()
    }
}
