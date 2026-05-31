package com.wuxianggujun.tinaide.ui.workspace.model

import com.wuxianggujun.tinaide.core.proot.PRootBootstrap

/**
 * 安装阶段
 */
enum class InstallPhase {
    INSTALLING,   // 安装中
    COMPLETED,    // 安装完成
    FAILED        // 安装失败
}

/**
 * 已安装组件数据
 */
data class InstalledComponent(
    val name: String,
    val version: String,
    val iconRes: Int
)

/**
 * 环境配置项数据
 */
data class EnvironmentConfigItem(
    val iconType: ConfigIconType,
    val title: String,
    val subtitle: String
)

/**
 * 配置项图标类型
 */
enum class ConfigIconType {
    CODE,      // <> 代码图标
    FOLDER,    // 文件夹图标
    SETTINGS   // 设置图标
}

/**
 * Linux rootfs 自检状态。
 */
enum class DependencyRootfsHealthStatus {
    UNKNOWN,
    CHECKING,
    READY,
    ATTENTION,
    UNAVAILABLE,
}

/**
 * Linux rootfs 自检展示数据。
 */
data class DependencyRootfsHealthUiState(
    val status: DependencyRootfsHealthStatus = DependencyRootfsHealthStatus.UNKNOWN,
    val statusText: String = "",
    val detailText: String = "",
)

/**
 * 依赖安装 UI 状态
 */
data class DependencyInstallUiState(
    val installPhase: InstallPhase = InstallPhase.INSTALLING,
    val progress: Float = 0f,
    val statusMessage: String = "",
    val installStage: PRootBootstrap.InstallStage = PRootBootstrap.InstallStage.INSTALLING_DISTRO,
    val packageList: List<PRootBootstrap.PackageInfo> = emptyList(),
    val currentPackage: String? = null,
    val failedMessage: String = "",
    val isNetworkRelated: Boolean = false,
    val isPaused: Boolean = false,
    val envReady: Boolean = false,
    val rootfsHealth: DependencyRootfsHealthUiState = DependencyRootfsHealthUiState(),
)

/**
 * 依赖安装事件（一次性事件）
 */
sealed class DependencyInstallEvent {
    data object NavigateToProjectManager : DependencyInstallEvent()
    data class ShowToast(val message: String) : DependencyInstallEvent()
    data object InstallCompleted : DependencyInstallEvent()
}
