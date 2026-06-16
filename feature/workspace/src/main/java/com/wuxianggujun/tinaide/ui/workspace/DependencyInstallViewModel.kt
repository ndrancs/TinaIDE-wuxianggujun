package com.wuxianggujun.tinaide.ui.workspace

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.wuxianggujun.tinaide.core.config.ConfigKeys
import com.wuxianggujun.tinaide.core.config.IConfigManager
import com.wuxianggujun.tinaide.core.i18n.Strings
import com.wuxianggujun.tinaide.core.i18n.strOr
import com.wuxianggujun.tinaide.core.ndk.AndroidNativeToolchainManager
import com.wuxianggujun.tinaide.core.ndk.AndroidSysrootManager
import com.wuxianggujun.tinaide.core.proot.InstallLogManager
import com.wuxianggujun.tinaide.core.proot.LinuxDistroRootfsHealthLevel
import com.wuxianggujun.tinaide.core.proot.LinuxDistroRootfsHealthProbe
import com.wuxianggujun.tinaide.core.proot.LinuxDistroRootfsHealthReport
import com.wuxianggujun.tinaide.core.proot.PRootBootstrap
import com.wuxianggujun.tinaide.core.proot.PRootGuestToolchainInstaller
import com.wuxianggujun.tinaide.core.proot.RootfsDistroRuntime
import com.wuxianggujun.tinaide.core.proot.ToolchainConfig
import com.wuxianggujun.tinaide.core.proot.toHealthSummary
import com.wuxianggujun.tinaide.ui.workspace.model.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 依赖安装 ViewModel
 *
 * 负责管理依赖安装页面的状态和业务逻辑，遵循 MVVM 架构。
 */
class DependencyInstallViewModel(
    private val applicationContext: Context,
    private val configManager: IConfigManager,
    private val installLogManager: InstallLogManager,
    private val toolchainConfig: ToolchainConfig,
    private val preferredLlvmMajorVersion: Int?,
    private val installLinuxEnvironment: Boolean
) : ViewModel() {
    companion object {
        private const val ROOTFS_PROGRESS_WEIGHT = 0.45f
        private const val PROOT_TOOLCHAIN_PROGRESS_WEIGHT = 0.20f
        private const val SYSROOT_PROGRESS_WEIGHT = 0.175f
        private const val TOOLCHAIN_PROGRESS_WEIGHT = 0.175f

        private const val PACKAGE_LINUX_DISTRO_RUNTIME = "linux-distro-runtime"
        private const val PACKAGE_LINUX_ROOTFS = "linux-rootfs"
        private const val PACKAGE_PROOT_GUEST_TOOLCHAIN = "proot-guest-toolchain"
        private const val PACKAGE_ANDROID_SYSROOT = "android-sysroot"
        private const val PACKAGE_NATIVE_TOOLCHAIN = "native-toolchain"

        private val PACKAGE_ORDER = listOf(
            PACKAGE_LINUX_DISTRO_RUNTIME,
            PACKAGE_LINUX_ROOTFS,
            PACKAGE_PROOT_GUEST_TOOLCHAIN,
            PACKAGE_ANDROID_SYSROOT,
            PACKAGE_NATIVE_TOOLCHAIN
        )
    }

    private val packageOrder = if (installLinuxEnvironment) {
        PACKAGE_ORDER
    } else {
        listOf(PACKAGE_ANDROID_SYSROOT, PACKAGE_NATIVE_TOOLCHAIN)
    }
    private val rootfsProgressWeight = if (installLinuxEnvironment) ROOTFS_PROGRESS_WEIGHT else 0f
    private val prootToolchainProgressWeight = if (installLinuxEnvironment) PROOT_TOOLCHAIN_PROGRESS_WEIGHT else 0f
    private val sysrootProgressWeight = if (installLinuxEnvironment) SYSROOT_PROGRESS_WEIGHT else 0.5f
    private val toolchainProgressWeight = if (installLinuxEnvironment) TOOLCHAIN_PROGRESS_WEIGHT else 0.5f
    private val guestToolchainInstaller by lazy { PRootGuestToolchainInstaller(applicationContext) }
    private var rootfsHealthJob: Job? = null
    private var toolchainInstallJob: Job? = null

    private val initialEnvReady = if (installLinuxEnvironment) {
        PRootBootstrap.isEnvironmentReady(applicationContext)
    } else {
        true
    }
    @Volatile
    private var toolchainInstallStarted = false

    @Volatile
    private var toolchainInstallCompleted = false

    // 工具链是否就绪需要启动 PRoot 进程探测（耗时 IO），不能在主线程同步判断，
    // 因此初始状态先按"准备中"呈现，真正的就绪检测在 init 的协程里完成。
    private val _uiState = MutableStateFlow(
        DependencyInstallUiState(
            installPhase = InstallPhase.INSTALLING,
            progress = if (initialEnvReady) rootfsProgressWeight else 0f,
            statusMessage = Strings.install_status_preparing.strOr(applicationContext),
            installStage = if (installLinuxEnvironment) {
                PRootBootstrap.InstallStage.INSTALLING_DISTRO
            } else {
                PRootBootstrap.InstallStage.PREPARING_RUNTIME
            },
            packageList = buildInitialPackageList(initialEnvReady, toolchainReady = false),
            envReady = initialEnvReady,
            rootfsHealth = if (installLinuxEnvironment) {
                DependencyRootfsHealthUiState(
                    statusText = Strings.workspace_linux_health_unknown.strOr(applicationContext),
                )
            } else {
                DependencyRootfsHealthUiState()
            },
        )
    )
    val uiState: StateFlow<DependencyInstallUiState> = _uiState.asStateFlow()

    private val _events = MutableSharedFlow<DependencyInstallEvent>(extraBufferCapacity = 1)
    val events: SharedFlow<DependencyInstallEvent> = _events.asSharedFlow()

    init {
        if (installLinuxEnvironment) {
            observeBootstrapState()
        }
        viewModelScope.launch {
            startInstallation()
        }
    }

    private fun observeBootstrapState() {
        viewModelScope.launch {
            PRootBootstrap.state.collect { bootstrapState ->
                updateStateFromBootstrap(bootstrapState)
            }
        }
    }

    private fun updateStateFromBootstrap(bootstrapState: PRootBootstrap.BootstrapState) {
        if (!installLinuxEnvironment) {
            return
        }

        if (toolchainInstallStarted &&
            !toolchainInstallCompleted &&
            bootstrapState !is PRootBootstrap.BootstrapState.Failed
        ) {
            // 正在安装内置工具链时，忽略 Bootstrap 的静态状态回推，避免覆盖当前进度
            return
        }

        val newPhase = when (bootstrapState) {
            is PRootBootstrap.BootstrapState.Idle -> {
                if (_uiState.value.envReady && toolchainInstallCompleted) InstallPhase.COMPLETED else InstallPhase.INSTALLING
            }
            is PRootBootstrap.BootstrapState.Installing -> InstallPhase.INSTALLING
            is PRootBootstrap.BootstrapState.Installed -> {
                if (toolchainInstallCompleted) InstallPhase.COMPLETED else InstallPhase.INSTALLING
            }
            is PRootBootstrap.BootstrapState.Failed -> InstallPhase.FAILED
            is PRootBootstrap.BootstrapState.NeedsToolchainRepair -> InstallPhase.INSTALLING
        }

        val (progress, message) = when (bootstrapState) {
            is PRootBootstrap.BootstrapState.Installing -> {
                val displayMessage = when {
                    bootstrapState.currentPackage != null -> {
                        val pkg = bootstrapState.packages.find { it.matchesPackageName(bootstrapState.currentPackage) }
                        val detailedMessage = bootstrapState.message.takeIf { it.isNotBlank() }
                        when (pkg?.status) {
                            PRootBootstrap.PackageStatus.DOWNLOADING ->
                                detailedMessage ?: Strings.install_status_downloading.strOr(applicationContext, pkg.displayName)
                            PRootBootstrap.PackageStatus.INSTALLING ->
                                detailedMessage ?: Strings.install_status_installing.strOr(applicationContext, pkg.displayName)
                            else -> bootstrapState.message
                        }
                    }
                    else -> bootstrapState.message
                }
                (bootstrapState.progress * rootfsProgressWeight) to displayMessage
            }
            is PRootBootstrap.BootstrapState.Installed ->
                rootfsProgressWeight to Strings.install_status_preparing.strOr(applicationContext)
            is PRootBootstrap.BootstrapState.Failed -> 0f to bootstrapState.message
            else -> {
                if (_uiState.value.envReady) {
                    rootfsProgressWeight to Strings.install_status_preparing.strOr(applicationContext)
                } else {
                    0f to Strings.install_status_preparing.strOr(applicationContext)
                }
            }
        }

        val (installStage, rootfsPackages, currentPackage) = when (bootstrapState) {
            is PRootBootstrap.BootstrapState.Installing ->
                Triple(bootstrapState.stage, bootstrapState.packages, bootstrapState.currentPackage)
            else ->
                Triple(PRootBootstrap.InstallStage.INSTALLING_DISTRO, emptyList(), null)
        }

        val failedMessage = (bootstrapState as? PRootBootstrap.BootstrapState.Failed)?.message ?: ""
        val isNetworkRelated = (bootstrapState as? PRootBootstrap.BootstrapState.Failed)?.isNetworkRelated ?: false

        _uiState.update { state ->
            state.copy(
                installPhase = newPhase,
                progress = progress,
                statusMessage = message,
                installStage = installStage,
                packageList = mergePackageList(state, rootfsPackages),
                currentPackage = currentPackage,
                failedMessage = failedMessage,
                isNetworkRelated = isNetworkRelated
            )
        }

        if (bootstrapState is PRootBootstrap.BootstrapState.Installed) {
            _uiState.update { state ->
                state.copy(
                    envReady = true,
                    packageList = state.packageList.map {
                        when (it.name) {
                            PACKAGE_LINUX_DISTRO_RUNTIME,
                            PACKAGE_LINUX_ROOTFS -> it.copy(status = PRootBootstrap.PackageStatus.COMPLETED)
                            else -> it
                        }
                    }
                )
            }
            startToolchainInstallIfNeeded()
        }

        if (bootstrapState is PRootBootstrap.BootstrapState.NeedsToolchainRepair) {
            PRootBootstrap.startToolchainRepair(applicationContext)
        }
    }

    private suspend fun startInstallation() {
        if (installLinuxEnvironment && !_uiState.value.envReady) {
            PRootBootstrap.start(applicationContext)
            return
        }

        if (needsToolchainInstall()) {
            startToolchainInstallIfNeeded()
        } else {
            markInstallCompleted()
        }
    }

    fun retry() {
        if (!installLinuxEnvironment) {
            startToolchainInstallIfNeeded(force = true)
            return
        }

        viewModelScope.launch {
            if (_uiState.value.envReady && needsToolchainInstall()) {
                startToolchainInstallIfNeeded(force = true)
                return@launch
            }

            PRootBootstrap.restart(
                applicationContext = applicationContext,
                reason = "manual retry"
            )
        }
    }

    fun togglePause() {
        _uiState.update { it.copy(isPaused = !it.isPaused) }
    }

    fun cancelInstallation() {
        val reason = "Dependency installation cancelled by user"
        toolchainInstallJob?.cancel(CancellationException(reason))
        toolchainInstallJob = null
        toolchainInstallStarted = false
        rootfsHealthJob?.cancel()
        rootfsHealthJob = null
        if (installLinuxEnvironment) {
            PRootBootstrap.cancel(applicationContext, reason)
        }
    }

    fun onInstallComplete() {
        configManager.set(ConfigKeys.WorkspaceSetupCompleted, true)
        viewModelScope.launch {
            _events.emit(DependencyInstallEvent.NavigateToProjectManager)
        }
    }

    private fun startToolchainInstallIfNeeded(force: Boolean = false) {
        if (toolchainInstallCompleted && !force) {
            markInstallCompleted()
            return
        }
        if (toolchainInstallStarted && !force) return
        if (force) {
            toolchainInstallJob?.cancel(CancellationException("Dependency installation restarted"))
            toolchainInstallJob = null
            toolchainInstallStarted = false
        }

        toolchainInstallStarted = true
        toolchainInstallCompleted = false

        _uiState.update { state ->
            state.copy(
                installPhase = InstallPhase.INSTALLING,
                progress = maxOf(state.progress, rootfsProgressWeight),
                statusMessage = Strings.install_status_preparing.strOr(applicationContext),
                installStage = PRootBootstrap.InstallStage.PREPARING_RUNTIME,
                packageList = state.packageList.map {
                    when (it.name) {
                        PACKAGE_LINUX_DISTRO_RUNTIME,
                        PACKAGE_LINUX_ROOTFS -> it.copy(status = PRootBootstrap.PackageStatus.COMPLETED)
                        else -> it
                    }
                },
                currentPackage = if (installLinuxEnvironment) PACKAGE_PROOT_GUEST_TOOLCHAIN else PACKAGE_ANDROID_SYSROOT,
                failedMessage = "",
                isNetworkRelated = false
            )
        }

        toolchainInstallJob = viewModelScope.launch {
            runCatching {
                installBuiltinToolchain()
            }.onSuccess {
                toolchainInstallStarted = false
                toolchainInstallCompleted = true
                toolchainInstallJob = null
                markInstallCompleted()
            }.onFailure { error ->
                if (error is CancellationException) {
                    toolchainInstallStarted = false
                    toolchainInstallJob = null
                    return@onFailure
                }
                toolchainInstallStarted = false
                toolchainInstallJob = null
                val fallbackMessage = Strings.error_unknown.strOr(applicationContext)
                val message = error.message?.takeIf { it.isNotBlank() } ?: fallbackMessage
                _uiState.update { state ->
                    state.copy(
                        installPhase = InstallPhase.FAILED,
                        failedMessage = message,
                        statusMessage = message,
                        isNetworkRelated = false
                    )
                }
            }
        }
    }

    private suspend fun installBuiltinToolchain() = withContext(Dispatchers.IO) {
        val guestToolchainLabel = packageDisplayName(PACKAGE_PROOT_GUEST_TOOLCHAIN)
        val sysrootManager = AndroidSysrootManager(applicationContext)
        val toolchainManager = AndroidNativeToolchainManager(applicationContext)
        val sysrootLabel = packageDisplayName(PACKAGE_ANDROID_SYSROOT)
        val toolchainLabel = packageDisplayName(PACKAGE_NATIVE_TOOLCHAIN)

        if (installLinuxEnvironment) {
            if (guestToolchainInstaller.isInstalled(toolchainConfig)) {
                setPackageStatus(PACKAGE_PROOT_GUEST_TOOLCHAIN, PRootBootstrap.PackageStatus.COMPLETED)
                _uiState.update { state ->
                    state.copy(
                        progress = rootfsProgressWeight + prootToolchainProgressWeight,
                        statusMessage = Strings.install_status_completed.strOr(applicationContext),
                        installStage = PRootBootstrap.InstallStage.PREPARING_RUNTIME,
                        currentPackage = PACKAGE_ANDROID_SYSROOT
                    )
                }
            } else {
                setPackageStatus(PACKAGE_PROOT_GUEST_TOOLCHAIN, PRootBootstrap.PackageStatus.INSTALLING)
                guestToolchainInstaller.install(toolchainConfig) { progress ->
                    val stepFraction = progress.current.toFloat() / progress.total.coerceAtLeast(1)
                    _uiState.update { state ->
                        state.copy(
                            progress = rootfsProgressWeight + stepFraction * prootToolchainProgressWeight,
                            statusMessage = Strings.install_status_installing.strOr(applicationContext, progress.displayName),
                            installStage = PRootBootstrap.InstallStage.PREPARING_RUNTIME,
                            currentPackage = PACKAGE_PROOT_GUEST_TOOLCHAIN
                        )
                    }
                }.getOrThrow()
                setPackageStatus(PACKAGE_PROOT_GUEST_TOOLCHAIN, PRootBootstrap.PackageStatus.COMPLETED)
            }
        }

        // 1) 安装 Android sysroot
        if (sysrootManager.isInstalled()) {
            setPackageStatus(PACKAGE_ANDROID_SYSROOT, PRootBootstrap.PackageStatus.COMPLETED)
            _uiState.update { state ->
                state.copy(
                    progress = rootfsProgressWeight + prootToolchainProgressWeight + sysrootProgressWeight,
                    statusMessage = Strings.install_status_completed.strOr(applicationContext),
                    installStage = PRootBootstrap.InstallStage.PREPARING_RUNTIME,
                    currentPackage = PACKAGE_NATIVE_TOOLCHAIN
                )
            }
        } else {
            setPackageStatus(PACKAGE_ANDROID_SYSROOT, PRootBootstrap.PackageStatus.INSTALLING)
            val result = sysrootManager.install { p ->
                _uiState.update { state ->
                    state.copy(
                        progress = rootfsProgressWeight + prootToolchainProgressWeight + p * sysrootProgressWeight,
                        statusMessage = Strings.install_status_installing.strOr(applicationContext, sysrootLabel),
                        installStage = PRootBootstrap.InstallStage.PREPARING_RUNTIME,
                        currentPackage = PACKAGE_ANDROID_SYSROOT
                    )
                }
            }
            result.getOrThrow()
            setPackageStatus(PACKAGE_ANDROID_SYSROOT, PRootBootstrap.PackageStatus.COMPLETED)
        }

        // 2) 安装 Native toolchain
        if (toolchainManager.isReadyForCurrentAssets()) {
            setPackageStatus(PACKAGE_NATIVE_TOOLCHAIN, PRootBootstrap.PackageStatus.COMPLETED)
            _uiState.update { state ->
                state.copy(
                    progress = 1f,
                    statusMessage = Strings.install_status_completed.strOr(applicationContext),
                    installStage = PRootBootstrap.InstallStage.COMPLETED,
                    currentPackage = null
                )
            }
        } else {
            setPackageStatus(PACKAGE_NATIVE_TOOLCHAIN, PRootBootstrap.PackageStatus.INSTALLING)
            val result = toolchainManager.install { p ->
                _uiState.update { state ->
                    state.copy(
                        progress = rootfsProgressWeight + prootToolchainProgressWeight + sysrootProgressWeight + p * toolchainProgressWeight,
                        statusMessage = Strings.install_status_installing.strOr(applicationContext, toolchainLabel),
                        installStage = PRootBootstrap.InstallStage.PREPARING_RUNTIME,
                        currentPackage = PACKAGE_NATIVE_TOOLCHAIN
                    )
                }
            }
            result.getOrThrow()
            setPackageStatus(PACKAGE_NATIVE_TOOLCHAIN, PRootBootstrap.PackageStatus.COMPLETED)
        }
    }

    private fun markInstallCompleted() {
        toolchainInstallCompleted = true
        _uiState.update { state ->
            state.copy(
                installPhase = InstallPhase.COMPLETED,
                progress = 1f,
                statusMessage = Strings.install_status_completed.strOr(applicationContext),
                installStage = PRootBootstrap.InstallStage.COMPLETED,
                packageList = mergePackageList(
                    state = state.copy(envReady = true),
                    rootfsPackages = buildList {
                        if (installLinuxEnvironment) {
                            add(
                                PRootBootstrap.PackageInfo(
                                    name = PACKAGE_LINUX_DISTRO_RUNTIME,
                                    displayName = packageDisplayName(PACKAGE_LINUX_DISTRO_RUNTIME),
                                    status = PRootBootstrap.PackageStatus.COMPLETED
                                )
                            )
                            add(
                                PRootBootstrap.PackageInfo(
                                    name = PACKAGE_LINUX_ROOTFS,
                                    displayName = packageDisplayName(PACKAGE_LINUX_ROOTFS),
                                    status = PRootBootstrap.PackageStatus.COMPLETED
                                )
                            )
                            add(
                                PRootBootstrap.PackageInfo(
                                    name = PACKAGE_PROOT_GUEST_TOOLCHAIN,
                                    displayName = packageDisplayName(PACKAGE_PROOT_GUEST_TOOLCHAIN),
                                    status = PRootBootstrap.PackageStatus.COMPLETED
                                )
                            )
                        }
                        add(
                            PRootBootstrap.PackageInfo(
                                name = PACKAGE_ANDROID_SYSROOT,
                                displayName = packageDisplayName(PACKAGE_ANDROID_SYSROOT),
                                status = PRootBootstrap.PackageStatus.COMPLETED
                            )
                        )
                        add(
                            PRootBootstrap.PackageInfo(
                                name = PACKAGE_NATIVE_TOOLCHAIN,
                                displayName = packageDisplayName(PACKAGE_NATIVE_TOOLCHAIN),
                                status = PRootBootstrap.PackageStatus.COMPLETED
                            )
                        )
                    }
                ),
                currentPackage = null,
                failedMessage = "",
                isNetworkRelated = false,
                envReady = true
            )
        }
        refreshRootfsHealthSnapshot()
        // 注意：不再自动设置 WorkspaceSetupCompleted 标记
        // 标记应该由用户点击"完成"按钮时通过 onInstallComplete() 设置
    }

    fun refreshRootfsHealth() {
        refreshRootfsHealthSnapshot()
    }

    private fun refreshRootfsHealthSnapshot() {
        if (!installLinuxEnvironment) return

        rootfsHealthJob?.cancel()
        rootfsHealthJob = viewModelScope.launch {
            installLogManager.info(Strings.workspace_linux_health_log_started.strOr(applicationContext))
            _uiState.update { state ->
                state.copy(
                    rootfsHealth = DependencyRootfsHealthUiState(
                        status = DependencyRootfsHealthStatus.CHECKING,
                        statusText = Strings.workspace_linux_health_checking.strOr(applicationContext),
                    )
                )
            }

            RootfsDistroRuntime(applicationContext, configManager)
                .checkActiveDistroHealth()
                .onSuccess { report ->
                    val healthState = report.toDependencyRootfsHealthUiState()
                    _uiState.update { state ->
                        state.copy(rootfsHealth = healthState)
                    }
                    logRootfsHealthResult(healthState)
                }
                .onFailure { error ->
                    val healthState = DependencyRootfsHealthUiState(
                        status = DependencyRootfsHealthStatus.UNAVAILABLE,
                        statusText = Strings.workspace_linux_health_unavailable.strOr(applicationContext),
                        detailText = Strings.workspace_linux_health_check_failed.strOr(
                            applicationContext,
                            error.message ?: Strings.error_unknown.strOr(applicationContext),
                        ),
                    )
                    _uiState.update { state ->
                        state.copy(rootfsHealth = healthState)
                    }
                    logRootfsHealthResult(healthState)
                }
        }
    }

    private fun logRootfsHealthResult(healthState: DependencyRootfsHealthUiState) {
        val statusText = healthState.statusText.ifBlank {
            Strings.workspace_linux_health_unknown.strOr(applicationContext)
        }
        val message = if (healthState.detailText.isBlank()) {
            Strings.workspace_linux_health_log_result.strOr(applicationContext, statusText)
        } else {
            Strings.workspace_linux_health_log_result_with_detail.strOr(
                applicationContext,
                statusText,
                healthState.detailText,
            )
        }

        when (healthState.status) {
            DependencyRootfsHealthStatus.READY -> installLogManager.success(message)
            DependencyRootfsHealthStatus.ATTENTION -> installLogManager.warning(message)
            DependencyRootfsHealthStatus.UNAVAILABLE -> installLogManager.error(message)
            DependencyRootfsHealthStatus.CHECKING,
            DependencyRootfsHealthStatus.UNKNOWN -> installLogManager.info(message)
        }
    }

    private fun LinuxDistroRootfsHealthReport.toDependencyRootfsHealthUiState(): DependencyRootfsHealthUiState {
        val summary = toHealthSummary { probe -> probe.toDisplayName() }
        val status = when (summary.level) {
            LinuxDistroRootfsHealthLevel.READY -> DependencyRootfsHealthStatus.READY
            LinuxDistroRootfsHealthLevel.ATTENTION -> DependencyRootfsHealthStatus.ATTENTION
            LinuxDistroRootfsHealthLevel.UNAVAILABLE -> DependencyRootfsHealthStatus.UNAVAILABLE
        }
        val statusText = when (status) {
            DependencyRootfsHealthStatus.READY -> Strings.workspace_linux_health_ready.strOr(applicationContext)
            DependencyRootfsHealthStatus.ATTENTION -> Strings.workspace_linux_health_attention.strOr(applicationContext)
            DependencyRootfsHealthStatus.UNAVAILABLE -> Strings.workspace_linux_health_unavailable.strOr(applicationContext)
            DependencyRootfsHealthStatus.CHECKING -> Strings.workspace_linux_health_checking.strOr(applicationContext)
            DependencyRootfsHealthStatus.UNKNOWN -> Strings.workspace_linux_health_unknown.strOr(applicationContext)
        }
        val detailText = when {
            summary.requiredMissingItems.isNotEmpty() -> Strings.workspace_linux_health_missing_required.strOr(
                applicationContext,
                summary.requiredMissingItems.joinToString(),
            )
            summary.optionalMissingItems.isNotEmpty() -> Strings.workspace_linux_health_missing_optional.strOr(
                applicationContext,
                summary.optionalMissingItems.joinToString(),
            )
            summary.identity.isNotBlank() -> summary.identity
            else -> ""
        }

        return DependencyRootfsHealthUiState(
            status = status,
            statusText = statusText,
            detailText = detailText,
        )
    }

    private fun LinuxDistroRootfsHealthProbe.toDisplayName(): String = when (this) {
        LinuxDistroRootfsHealthProbe.ROOTFS_AVAILABLE ->
            Strings.linux_distro_health_probe_rootfs_available.strOr(applicationContext)
        LinuxDistroRootfsHealthProbe.PACKAGE_MANAGER_COMMANDS ->
            Strings.linux_distro_health_probe_package_manager_commands.strOr(applicationContext)
        LinuxDistroRootfsHealthProbe.PACKAGE_MANAGER_VERSION ->
            Strings.linux_distro_health_probe_package_manager_version.strOr(applicationContext)
        LinuxDistroRootfsHealthProbe.REQUIRED_BOOTSTRAP_COMMANDS ->
            Strings.linux_distro_health_probe_required_commands.strOr(applicationContext)
        LinuxDistroRootfsHealthProbe.OPTIONAL_BOOTSTRAP_COMMANDS ->
            Strings.linux_distro_health_probe_optional_commands.strOr(applicationContext)
        LinuxDistroRootfsHealthProbe.ARCHITECTURE ->
            Strings.linux_distro_health_probe_architecture.strOr(applicationContext)
        LinuxDistroRootfsHealthProbe.OS_RELEASE ->
            Strings.linux_distro_health_probe_os_release.strOr(applicationContext)
    }

    private suspend fun needsToolchainInstall(): Boolean =
        runCatching { !isToolchainReady() }.getOrDefault(true)

    // 工具链就绪检测会启动 PRoot 进程探测命令（耗时 IO），必须在 IO 调度器执行，
    // 禁止用 runBlocking 在主线程同步等待，否则可能 ANR。
    private suspend fun isToolchainReady(): Boolean = withContext(Dispatchers.IO) {
        val guestToolchainReady = if (installLinuxEnvironment) {
            runCatching {
                guestToolchainInstaller.isInstalled(toolchainConfig)
            }.getOrDefault(false)
        } else {
            true
        }

        val sysrootReady = runCatching {
            AndroidSysrootManager(applicationContext).isInstalled()
        }.getOrDefault(false)

        val toolchainReady = runCatching {
            val manager = AndroidNativeToolchainManager(applicationContext)
            manager.isReadyForCurrentAssets()
        }.getOrDefault(false)

        guestToolchainReady && sysrootReady && toolchainReady
    }

    private fun mergePackageList(
        state: DependencyInstallUiState,
        rootfsPackages: List<PRootBootstrap.PackageInfo>
    ): List<PRootBootstrap.PackageInfo> {
        val map = state.packageList.associateBy { it.name }.toMutableMap()
        rootfsPackages.forEach { pkg ->
            map[pkg.name] = pkg.copy(displayName = packageDisplayName(pkg.name))
        }

        packageOrder.forEach { packageName ->
            if (map[packageName] == null) {
                map[packageName] = defaultPackageInfo(packageName, state.envReady)
            } else {
                map[packageName] = map[packageName]!!.copy(displayName = packageDisplayName(packageName))
            }
        }

        return packageOrder.map { map.getValue(it) }
    }

    private fun setPackageStatus(packageName: String, status: PRootBootstrap.PackageStatus) {
        _uiState.update { state ->
            val map = state.packageList.associateBy { it.name }.toMutableMap()
            val base = map[packageName] ?: defaultPackageInfo(packageName, state.envReady)
            map[packageName] = base.copy(
                displayName = packageDisplayName(packageName),
                status = status
            )
            state.copy(packageList = packageOrder.map { map[it] ?: defaultPackageInfo(it, state.envReady) })
        }
    }

    private fun buildInitialPackageList(
        envReady: Boolean,
        toolchainReady: Boolean
    ): List<PRootBootstrap.PackageInfo> = packageOrder.map { packageName ->
        val status = when (packageName) {
            PACKAGE_LINUX_DISTRO_RUNTIME, PACKAGE_LINUX_ROOTFS, PACKAGE_PROOT_GUEST_TOOLCHAIN -> {
                if (envReady) PRootBootstrap.PackageStatus.COMPLETED else PRootBootstrap.PackageStatus.PENDING
            }
            else -> if (toolchainReady) PRootBootstrap.PackageStatus.COMPLETED else PRootBootstrap.PackageStatus.PENDING
        }
        PRootBootstrap.PackageInfo(
            name = packageName,
            displayName = packageDisplayName(packageName),
            status = status
        )
    }

    private fun defaultPackageInfo(
        packageName: String,
        envReady: Boolean
    ): PRootBootstrap.PackageInfo {
        val status = when (packageName) {
            PACKAGE_LINUX_DISTRO_RUNTIME, PACKAGE_LINUX_ROOTFS, PACKAGE_PROOT_GUEST_TOOLCHAIN -> {
                if (envReady) PRootBootstrap.PackageStatus.COMPLETED else PRootBootstrap.PackageStatus.PENDING
            }
            else -> {
                if (toolchainInstallCompleted) PRootBootstrap.PackageStatus.COMPLETED else PRootBootstrap.PackageStatus.PENDING
            }
        }
        return PRootBootstrap.PackageInfo(
            name = packageName,
            displayName = packageDisplayName(packageName),
            status = status
        )
    }

    private fun packageDisplayName(packageName: String): String = when (packageName) {
        PACKAGE_LINUX_DISTRO_RUNTIME -> Strings.linux_distro_package_runtime.strOr(applicationContext)
        PACKAGE_LINUX_ROOTFS -> Strings.linux_package_rootfs.strOr(applicationContext)
        PACKAGE_PROOT_GUEST_TOOLCHAIN -> Strings.proot_package_guest_toolchain.strOr(applicationContext)
        PACKAGE_ANDROID_SYSROOT -> Strings.proot_package_android_sysroot.strOr(applicationContext)
        PACKAGE_NATIVE_TOOLCHAIN -> Strings.proot_package_native_toolchain.strOr(applicationContext)
        else -> packageName
    }
}
