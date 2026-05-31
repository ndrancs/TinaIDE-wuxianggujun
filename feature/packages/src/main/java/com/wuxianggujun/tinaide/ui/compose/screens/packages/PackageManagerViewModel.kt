package com.wuxianggujun.tinaide.ui.compose.screens.packages

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.wuxianggujun.tinaide.core.packages.InstalledPackageMetadata
import com.wuxianggujun.tinaide.core.packages.InstalledPackageMetadataReader
import com.wuxianggujun.tinaide.core.packages.PackageManager
import com.wuxianggujun.tinaide.core.packages.model.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import timber.log.Timber

class PackageManagerViewModel(
    private val appContext: Context,
    private val packageManager: PackageManager
) : ViewModel() {

    private companion object {
        private const val TAG = "PackageManagerViewModel"
    }

    private val _uiState = MutableStateFlow(PackageManagerUiState())
    val uiState: StateFlow<PackageManagerUiState> = _uiState.asStateFlow()

    private val _filterState = MutableStateFlow(PackageFilterState())
    val filterState: StateFlow<PackageFilterState> = _filterState.asStateFlow()

    private val _dialogState = MutableStateFlow<PackageDialogState?>(null)
    val dialogState: StateFlow<PackageDialogState?> = _dialogState.asStateFlow()

    init {
        loadPackages()
    }

    fun loadPackages() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null) }

            try {
                val installedPackages = packageManager.getInstalledPackages()
                val installedMetadata = buildInstalledMetadataMap(installedPackages)

                // 将 InstalledPackageInfo 转换为 GUIPackage
                val packages = installedPackages.map { installed ->
                    val metadata = installedMetadata[installed.packageId]
                    GUIPackage(
                        id = installed.packageId,
                        name = metadata?.name ?: installed.packageName,
                        description = metadata?.description,
                        category = metadata?.category,
                        homepage = metadata?.homepage,
                        linux = if (installed.platform == Platform.LINUX) {
                            PlatformPackage(
                                version = installed.version,
                                installType = installed.installType,
                                size = installed.size,
                                dependencies = emptyList()
                            )
                        } else null,
                        android = if (installed.platform == Platform.ANDROID) {
                            PlatformPackage(
                                version = installed.version,
                                installType = installed.installType,
                                size = installed.size,
                                abi = metadata?.abis,
                                dependencies = emptyList()
                            )
                        } else null
                    )
                }

                val installStates = packages.associate { pkg ->
                    pkg.id to packageManager.getInstallState(pkg.id)
                }

                val filter = _filterState.value
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        packages = packages,
                        filteredPackages = filterPackages(packages, installStates, filter),
                        installStates = installStates,
                        installedMetadata = installedMetadata
                    )
                }
            } catch (e: Exception) {
                Timber.tag(TAG).e(e, "Failed to load installed packages")
                _uiState.update {
                    it.copy(isLoading = false, error = e.message ?: "Failed to load installed packages")
                }
            }
        }
    }

    fun updateSearchQuery(query: String) {
        _filterState.update { it.copy(searchQuery = query) }
        applyFilter()
    }

    private fun applyFilter() {
        val state = _uiState.value
        val filter = _filterState.value
        _uiState.update {
            it.copy(filteredPackages = filterPackages(state.packages, state.installStates, filter))
        }
    }

    private fun filterPackages(
        packages: List<GUIPackage>,
        installStates: Map<String, PackageInstallState>,
        filter: PackageFilterState
    ): List<GUIPackage> {
        return packages.filter { pkg ->
            filter.searchQuery.isBlank() ||
                    pkg.name.contains(filter.searchQuery, ignoreCase = true) ||
                    pkg.description?.contains(filter.searchQuery, ignoreCase = true) == true
        }
    }

    fun refreshPackages() {
        viewModelScope.launch {
            packageManager.refreshCache()
            loadPackages()
        }
    }

    fun installPackage(packageId: String, platform: Platform) {
        val pkg = _uiState.value.packages.find { it.id == packageId } ?: return

        viewModelScope.launch {
            _dialogState.value = PackageDialogState.Installing(
                packageId = packageId,
                packageName = pkg.name,
                platform = platform,
                event = InstallProgressEvent.Preparing("Starting installation...")
            )

            val result = packageManager.install(packageId, platform) { event ->
                _dialogState.update { current ->
                    if (current is PackageDialogState.Installing && current.packageId == packageId) {
                        current.copy(event = event)
                    } else current
                }
            }

            when (result) {
                is InstallResult.Success -> {
                    refreshInstallState(packageId)
                    refreshInstalledMetadata(packageId)
                    _dialogState.value = PackageDialogState.InstallComplete(packageId, result)
                }
                is InstallResult.Failure -> {
                    _dialogState.value = PackageDialogState.InstallComplete(packageId, result)
                }
            }
        }
    }

    fun requestUninstall(packageId: String, platform: Platform) {
        val pkg = _uiState.value.packages.find { it.id == packageId } ?: return

        viewModelScope.launch {
            val dependents = packageManager.getDependentPackages(packageId, platform)
            _dialogState.value = PackageDialogState.UninstallConfirm(
                packageId = packageId,
                packageInfo = pkg,
                platform = platform,
                dependentPackages = dependents
            )
        }
    }

    fun confirmUninstall(packageId: String, platform: Platform) {
        viewModelScope.launch {
            val result = packageManager.uninstall(packageId, platform)
            refreshInstallState(packageId)
            refreshInstalledMetadata(packageId)
            _dialogState.value = PackageDialogState.UninstallComplete(packageId, result)
        }
    }

    fun showPackageDetails(packageId: String) {
        val pkg = _uiState.value.packages.find { it.id == packageId } ?: return
        _uiState.update { it.copy(currentDetailPackage = pkg, currentInstallEvent = null) }
    }

    fun closePackageDetails() {
        _uiState.update { it.copy(currentDetailPackage = null, currentInstallEvent = null) }
    }

    fun navigateToDependency(packageId: String) {
        viewModelScope.launch {
            val pkg = _uiState.value.packages.find { it.id == packageId }
            if (pkg != null) {
                _uiState.update { it.copy(currentDetailPackage = pkg, currentInstallEvent = null) }
            } else {
                packageManager.getPackageDetail(packageId).onSuccess { detail ->
                    _uiState.update { it.copy(currentDetailPackage = detail, currentInstallEvent = null) }
                }
            }
        }
    }

    fun dismissDialog() {
        _dialogState.value = null
    }

    fun toggleSelectionMode() {
        _uiState.update {
            if (it.isSelectionMode) {
                it.copy(isSelectionMode = false, selectedPackageIds = emptySet())
            } else {
                it.copy(isSelectionMode = true)
            }
        }
    }

    fun togglePackageSelection(packageId: String) {
        _uiState.update { state ->
            val newSelection = if (packageId in state.selectedPackageIds) {
                state.selectedPackageIds - packageId
            } else {
                state.selectedPackageIds + packageId
            }
            state.copy(selectedPackageIds = newSelection)
        }
    }

    fun selectAll() {
        _uiState.update { state ->
            state.copy(selectedPackageIds = state.filteredPackages.map { it.id }.toSet())
        }
    }

    fun clearSelection() {
        _uiState.update { it.copy(selectedPackageIds = emptySet()) }
    }

    fun batchUninstall(platform: Platform) {
        val selectedIds = _uiState.value.selectedPackageIds.toList()
        if (selectedIds.isEmpty()) return

        viewModelScope.launch {
            for (packageId in selectedIds) {
                packageManager.uninstall(packageId, platform)
                refreshInstallState(packageId)
            }

            _uiState.update { it.copy(isSelectionMode = false, selectedPackageIds = emptySet()) }
            loadPackages()
        }
    }

    fun checkForUpdates() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }
            val updates = packageManager.checkForUpdates()
            _uiState.update { it.copy(availableUpdates = updates) }
            loadPackages()
        }
    }

    fun updateAllPackages() {
        val updates = _uiState.value.availableUpdates
        if (updates.isEmpty()) return

        val updateList = updates.values.toList()
        _dialogState.value = PackageDialogState.BatchUpdating(
            updates = updateList,
            currentIndex = 0,
            totalCount = updateList.size,
            currentPackageName = updateList.first().packageName,
            event = InstallProgressEvent.Preparing("Starting update...")
        )

        viewModelScope.launch {
            for ((index, update) in updateList.withIndex()) {
                _dialogState.update { current ->
                    if (current is PackageDialogState.BatchUpdating) {
                        current.copy(
                            currentIndex = index,
                            currentPackageName = update.packageName,
                            event = InstallProgressEvent.Preparing("Updating ${update.packageName}...")
                        )
                    } else current
                }

                val result = packageManager.install(update.packageId, update.platform) { event ->
                    _dialogState.update { current ->
                        if (current is PackageDialogState.BatchUpdating) {
                            current.copy(event = event)
                        } else current
                    }
                }

                refreshInstallState(update.packageId)

                if (result is InstallResult.Failure) {
                    Timber.tag(TAG).e(
                        "Update failed for %s: %s",
                        update.packageId,
                        result.error.toDisplayMessage()
                    )
                }
            }

            _dialogState.value = PackageDialogState.BatchUpdateComplete(updateList.size)
            _uiState.update { it.copy(availableUpdates = emptyMap()) }
        }
    }

    private fun buildInstalledMetadataMap(
        installedPackages: List<com.wuxianggujun.tinaide.core.packages.InstalledPackageInfo>
    ): Map<String, InstalledPackageMetadata> {
        return installedPackages.asSequence()
            .filter { it.platform == Platform.ANDROID }
            .mapNotNull { installed ->
                InstalledPackageMetadataReader.read(appContext, installed.packageId)
            }
            .associateBy { it.id }
    }

    private suspend fun refreshInstallState(packageId: String) {
        val newState = packageManager.getInstallState(packageId)
        _uiState.update { state ->
            state.copy(installStates = state.installStates + (packageId to newState))
        }
    }

    private fun refreshInstalledMetadata(packageId: String) {
        val metadata = InstalledPackageMetadataReader.read(appContext, packageId)
        _uiState.update { state ->
            val newMetadata = state.installedMetadata.toMutableMap()
            if (metadata != null) {
                newMetadata[packageId] = metadata
            } else {
                newMetadata.remove(packageId)
            }
            state.copy(installedMetadata = newMetadata)
        }
    }
}

data class PackageManagerUiState(
    val isLoading: Boolean = false,
    val error: String? = null,
    val packages: List<GUIPackage> = emptyList(),
    val filteredPackages: List<GUIPackage> = emptyList(),
    val installStates: Map<String, PackageInstallState> = emptyMap(),
    val installedMetadata: Map<String, InstalledPackageMetadata> = emptyMap(),
    val selectedPackageIds: Set<String> = emptySet(),
    val isSelectionMode: Boolean = false,
    val currentDetailPackage: GUIPackage? = null,
    val currentInstallEvent: InstallProgressEvent? = null,
    val availableUpdates: Map<String, com.wuxianggujun.tinaide.core.packages.UpdateInfo> = emptyMap()
)

data class PackageFilterState(
    val searchQuery: String = ""
)

sealed class PackageDialogState {
    data class Installing(
        val packageId: String,
        val packageName: String,
        val platform: Platform,
        val event: InstallProgressEvent
    ) : PackageDialogState()

    data class InstallComplete(
        val packageId: String,
        val result: InstallResult
    ) : PackageDialogState()

    data class UninstallConfirm(
        val packageId: String,
        val packageInfo: GUIPackage,
        val platform: Platform,
        val dependentPackages: List<String>
    ) : PackageDialogState()

    data class UninstallComplete(
        val packageId: String,
        val result: UninstallResult
    ) : PackageDialogState()

    data class PackageDetails(val packageInfo: GUIPackage) : PackageDialogState()

    data class BatchInstalling(
        val packageIds: List<String>,
        val platform: Platform,
        val currentIndex: Int,
        val totalCount: Int,
        val currentPackageName: String,
        val event: InstallProgressEvent
    ) : PackageDialogState()

    data class BatchInstallComplete(
        val totalCount: Int,
        val platform: Platform
    ) : PackageDialogState()

    data class BatchPlatformSelect(
        val packageIds: List<String>
    ) : PackageDialogState()

    data class BatchUpdating(
        val updates: List<com.wuxianggujun.tinaide.core.packages.UpdateInfo>,
        val currentIndex: Int,
        val totalCount: Int,
        val currentPackageName: String,
        val event: InstallProgressEvent
    ) : PackageDialogState()

    data class BatchUpdateComplete(
        val totalCount: Int
    ) : PackageDialogState()
}
