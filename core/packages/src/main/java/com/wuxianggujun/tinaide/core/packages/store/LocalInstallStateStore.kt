package com.wuxianggujun.tinaide.core.packages.store

import android.content.Context
import android.content.SharedPreferences
import com.wuxianggujun.tinaide.core.packages.InstalledPackageInfo
import com.wuxianggujun.tinaide.core.packages.model.*
import com.wuxianggujun.tinaide.core.serialization.JsonSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString

class LocalInstallStateStore(context: Context) {

    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val json = JsonSerializer.default

    companion object {
        private const val PREFS_NAME = "package_install_state"
        private const val KEY_INSTALLED_PACKAGES = "installed_packages"
        private const val KEY_UPDATE_AVAILABLE = "update_available"
    }

    @Serializable
    private data class StoredInstallInfo(
        val packageId: String,
        val packageName: String,
        val platform: String,
        val version: String,
        val installedAt: Long,
        val installType: String,
        val size: Long? = null,
        val isBundled: Boolean = false
    )

    @Serializable
    private data class StoredUpdateInfo(
        val packageId: String,
        val platform: String,
        val currentVersion: String,
        val newVersion: String
    )

    fun getInstallState(packageId: String): PackageInstallState {
        val installedPackages = getStoredPackages()
        val updateInfos = getStoredUpdates()

        val linuxInfo = installedPackages.find { it.packageId == packageId && it.platform == "linux" }
        val androidInfo = installedPackages.find { it.packageId == packageId && it.platform == "android" }
        val linuxUpdate = updateInfos.find { it.packageId == packageId && it.platform == "linux" }
        val androidUpdate = updateInfos.find { it.packageId == packageId && it.platform == "android" }

        return PackageInstallState(
            linux = when {
                linuxUpdate != null -> PlatformInstallState.UpdateAvailable(linuxUpdate.currentVersion, linuxUpdate.newVersion)
                linuxInfo != null -> PlatformInstallState.Installed(linuxInfo.version, linuxInfo.installedAt)
                else -> PlatformInstallState.NotInstalled
            },
            android = when {
                androidUpdate != null -> PlatformInstallState.UpdateAvailable(androidUpdate.currentVersion, androidUpdate.newVersion)
                androidInfo != null -> PlatformInstallState.Installed(androidInfo.version, androidInfo.installedAt)
                else -> PlatformInstallState.NotInstalled
            }
        )
    }

    fun setInstalled(
        packageId: String,
        platform: Platform,
        version: String,
        packageName: String = packageId,
        installType: InstallType = InstallType.APT,
        size: Long? = null,
        isBundled: Boolean = false
    ) {
        val installedPackages = getStoredPackages().toMutableList()

        installedPackages.removeAll { it.packageId == packageId && it.platform == platform.name.lowercase() }

        installedPackages.add(
            StoredInstallInfo(
                packageId = packageId,
                packageName = packageName,
                platform = platform.name.lowercase(),
                version = version,
                installedAt = System.currentTimeMillis(),
                installType = installType.name.lowercase(),
                size = size,
                isBundled = isBundled
            )
        )

        saveStoredPackages(installedPackages)
    }

    fun setUninstalled(packageId: String, platform: Platform) {
        val installedPackages = getStoredPackages().toMutableList()
        installedPackages.removeAll { it.packageId == packageId && it.platform == platform.name.lowercase() }
        saveStoredPackages(installedPackages)
    }

    fun getAllInstalledPackages(): List<InstalledPackageInfo> = getStoredPackages().map { stored ->
        InstalledPackageInfo(
            packageId = stored.packageId,
            packageName = stored.packageName,
            platform = Platform.valueOf(stored.platform.uppercase()),
            version = stored.version,
            installedAt = stored.installedAt,
            installType = InstallType.valueOf(stored.installType.uppercase()),
            size = stored.size
        )
    }

    fun isInstalled(packageId: String, platform: Platform): Boolean = getStoredPackages().any {
        it.packageId == packageId && it.platform == platform.name.lowercase()
    }

    fun getInstalledVersion(packageId: String, platform: Platform): String? = getStoredPackages().find {
        it.packageId == packageId && it.platform == platform.name.lowercase()
    }?.version

    fun isBundledPackage(packageId: String): Boolean = getStoredPackages().any {
        it.packageId == packageId && it.isBundled
    }

    fun setUpdateAvailable(
        packageId: String,
        platform: Platform,
        currentVersion: String,
        newVersion: String
    ) {
        val updates = getStoredUpdates().toMutableList()
        updates.removeAll { it.packageId == packageId && it.platform == platform.name.lowercase() }
        updates.add(
            StoredUpdateInfo(
                packageId = packageId,
                platform = platform.name.lowercase(),
                currentVersion = currentVersion,
                newVersion = newVersion
            )
        )
        saveStoredUpdates(updates)
    }

    fun clearUpdateAvailable(packageId: String, platform: Platform) {
        val updates = getStoredUpdates().toMutableList()
        updates.removeAll { it.packageId == packageId && it.platform == platform.name.lowercase() }
        saveStoredUpdates(updates)
    }

    fun clear() {
        prefs.edit().clear().apply()
    }

    private fun getStoredPackages(): List<StoredInstallInfo> {
        val jsonString = prefs.getString(KEY_INSTALLED_PACKAGES, null) ?: return emptyList()
        return try {
            json.decodeFromString<List<StoredInstallInfo>>(jsonString)
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun saveStoredPackages(packages: List<StoredInstallInfo>) {
        val jsonString = json.encodeToString(packages)
        prefs.edit().putString(KEY_INSTALLED_PACKAGES, jsonString).apply()
    }

    private fun getStoredUpdates(): List<StoredUpdateInfo> {
        val jsonString = prefs.getString(KEY_UPDATE_AVAILABLE, null) ?: return emptyList()
        return try {
            json.decodeFromString<List<StoredUpdateInfo>>(jsonString)
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun saveStoredUpdates(updates: List<StoredUpdateInfo>) {
        val jsonString = json.encodeToString(updates)
        prefs.edit().putString(KEY_UPDATE_AVAILABLE, jsonString).apply()
    }
}
