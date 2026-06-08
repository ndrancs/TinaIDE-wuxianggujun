package com.wuxianggujun.tinaide.storage

import android.Manifest
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import com.wuxianggujun.tinaide.core.ServiceLifecycle
import com.wuxianggujun.tinaide.core.i18n.Strings
import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import timber.log.Timber

/**
 * 存储管理器
 *
 * 职责：
 * - 管理公有源码目录和私有目录路径
 * - 权限检查与请求
 * - 存储空间监控
 */
class StorageManager(private val context: Context) : ServiceLifecycle {

    data class ProjectDirAccessResult(
        val canAccess: Boolean,
        val failureMessageResId: Int? = null
    )

    companion object {
        private const val TAG = "StorageManager"
    }

    private val _permissionStatus = MutableStateFlow(computePermissionStatus())

    /**
     * 权限状态响应式观察入口。
     *
     * 注意：Android 11+ 的 `MANAGE_EXTERNAL_STORAGE` 由系统特殊权限管理，应用在设置页
     * 授权 / 撤销后不会主动通知；调用方需要在合适时机（`ON_RESUME`、launcher 回调等）
     * 主动调用 [refreshPermissionStatus] 触发刷新。
     */
    val permissionStatus: StateFlow<PermissionStatus> = _permissionStatus.asStateFlow()

    override fun onCreate() {
        Timber.tag(TAG).d("StorageManager initialized")
        ensurePrivateDirectories()
    }

    override fun onDestroy() {
        Timber.tag(TAG).d("StorageManager destroyed")
    }

    // ============ 公有源码目录管理 ============

    /**
     * 获取公有项目根目录。
     *
     * 默认路径：Documents/TinaIDE（统一走 [ProjectPaths.getPublicProjectsRoot]）
     *
     * @return 公有项目目录，如果权限不足返回 null
     */
    fun getPublicProjectsDir(): File? {
        if (!hasExternalStoragePermission()) {
            Timber.tag(TAG).w("No external storage permission")
            return null
        }
        return ProjectPaths.getPublicProjectsRoot(context).apply {
            if (!exists()) mkdirs()
        }
    }

    // ============ 私有存储管理 ============

    /**
     * 获取同步元数据目录
     * 路径：/data/data/com.wuxianggujun.tinaIDE/files/sync-meta/
     */
    fun getSyncMetaDir(): File = ProjectPaths.getSyncMetaRoot(context)

    /**
     * 获取终端状态存储目录
     * 路径：/sdcard/Android/data/com.wuxianggujun.tinaide/files/terminal_states/
     *
     * 用于存储终端会话状态（按项目隔离）
     */
    fun getTerminalStatesDir(): File = ProjectPaths.getTerminalStatesRoot(context)

    /**
     * 确保私有目录存在
     */
    private fun ensurePrivateDirectories() {
        val metaDir = getSyncMetaDir()

        if (!metaDir.exists()) {
            metaDir.mkdirs()
            Timber.tag(TAG).d("Created sync meta directory: %s", metaDir.absolutePath)
        }
    }

    // ============ 权限管理 ============

    /**
     * 检查外部存储权限状态（直接读取系统 API，不走缓存）。
     *
     * 响应式订阅请走 [permissionStatus]；UI 请求权限后主动 [refreshPermissionStatus] 更新。
     */
    fun checkExternalStoragePermission(): PermissionStatus = computePermissionStatus()

    /**
     * 重新读取系统权限状态并更新 [permissionStatus] StateFlow。
     *
     * 何时调用：
     * - 从"所有文件访问"设置页返回后（launcher 回调里）
     * - Activity `onResume` 时（用户可能在后台改了设置）
     */
    fun refreshPermissionStatus(): PermissionStatus {
        val status = computePermissionStatus()
        _permissionStatus.value = status
        return status
    }

    private fun computePermissionStatus(): PermissionStatus = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        // Android 11+: 检查 MANAGE_EXTERNAL_STORAGE
        if (Environment.isExternalStorageManager()) {
            PermissionStatus.GRANTED
        } else {
            PermissionStatus.DENIED
        }
    } else {
        // Android 6-10: 检查 READ/WRITE_EXTERNAL_STORAGE
        val hasRead = context.checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED
        val hasWrite = context.checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED

        if (hasRead && hasWrite) {
            PermissionStatus.GRANTED
        } else {
            PermissionStatus.DENIED
        }
    }

    /**
     * 是否有外部存储权限
     */
    fun hasExternalStoragePermission(): Boolean = checkExternalStoragePermission() == PermissionStatus.GRANTED

    /**
     * 判断项目目录当前是否可通过 java.io.File 正常访问。
     *
     * 私有源码目录始终可访问；其余目录依赖外部存储权限。
     */
    fun canAccessProjectDir(dir: File): Boolean = checkProjectDirAccess(dir).canAccess

    /**
     * 检查项目目录是否真正可打开。
     *
     * 除了权限，还会验证目录是否存在、是否可读，以及是否能列出顶层内容。
     * 这样可以避免把“权限已丢失/目录不可读”的项目误判为可打开项目。
     */
    fun checkProjectDirAccess(dir: File): ProjectDirAccessResult {
        if (!dir.exists() || !dir.isDirectory) {
            return ProjectDirAccessResult(
                canAccess = false,
                failureMessageResId = Strings.export_dir_not_exist
            )
        }

        val isPrivateProject = ProjectPaths.isUnderPrivateProjectsRoot(context, dir)
        if (!isPrivateProject && !hasExternalStoragePermission()) {
            return ProjectDirAccessResult(
                canAccess = false,
                failureMessageResId = Strings.permission_storage_settings
            )
        }

        val canReadDir = runCatching { dir.canRead() }.getOrDefault(false)
        val canListDir = runCatching { dir.listFiles() != null }.getOrDefault(false)
        if (canReadDir && canListDir) {
            return ProjectDirAccessResult(canAccess = true)
        }

        val failureMessageResId = if (isPrivateProject) {
            Strings.compile_error_cannot_read_dir
        } else {
            Strings.permission_storage_settings
        }
        return ProjectDirAccessResult(
            canAccess = false,
            failureMessageResId = failureMessageResId
        )
    }

    /**
     * 创建请求外部存储权限的 Intent
     *
     * Android 11+: 跳转到"所有文件访问权限"设置页
     * Android 6-10: 返回 null，由调用方使用 ActivityCompat.requestPermissions
     */
    fun createPermissionRequestIntent(): Intent? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
            data = Uri.parse("package:${context.packageName}")
        }
    } else {
        null // 使用传统运行时权限
    }

    /**
     * 计算下一步权限申请动作。
     *
     * 调用方（Composable / Activity）根据返回值驱动 launcher，避免各处重复判断分支。
     */
    fun nextPermissionRequest(): StoragePermissionRequest {
        if (hasExternalStoragePermission()) return StoragePermissionRequest.AlreadyGranted
        val intent = createPermissionRequestIntent()
        if (intent != null) return StoragePermissionRequest.OpenSettings(intent)
        return StoragePermissionRequest.RequestRuntime(
            arrayOf(
                Manifest.permission.READ_EXTERNAL_STORAGE,
                Manifest.permission.WRITE_EXTERNAL_STORAGE
            )
        )
    }

    // ============ 存储空间管理 ============

    /**
     * 获取指定位置的可用空间（字节）
     */
    fun getAvailableSpace(location: StorageLocation): Long {
        val dir = when (location) {
            StorageLocation.PUBLIC_SOURCE -> getPublicProjectsDir()
            StorageLocation.PRIVATE_META -> getSyncMetaDir()
        }

        return dir?.usableSpace ?: 0L
    }

    /**
     * 获取指定位置的总空间（字节）
     */
    fun getTotalSpace(location: StorageLocation): Long {
        val dir = when (location) {
            StorageLocation.PUBLIC_SOURCE -> getPublicProjectsDir()
            StorageLocation.PRIVATE_META -> getSyncMetaDir()
        }

        return dir?.totalSpace ?: 0L
    }

    /**
     * 格式化文件大小
     */
    fun formatSize(bytes: Long): String = when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> String.format("%.2f KB", bytes / 1024.0)
        bytes < 1024 * 1024 * 1024 -> String.format("%.2f MB", bytes / (1024.0 * 1024))
        else -> String.format("%.2f GB", bytes / (1024.0 * 1024 * 1024))
    }
}

/**
 * 存储位置枚举
 */
enum class StorageLocation {
    PUBLIC_SOURCE, // 公有源码目录（Documents/TinaIDE）
    PRIVATE_META // 私有同步元数据
}

/**
 * 权限状态
 */
enum class PermissionStatus {
    GRANTED, // 已授权
    DENIED // 未授权
}
