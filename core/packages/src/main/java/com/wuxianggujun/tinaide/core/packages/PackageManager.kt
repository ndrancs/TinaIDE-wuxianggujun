package com.wuxianggujun.tinaide.core.packages

import com.wuxianggujun.tinaide.core.packages.model.*

interface PackageManager {
    suspend fun getAvailablePackages(
        page: Int = 1,
        pageSize: Int = 50,
        category: String? = null,
        platform: Platform? = null,
        search: String? = null
    ): Result<List<GUIPackage>>

    suspend fun getCategories(): Result<List<PackageCategory>>

    suspend fun getPackageDetail(packageId: String): Result<GUIPackage>

    suspend fun getInstallState(packageId: String): PackageInstallState

    suspend fun previewInstallPlan(packageId: String, platform: Platform): Result<PackageInstallPlan>

    suspend fun install(
        packageId: String,
        platform: Platform,
        progress: (InstallProgressEvent) -> Unit = {}
    ): InstallResult

    suspend fun uninstall(
        packageId: String,
        platform: Platform
    ): UninstallResult

    suspend fun getInstalledPackages(): List<InstalledPackageInfo>

    suspend fun getDependentPackages(packageId: String, platform: Platform): List<String>

    suspend fun refreshCache()

    suspend fun clearCache()

    suspend fun checkForUpdates(): Map<String, UpdateInfo>
}

data class InstalledPackageInfo(
    val packageId: String,
    val packageName: String,
    val platform: Platform,
    val version: String,
    val installedAt: Long,
    val installType: InstallType,
    val size: Long? = null
)

data class UpdateInfo(
    val packageId: String,
    val packageName: String,
    val platform: Platform,
    val currentVersion: String,
    val newVersion: String
)

data class PackageInstallPlan(
    val packageId: String,
    val packageName: String,
    val platform: Platform,
    val packages: List<PackageInstallPlanItem>
)

data class PackageInstallPlanItem(
    val packageId: String,
    val packageName: String,
    val version: String,
    val isRoot: Boolean,
    val isAlreadyInstalled: Boolean
)
