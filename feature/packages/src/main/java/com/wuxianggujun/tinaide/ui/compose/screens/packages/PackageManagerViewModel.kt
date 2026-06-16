package com.wuxianggujun.tinaide.ui.compose.screens.packages

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.wuxianggujun.tinaide.core.i18n.Strings
import com.wuxianggujun.tinaide.core.i18n.strOr
import com.wuxianggujun.tinaide.core.packages.InstalledPackageMetadata
import com.wuxianggujun.tinaide.core.packages.InstalledPackageMetadataReader
import com.wuxianggujun.tinaide.core.packages.PackageInstallPlan
import com.wuxianggujun.tinaide.core.packages.PackageManager
import com.wuxianggujun.tinaide.core.packages.model.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
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

    // 进行中的安装任务：用于支持用户主动取消（取消协程会触发 OkHttp Call.cancel 立即断开连接）。
    private var installJob: Job? = null

    init {
        loadPackages()
    }

    fun loadPackages() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null) }

            try {
                val availablePackages = packageManager.getAvailablePackages()
                    .getOrElse { throwable ->
                        Timber.tag(TAG).w(throwable, "Failed to load available packages; falling back to installed packages")
                        emptyList()
                    }
                val installedPackages = packageManager.getInstalledPackages()
                val installedMetadata = buildInstalledMetadataMap(installedPackages)

                // 将 InstalledPackageInfo 转换为 GUIPackage
                val packages = mergeAvailableAndInstalledPackages(
                    availablePackages = availablePackages,
                    installedPackages = installedPackages,
                    installedMetadata = installedMetadata
                )

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
                Timber.tag(TAG).e(e, "Failed to load packages")
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        error = e.message ?: Strings.pkg_manager_load_failed.strOr(appContext)
                    )
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
        val tokens = filter.searchQuery
            .split(',', ' ', '\n', '\t')
            .map { it.trim() }
            .filter { it.isNotBlank() }
        if (tokens.isEmpty()) return packages

        return packages.filter { pkg ->
            tokens.any { token ->
                pkg.id.contains(token, ignoreCase = true) ||
                    pkg.name.contains(token, ignoreCase = true) ||
                    pkg.description?.contains(token, ignoreCase = true) == true ||
                    pkg.category?.contains(token, ignoreCase = true) == true
            }
        }
    }

    fun refreshPackages() {
        viewModelScope.launch {
            packageManager.refreshCache()
            loadPackages()
        }
    }

    fun installPackage(packageId: String, platform: Platform) {
        val pkg = _uiState.value.packages.find { it.id == packageId } ?: run {
            _dialogState.value = PackageDialogState.InstallComplete(
                packageId = packageId,
                result = InstallResult.Failure(
                    packageId = packageId,
                    error = InstallError.UnknownError(
                        Strings.pkg_manager_package_not_found.strOr(appContext, packageId)
                    )
                )
            )
            return
        }
        val installPlatform = PackageInstallUiStateSupport.resolveAvailableInstallPlatform(pkg, platform) ?: run {
            _dialogState.value = PackageDialogState.InstallComplete(
                packageId = packageId,
                result = InstallResult.Failure(
                    packageId = packageId,
                    error = InstallError.UnknownError(
                        Strings.pkg_manager_package_not_installable.strOr(appContext, packageId)
                    )
                )
            )
            return
        }

        val job = viewModelScope.launch {
            val plan = packageManager.previewInstallPlan(packageId, installPlatform).getOrElse { error ->
                _dialogState.value = PackageDialogState.InstallComplete(
                    packageId = packageId,
                    result = InstallResult.Failure(
                        packageId = packageId,
                        error = InstallError.UnknownError(
                            error.message ?: Strings.pkg_manager_error_unknown.strOr(appContext)
                        )
                    )
                )
                return@launch
            }
            val dependenciesToInstall = plan.packages.filterNot { it.isRoot }
            if (dependenciesToInstall.isNotEmpty()) {
                _dialogState.value = PackageDialogState.InstallConfirm(
                    packageId = packageId,
                    packageInfo = pkg,
                    platform = installPlatform,
                    plan = plan
                )
                return@launch
            }

            startInstall(pkg, installPlatform)
        }
        installJob = job
        job.invokeOnCompletion { if (installJob === job) installJob = null }
    }

    fun confirmInstall(packageId: String, platform: Platform) {
        val pkg = _uiState.value.packages.find { it.id == packageId } ?: return
        val installPlatform = PackageInstallUiStateSupport.resolveAvailableInstallPlatform(pkg, platform) ?: return
        val job = viewModelScope.launch {
            startInstall(pkg, installPlatform)
        }
        installJob = job
        job.invokeOnCompletion { if (installJob === job) installJob = null }
    }

    /**
     * 取消进行中的安装。取消协程会触发 OkHttp `Call.cancel()` 立即断开网络连接，
     * 关闭对话框，用户可在网络恢复后重新点击下载。
     */
    fun cancelInstall() {
        installJob?.cancel(CancellationException("Package install cancelled by user"))
        installJob = null
        _dialogState.value = null
    }

    private suspend fun startInstall(pkg: GUIPackage, installPlatform: Platform) {
        val packageId = pkg.id
        _dialogState.value = PackageDialogState.Installing(
            packageId = packageId,
            packageName = pkg.name,
            platform = installPlatform,
            event = InstallProgressEvent.Preparing(
                Strings.pkg_manager_progress_starting.strOr(appContext)
            )
        )

        val result = try {
            packageManager.install(packageId, installPlatform) { event ->
                _dialogState.update { current ->
                    if (current is PackageDialogState.Installing && current.packageId == packageId) {
                        current.copy(event = event)
                    } else {
                        current
                    }
                }
            }
        } catch (e: CancellationException) {
            // 用户主动取消：对话框已由 cancelInstall 关闭，向上抛出由协程框架处理。
            throw e
        } catch (throwable: Throwable) {
            InstallResult.Failure(
                packageId = packageId,
                error = InstallError.UnknownError(
                    throwable.message ?: Strings.pkg_manager_error_unknown.strOr(appContext)
                )
            )
        }

        when (result) {
            is InstallResult.Success -> {
                refreshInstallState(packageId)
                refreshInstalledMetadata(packageId)
                _dialogState.value = PackageDialogState.InstallComplete(packageId, result)
                loadPackages()
            }
            is InstallResult.Failure -> {
                _dialogState.value = PackageDialogState.InstallComplete(packageId, result)
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

    fun batchInstall(platform: Platform) {
        val selectedIds = _uiState.value.selectedPackageIds.toList()
        if (selectedIds.isEmpty()) return

        val installableIds = selectedIds.filter { packageId ->
            val pkg = _uiState.value.packages.find { it.id == packageId }
            pkg?.platformPackageFor(platform) != null
        }
        if (installableIds.isEmpty()) return

        viewModelScope.launch {
            val plans = previewInstallPlans(
                packageIds = installableIds,
                platform = platform
            ).getOrElse { error ->
                _dialogState.value = PackageDialogState.InstallComplete(
                    packageId = installableIds.first(),
                    result = InstallResult.Failure(
                        packageId = installableIds.first(),
                        error = InstallError.UnknownError(
                            error.message ?: Strings.pkg_manager_error_unknown.strOr(appContext)
                        )
                    )
                )
                return@launch
            }

            if (plans.hasPendingDependencies()) {
                _dialogState.value = PackageDialogState.BatchInstallConfirm(
                    packageIds = installableIds,
                    platform = platform,
                    plans = plans
                )
                return@launch
            }

            startBatchInstall(installableIds, platform)
        }
    }

    fun confirmBatchInstall() {
        val state = _dialogState.value as? PackageDialogState.BatchInstallConfirm ?: return
        viewModelScope.launch {
            startBatchInstall(state.packageIds, state.platform)
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
        viewModelScope.launch {
            val plans = previewUpdatePlans(updateList).getOrElse { error ->
                val failedUpdate = updateList.first()
                _dialogState.value = PackageDialogState.InstallComplete(
                    packageId = failedUpdate.packageId,
                    result = InstallResult.Failure(
                        packageId = failedUpdate.packageId,
                        error = InstallError.UnknownError(
                            error.message ?: Strings.pkg_manager_error_unknown.strOr(appContext)
                        )
                    )
                )
                return@launch
            }

            if (plans.hasPendingDependencies()) {
                _dialogState.value = PackageDialogState.BatchUpdateConfirm(
                    updates = updateList,
                    plans = plans
                )
                return@launch
            }

            startUpdateAllPackages(updateList)
        }
    }

    fun confirmBatchUpdate() {
        val state = _dialogState.value as? PackageDialogState.BatchUpdateConfirm ?: return
        viewModelScope.launch {
            startUpdateAllPackages(state.updates)
        }
    }

    private suspend fun startBatchInstall(
        packageIds: List<String>,
        platform: Platform
    ) {
        if (packageIds.isEmpty()) return
        val firstPackageName = _uiState.value.packages.find { it.id == packageIds.first() }?.name
            ?: packageIds.first()
        _dialogState.value = PackageDialogState.BatchInstalling(
            packageIds = packageIds,
            platform = platform,
            currentIndex = 0,
            totalCount = packageIds.size,
            currentPackageName = firstPackageName,
            event = InstallProgressEvent.Preparing(
                Strings.pkg_manager_progress_starting.strOr(appContext)
            )
        )

        for ((index, packageId) in packageIds.withIndex()) {
            val packageName = _uiState.value.packages.find { it.id == packageId }?.name ?: packageId
            _dialogState.update { current ->
                if (current is PackageDialogState.BatchInstalling) {
                    current.copy(
                        currentIndex = index,
                        currentPackageName = packageName,
                        event = InstallProgressEvent.Preparing(
                            Strings.pkg_manager_progress_installing.strOr(appContext, packageName)
                        )
                    )
                } else {
                    current
                }
            }

            val result = packageManager.install(packageId, platform) { event ->
                _dialogState.update { current ->
                    if (current is PackageDialogState.BatchInstalling) {
                        current.copy(event = event)
                    } else {
                        current
                    }
                }
            }

            refreshInstallState(packageId)
            refreshInstalledMetadata(packageId)

            if (result is InstallResult.Failure) {
                Timber.tag(TAG).e(
                    "Batch install failed for %s: %s",
                    packageId,
                    result.error.toDisplayMessage()
                )
            }
        }

        _dialogState.value = PackageDialogState.BatchInstallComplete(packageIds.size, platform)
        _uiState.update { it.copy(isSelectionMode = false, selectedPackageIds = emptySet()) }
        loadPackages()
    }

    private suspend fun startUpdateAllPackages(
        updateList: List<com.wuxianggujun.tinaide.core.packages.UpdateInfo>
    ) {
        _dialogState.value = PackageDialogState.BatchUpdating(
            updates = updateList,
            currentIndex = 0,
            totalCount = updateList.size,
            currentPackageName = updateList.first().packageName,
            event = InstallProgressEvent.Preparing(
                Strings.pkg_manager_progress_update_starting.strOr(appContext)
            )
        )

        for ((index, update) in updateList.withIndex()) {
            _dialogState.update { current ->
                if (current is PackageDialogState.BatchUpdating) {
                    current.copy(
                        currentIndex = index,
                        currentPackageName = update.packageName,
                        event = InstallProgressEvent.Preparing(
                            Strings.pkg_manager_progress_updating_package.strOr(
                                appContext,
                                update.packageName
                            )
                        )
                    )
                } else {
                    current
                }
            }

            val result = packageManager.install(update.packageId, update.platform) { event ->
                _dialogState.update { current ->
                    if (current is PackageDialogState.BatchUpdating) {
                        current.copy(event = event)
                    } else {
                        current
                    }
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
        loadPackages()
    }

    private suspend fun previewInstallPlans(
        packageIds: List<String>,
        platform: Platform
    ): Result<List<PackageInstallPlan>> {
        val plans = mutableListOf<PackageInstallPlan>()
        for (packageId in packageIds) {
            plans += packageManager.previewInstallPlan(packageId, platform).getOrElse { error ->
                return Result.failure(error)
            }
        }
        return Result.success(plans)
    }

    private suspend fun previewUpdatePlans(
        updates: List<com.wuxianggujun.tinaide.core.packages.UpdateInfo>
    ): Result<List<PackageInstallPlan>> {
        val plans = mutableListOf<PackageInstallPlan>()
        for (update in updates) {
            plans += packageManager.previewInstallPlan(update.packageId, update.platform).getOrElse { error ->
                return Result.failure(error)
            }
        }
        return Result.success(plans)
    }

    private fun buildInstalledMetadataMap(
        installedPackages: List<com.wuxianggujun.tinaide.core.packages.InstalledPackageInfo>
    ): Map<String, InstalledPackageMetadata> = installedPackages.asSequence()
        .filter { it.platform == Platform.ANDROID }
        .mapNotNull { installed ->
            InstalledPackageMetadataReader.read(appContext, installed.packageId)
        }
        .associateBy { it.id }

    private fun mergeAvailableAndInstalledPackages(
        availablePackages: List<GUIPackage>,
        installedPackages: List<com.wuxianggujun.tinaide.core.packages.InstalledPackageInfo>,
        installedMetadata: Map<String, InstalledPackageMetadata>
    ): List<GUIPackage> {
        val packagesById = availablePackages.associateByTo(linkedMapOf()) { it.id }
        installedPackages.forEach { installed ->
            if (packagesById.containsKey(installed.packageId)) return@forEach
            val metadata = installedMetadata[installed.packageId]
            packagesById[installed.packageId] = GUIPackage(
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
                } else {
                    null
                },
                android = if (installed.platform == Platform.ANDROID) {
                    PlatformPackage(
                        version = installed.version,
                        installType = installed.installType,
                        size = installed.size,
                        abi = metadata?.abis,
                        dependencies = emptyList()
                    )
                } else {
                    null
                }
            )
        }
        return packagesById.values.sortedBy { it.name.lowercase() }
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
    data class InstallConfirm(
        val packageId: String,
        val packageInfo: GUIPackage,
        val platform: Platform,
        val plan: PackageInstallPlan
    ) : PackageDialogState()

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

    data class BatchInstallConfirm(
        val packageIds: List<String>,
        val platform: Platform,
        val plans: List<PackageInstallPlan>
    ) : PackageDialogState()

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

    data class BatchUpdateConfirm(
        val updates: List<com.wuxianggujun.tinaide.core.packages.UpdateInfo>,
        val plans: List<PackageInstallPlan>
    ) : PackageDialogState()

    data class BatchUpdateComplete(
        val totalCount: Int
    ) : PackageDialogState()
}

private fun GUIPackage.platformPackageFor(platform: Platform): PlatformPackage? = when (platform) {
    Platform.LINUX -> linux
    Platform.ANDROID -> android
}

private fun List<PackageInstallPlan>.hasPendingDependencies(): Boolean = any { plan ->
    plan.packages.any { item -> !item.isRoot && !item.isAlreadyInstalled }
}
