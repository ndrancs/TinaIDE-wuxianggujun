package com.wuxianggujun.tinaide.core.packages

import android.content.Context
import com.wuxianggujun.tinaide.core.network.ApiResult
import com.wuxianggujun.tinaide.core.packages.api.PackageApiClient
import com.wuxianggujun.tinaide.core.packages.backend.ApkPackageBackend
import com.wuxianggujun.tinaide.core.packages.backend.DownloadPackageBackend
import com.wuxianggujun.tinaide.core.packages.cache.PackageCacheManager
import com.wuxianggujun.tinaide.core.packages.model.*
import com.wuxianggujun.tinaide.core.packages.store.LocalInstallStateStore
import com.wuxianggujun.tinaide.core.proot.PRootEnvironment
import timber.log.Timber

class PackageManagerImpl(
    private val context: Context,
    private val apiClient: PackageApiClient,
    private val installStateStore: LocalInstallStateStore,
    private val cacheManager: PackageCacheManager = PackageCacheManager(context),
    private val prootEnv: PRootEnvironment? = null
) : PackageManager {

    companion object {
        private const val TAG = "PackageManagerImpl"
    }

    private val linuxPackageBackend: ApkPackageBackend? by lazy {
        prootEnv?.let { ApkPackageBackend(it) }
    }

    private val downloadBackend: DownloadPackageBackend by lazy {
        DownloadPackageBackend(context, apiClient)
    }

    private var cachedVersions: MutableMap<String, Map<Platform, Int>> = mutableMapOf()

    override suspend fun getAvailablePackages(
        page: Int,
        pageSize: Int,
        category: String?,
        platform: Platform?,
        search: String?
    ): Result<List<GUIPackage>> {
        val isDefaultQuery = page == 1 && category == null && platform == null && search == null
        
        if (isDefaultQuery) {
            cacheManager.getPackages()?.let { cached ->
                Timber.tag(TAG).d("Returning ${cached.size} packages from cache")
                return Result.success(cached)
            }
        }

        val result = apiClient.getPackages(
            page = page,
            pageSize = pageSize,
            category = category,
            platform = platform?.name?.lowercase(),
            search = search
        )

        return when (result) {
            is ApiResult.Success -> {
                val rawPackages = result.data.packages
                @Suppress("SENSELESS_COMPARISON")
                val packages = if (rawPackages == null) {
                    Timber.tag(TAG).e("API returned null packages; falling back to cache/empty list")
                    cacheManager.getPackages() ?: emptyList()
                } else {
                    rawPackages
                }

                // 标记内置包
                val packagesWithBundledFlag = packages.map { pkg ->
                    if (installStateStore.isBundledPackage(pkg.id)) {
                        pkg.copy(isBundled = true)
                    } else {
                        pkg
                    }
                }

                if (isDefaultQuery) {
                    cacheManager.savePackages(packagesWithBundledFlag)
                }
                Result.success(packagesWithBundledFlag)
            }
            is ApiResult.Error -> {
                Timber.tag(TAG).e("Failed to get packages: ${result.message}")
                if (isDefaultQuery) {
                    cacheManager.getPackages()?.let { return Result.success(it) }
                }
                Result.failure(Exception(result.message))
            }
            is ApiResult.NetworkError -> {
                Timber.tag(TAG).e("Network error getting packages: ${result.message}")
                if (isDefaultQuery) {
                    cacheManager.getPackages()?.let { return Result.success(it) }
                }
                Result.failure(Exception(result.message))
            }
        }
    }

    override suspend fun getCategories(): Result<List<PackageCategory>> {
        cacheManager.getCategories()?.let { cached ->
            Timber.tag(TAG).d("Returning ${cached.size} categories from cache")
            return Result.success(cached)
        }

        return when (val result = apiClient.getCategories()) {
            is ApiResult.Success -> {
                cacheManager.saveCategories(result.data)
                Result.success(result.data)
            }
            is ApiResult.Error -> {
                cacheManager.getCategories()?.let { return Result.success(it) }
                Result.failure(Exception(result.message))
            }
            is ApiResult.NetworkError -> {
                cacheManager.getCategories()?.let { return Result.success(it) }
                Result.failure(Exception(result.message))
            }
        }
    }

    override suspend fun getPackageDetail(packageId: String): Result<GUIPackage> {
        cacheManager.getPackageDetail(packageId)?.let { cached ->
            Timber.tag(TAG).d("Returning package detail from cache: $packageId")
            // 标记内置包
            val pkg = if (installStateStore.isBundledPackage(cached.id)) {
                cached.copy(isBundled = true)
            } else {
                cached
            }
            return Result.success(pkg)
        }

        return when (val result = apiClient.getPackageDetail(packageId)) {
            is ApiResult.Success -> {
                val pkg = result.data
                // 标记内置包
                val pkgWithBundledFlag = if (installStateStore.isBundledPackage(pkg.id)) {
                    pkg.copy(isBundled = true)
                } else {
                    pkg
                }
                cacheManager.savePackageDetail(pkgWithBundledFlag)
                Result.success(pkgWithBundledFlag)
            }
            is ApiResult.Error -> {
                cacheManager.getPackageDetail(packageId)?.let { return Result.success(it) }
                Result.failure(Exception(result.message))
            }
            is ApiResult.NetworkError -> {
                cacheManager.getPackageDetail(packageId)?.let { return Result.success(it) }
                Result.failure(Exception(result.message))
            }
        }
    }

    override suspend fun getInstallState(packageId: String): PackageInstallState {
        return installStateStore.getInstallState(packageId)
    }

    override suspend fun install(
        packageId: String,
        platform: Platform,
        progress: (InstallProgressEvent) -> Unit
    ): InstallResult {
        progress(InstallProgressEvent.Preparing("Fetching package info..."))

        val packageResult = getPackageDetail(packageId)
        if (packageResult.isFailure) {
            val error = InstallError.UnknownError(packageResult.exceptionOrNull()?.message ?: "Unknown error")
            progress(InstallProgressEvent.Failed(error))
            return InstallResult.Failure(packageId, error)
        }

        val pkg = packageResult.getOrNull()!!
        val platformPkg = when (platform) {
            Platform.LINUX -> pkg.linux
            Platform.ANDROID -> pkg.android
        }

        if (platformPkg == null) {
            val error = InstallError.UnknownError("Package not available for $platform")
            progress(InstallProgressEvent.Failed(error))
            return InstallResult.Failure(packageId, error)
        }

        val result = when (platformPkg.installType) {
            InstallType.APT -> installLinuxPackage(packageId, platformPkg, progress)
            InstallType.DOWNLOAD -> installDownload(packageId, pkg.name, platform, platformPkg, progress)
            InstallType.SCRIPT -> installScript(packageId, platform, platformPkg, progress)
        }

        when (result) {
            is InstallResult.Success -> {
                installStateStore.clearUpdateAvailable(packageId, platform)
                installStateStore.setInstalled(
                    packageId = packageId,
                    platform = platform,
                    version = platformPkg.version,
                    packageName = pkg.name,
                    installType = platformPkg.installType
                )
                PackageDependencyEvents.notifyChanged(
                    PackageDependencyEvents.DependencyChangedEvent(
                        packageId = packageId,
                        platform = platform,
                        action = PackageDependencyEvents.ChangeAction.INSTALLED,
                        version = platformPkg.version
                    )
                )
            }
            is InstallResult.Failure -> Unit
        }

        return result
    }

    private suspend fun installLinuxPackage(
        packageId: String,
        platformPkg: PlatformPackage,
        progress: (InstallProgressEvent) -> Unit
    ): InstallResult {
        val systemPackage = platformPkg.aptPackage
            ?: return InstallResult.Failure(packageId, InstallError.UnknownError("No Linux package specified"))

        val backend = linuxPackageBackend
        if (backend == null) {
            val error = InstallError.UnknownError("PRoot environment not available")
            progress(InstallProgressEvent.Failed(error))
            return InstallResult.Failure(packageId, error)
        }

        return backend.install(
            packageId = packageId,
            systemPackage = systemPackage,
            version = platformPkg.version,
            progress = progress
        )
    }

    private suspend fun installDownload(
        packageId: String,
        packageName: String,
        platform: Platform,
        platformPkg: PlatformPackage,
        progress: (InstallProgressEvent) -> Unit
    ): InstallResult {
        val versionId = getVersionId(packageId, platform)
        if (versionId == null) {
            val error = InstallError.UnknownError("Could not find version ID for $packageId on $platform")
            progress(InstallProgressEvent.Failed(error))
            return InstallResult.Failure(packageId, error)
        }

        return downloadBackend.install(
            packageId = packageId,
            versionId = versionId,
            version = platformPkg.version,
            progress = progress
        )
    }

    private suspend fun getVersionId(packageId: String, platform: Platform): Int? {
        cachedVersions[packageId]?.get(platform)?.let { return it }

        val versionsResult = apiClient.getPackageVersions(packageId)
        if (versionsResult !is ApiResult.Success) {
            return null
        }

        val versions = when (platform) {
            Platform.LINUX -> versionsResult.data.linux
            Platform.ANDROID -> versionsResult.data.android
        } ?: return null

        val latestVersion = versions.find { it.isLatest } ?: versions.firstOrNull() ?: return null

        val versionMap = mutableMapOf<Platform, Int>()
        versionsResult.data.linux?.find { it.isLatest }?.let { versionMap[Platform.LINUX] = it.id }
        versionsResult.data.android?.find { it.isLatest }?.let { versionMap[Platform.ANDROID] = it.id }
        cachedVersions[packageId] = versionMap

        return latestVersion.id
    }

    private suspend fun installScript(
        packageId: String,
        platform: Platform,
        platformPkg: PlatformPackage,
        progress: (InstallProgressEvent) -> Unit
    ): InstallResult {
        progress(InstallProgressEvent.Installing("Running install script..."))

        val proot = prootEnv
        if (proot == null || !proot.isInstalled()) {
            val error = InstallError.UnknownError("PRoot environment not available")
            progress(InstallProgressEvent.Failed(error))
            return InstallResult.Failure(packageId, error)
        }

        val versionsResult = apiClient.getPackageVersions(packageId)
        if (versionsResult !is ApiResult.Success) {
            val error = InstallError.UnknownError("Failed to get package versions")
            progress(InstallProgressEvent.Failed(error))
            return InstallResult.Failure(packageId, error)
        }

        val versions = when (platform) {
            Platform.LINUX -> versionsResult.data.linux
            Platform.ANDROID -> versionsResult.data.android
        }
        val version = versions?.find { it.isLatest } ?: versions?.firstOrNull()

        val installScript = version?.installScript
        if (installScript.isNullOrBlank()) {
            val error = InstallError.UnknownError("No install script available")
            progress(InstallProgressEvent.Failed(error))
            return InstallResult.Failure(packageId, error)
        }

        Timber.tag(TAG).d("Executing install script for $packageId")

        val result = proot.executeShellWithEnv(
            command = installScript,
            env = mapOf("DEBIAN_FRONTEND" to "noninteractive"),
            timeout = 600_000
        )

        return if (result.exitCode == 0) {
            Timber.tag(TAG).d("Script install completed for $packageId")
            progress(InstallProgressEvent.Completed(InstallResult.Success(packageId, platformPkg.version, platform)))
            InstallResult.Success(packageId, platformPkg.version, platform)
        } else {
            val error = InstallError.ScriptError(result.exitCode, result.stderr.takeLast(500))
            Timber.tag(TAG).e("Script install failed: ${error.toDisplayMessage()}")
            progress(InstallProgressEvent.Failed(error))
            InstallResult.Failure(packageId, error)
        }
    }

    override suspend fun uninstall(packageId: String, platform: Platform): UninstallResult {
        val state = installStateStore.getInstallState(packageId)
        val platformState = state.forPlatform(platform)

        if (platformState !is PlatformInstallState.Installed && platformState !is PlatformInstallState.UpdateAvailable) {
            return UninstallResult.Failure(packageId, UninstallError.PackageNotFound(packageId))
        }

        val packageResult = getPackageDetail(packageId)
        val pkg = packageResult.getOrNull()
        val platformPkg = when (platform) {
            Platform.LINUX -> pkg?.linux
            Platform.ANDROID -> pkg?.android
        }

        // 确定安装类型：优先使用 API 返回的，如果 API 不可用则从本地存储回退
        val effectiveInstallType = platformPkg?.installType
            ?: installStateStore.getAllInstalledPackages()
                .find { it.packageId == packageId && it.platform == platform }
                ?.installType

        val result = when (effectiveInstallType) {
            InstallType.APT -> {
                val systemPackage = platformPkg?.aptPackage ?: packageId
                linuxPackageBackend?.uninstall(packageId, systemPackage)
                    ?: UninstallResult.Failure(packageId, UninstallError.UnknownError("PRoot not available"))
            }
            InstallType.DOWNLOAD -> {
                downloadBackend.uninstall(packageId)
            }
            InstallType.SCRIPT -> {
                val versionsResult = apiClient.getPackageVersions(packageId)
                val versions = if (versionsResult is ApiResult.Success) {
                    when (platform) {
                        Platform.LINUX -> versionsResult.data.linux
                        Platform.ANDROID -> versionsResult.data.android
                    }
                } else null

                val uninstallScript = versions?.find { it.isLatest }?.uninstallScript

                if (!uninstallScript.isNullOrBlank() && prootEnv?.isInstalled() == true) {
                    val result = prootEnv.executeShellWithEnv(
                        command = uninstallScript,
                        env = emptyMap(),
                        timeout = 120_000
                    )
                    if (result.exitCode == 0) {
                        // 脚本卸载成功后，也清理可能存在的下载文件
                        cleanupDownloadFiles(packageId)
                        UninstallResult.Success(packageId, platform, 0)
                    } else {
                        UninstallResult.Failure(packageId, UninstallError.UnknownError("Uninstall script failed"))
                    }
                } else {
                    // 没有卸载脚本时，尝试清理下载文件
                    cleanupDownloadFiles(packageId)
                    UninstallResult.Success(packageId, platform, 0)
                }
            }
            null -> {
                // 安装类型未知（API 和本地都没有记录），尝试清理下载文件作为兜底
                Timber.tag(TAG).w("Unknown install type for $packageId, attempting download file cleanup")
                cleanupDownloadFiles(packageId)
                UninstallResult.Success(packageId, platform, 0)
            }
        }

        val uninstalledVersion = when (platformState) {
            is PlatformInstallState.Installed -> platformState.version
            is PlatformInstallState.UpdateAvailable -> platformState.currentVersion
            else -> null
        }
        when (result) {
            is UninstallResult.Success -> {
                installStateStore.setUninstalled(packageId, platform)
                PackageDependencyEvents.notifyChanged(
                    PackageDependencyEvents.DependencyChangedEvent(
                        packageId = packageId,
                        platform = platform,
                        action = PackageDependencyEvents.ChangeAction.UNINSTALLED,
                        version = uninstalledVersion
                    )
                )
            }
            is UninstallResult.Failure -> Unit
        }

        return result
    }

    /**
     * 清理下载类型包的解压文件。
     * 作为兜底机制，确保卸载时文件被实际删除。
     */
    private fun cleanupDownloadFiles(packageId: String) {
        try {
            val installPath = downloadBackend.getInstallPath(packageId)
            if (installPath.exists()) {
                val freedSpace = installPath.walkTopDown().sumOf { it.length() }
                installPath.deleteRecursively()
                Timber.tag(TAG).d("Cleaned up download files for $packageId, freed $freedSpace bytes")
            }
        } catch (e: Exception) {
            Timber.tag(TAG).w(e, "Failed to cleanup download files for $packageId")
        }
    }

    override suspend fun getInstalledPackages(): List<InstalledPackageInfo> {
        return installStateStore.getAllInstalledPackages()
    }

    override suspend fun getDependentPackages(packageId: String, platform: Platform): List<String> {
        return emptyList()
    }

    override suspend fun refreshCache() {
        cacheManager.invalidatePackages()
        cacheManager.invalidateCategories()
        cachedVersions.clear()
    }

    override suspend fun clearCache() {
        cacheManager.clearCache()
        cachedVersions.clear()
        downloadBackend.clearDownloadCache()
    }

    override suspend fun checkForUpdates(): Map<String, UpdateInfo> {
        val updates = mutableMapOf<String, UpdateInfo>()
        val installedPackages = installStateStore.getAllInstalledPackages()
        
        for (installed in installedPackages) {
            try {
                val detailResult = apiClient.getPackageDetail(installed.packageId)
                if (detailResult is ApiResult.Success) {
                    val pkg = detailResult.data
                    
                    if (installed.platform == Platform.LINUX) {
                        pkg.linux?.let { platformPkg ->
                            if (isNewerVersion(platformPkg.version, installed.version)) {
                                updates[installed.packageId] = UpdateInfo(
                                    packageId = installed.packageId,
                                    packageName = pkg.name,
                                    platform = Platform.LINUX,
                                    currentVersion = installed.version,
                                    newVersion = platformPkg.version
                                )
                            }
                        }
                    }
                    
                    if (installed.platform == Platform.ANDROID) {
                        pkg.android?.let { platformPkg ->
                            if (isNewerVersion(platformPkg.version, installed.version)) {
                                updates[installed.packageId] = UpdateInfo(
                                    packageId = installed.packageId,
                                    packageName = pkg.name,
                                    platform = Platform.ANDROID,
                                    currentVersion = installed.version,
                                    newVersion = platformPkg.version
                                )
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                Timber.tag(TAG).w(e, "Failed to check update for ${installed.packageId}")
            }
        }
        
        for ((packageId, updateInfo) in updates) {
            installStateStore.setUpdateAvailable(
                packageId = packageId,
                platform = updateInfo.platform,
                currentVersion = updateInfo.currentVersion,
                newVersion = updateInfo.newVersion
            )
        }
        
        return updates
    }

    private fun isNewerVersion(remote: String, local: String): Boolean {
        val remoteParts = remote.split(".").mapNotNull { it.toIntOrNull() }
        val localParts = local.split(".").mapNotNull { it.toIntOrNull() }
        
        for (i in 0 until maxOf(remoteParts.size, localParts.size)) {
            val r = remoteParts.getOrElse(i) { 0 }
            val l = localParts.getOrElse(i) { 0 }
            if (r > l) return true
            if (r < l) return false
        }
        return false
    }
}
