package com.wuxianggujun.tinaide.core.common

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build

data class AppVersionInfo(
    val versionName: String,
    val baseVersionCode: Long,
    val packageVersionCode: Long,
) {
    val displayText: String
        get() = "$versionName ($baseVersionCode)"
}

object AppVersionInfoReader {
    private const val METADATA_BASE_VERSION_CODE = "com.wuxianggujun.tinaide.BASE_VERSION_CODE"
    private const val ABI_VERSION_CODE_MULTIPLIER = 10L
    private val ABI_VERSION_CODE_SUFFIXES = setOf(1L, 2L)

    fun read(context: Context): AppVersionInfo {
        val appContext = context.applicationContext
        val packageInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            appContext.packageManager.getPackageInfo(
                appContext.packageName,
                PackageManager.PackageInfoFlags.of(0),
            )
        } else {
            @Suppress("DEPRECATION")
            appContext.packageManager.getPackageInfo(appContext.packageName, 0)
        }
        val packageVersionCode = packageInfo.longVersionCode
        return AppVersionInfo(
            versionName = packageInfo.versionName ?: "unknown",
            baseVersionCode = readBaseVersionCode(appContext)
                ?: inferBaseVersionCode(packageVersionCode),
            packageVersionCode = packageVersionCode,
        )
    }

    private fun readBaseVersionCode(context: Context): Long? = runCatching {
        val applicationInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.packageManager.getApplicationInfo(
                context.packageName,
                PackageManager.ApplicationInfoFlags.of(PackageManager.GET_META_DATA.toLong()),
            )
        } else {
            @Suppress("DEPRECATION")
            context.packageManager.getApplicationInfo(
                context.packageName,
                PackageManager.GET_META_DATA,
            )
        }
        when (val value = applicationInfo.metaData?.get(METADATA_BASE_VERSION_CODE)) {
            is Int -> value.toLong()
            is Long -> value
            is String -> value.toLongOrNull()
            else -> null
        }?.takeIf { it > 0L }
    }.getOrNull()

    private fun inferBaseVersionCode(packageVersionCode: Long): Long {
        val abiSuffix = packageVersionCode % ABI_VERSION_CODE_MULTIPLIER
        return if (
            packageVersionCode > ABI_VERSION_CODE_MULTIPLIER &&
            abiSuffix in ABI_VERSION_CODE_SUFFIXES
        ) {
            packageVersionCode / ABI_VERSION_CODE_MULTIPLIER
        } else {
            packageVersionCode
        }
    }
}
