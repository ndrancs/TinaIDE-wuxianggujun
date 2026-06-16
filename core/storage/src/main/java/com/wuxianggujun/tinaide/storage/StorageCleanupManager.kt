package com.wuxianggujun.tinaide.storage

import android.content.Context
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber

/**
 * 存储空间清理管理器。
 *
 * 安全护栏（硬约束）：
 * - 只接受 [CleanupCategory] 枚举，不暴露任意 File 入参
 * - 所有目标路径必须由 [ProjectPaths] 的 getter 返回
 * - 只删目录内的内容，保留目录本身
 * - 绝不触及 projects/、config.json、fonts/ 等用户数据
 */
class StorageCleanupManager(private val context: Context) {

    companion object {
        private const val TAG = "StorageCleanupManager"
    }

    data class CategorySize(
        val category: CleanupCategory,
        val bytes: Long
    )

    data class CleanupResult(
        val category: CleanupCategory,
        val success: Boolean,
        val deletedBytes: Long,
        val failedPaths: List<String> = emptyList()
    )

    /**
     * 清理树的一个节点。可表示文件或目录。
     *
     * - 顶层项(`isTopLevel=true`):一个类别下的可视入口(BUILD 是项目的 build 目录,
     *   其它类别是 cache/log 根目录下的直接子项)
     * - 子项(`isTopLevel=false`):顶层项目录下的直接子文件/子目录(只展开一层)
     */
    data class CleanupNode(
        val displayName: String,
        val absolutePath: String,
        val bytes: Long,
        val isDirectory: Boolean,
        val isTopLevel: Boolean = true
    )

    /** 扫描指定类别的占用字节数。 */
    suspend fun scanSize(category: CleanupCategory): Long = withContext(Dispatchers.IO) {
        resolveDirs(category).sumOf { StorageCleanupSupport.computeSize(it) }
    }

    /** 批量扫描全部类别。 */
    suspend fun scanAll(): List<CategorySize> = withContext(Dispatchers.IO) {
        CleanupCategory.entries.map { category ->
            val bytes = resolveDirs(category).sumOf { StorageCleanupSupport.computeSize(it) }
            CategorySize(category, bytes)
        }
    }

    /** 清理指定类别。 */
    suspend fun clean(category: CleanupCategory): CleanupResult = withContext(Dispatchers.IO) {
        var deleted = 0L
        val failed = mutableListOf<String>()
        resolveDirs(category).forEach { dir ->
            val result = StorageCleanupSupport.deleteContents(dir)
            deleted += result.deletedBytes
            failed += result.failedPaths
        }
        val success = failed.isEmpty()
        if (success) {
            Timber.tag(TAG).i("Cleanup[%s] done: %d bytes freed", category.name, deleted)
        } else {
            Timber.tag(TAG).w(
                "Cleanup[%s] partial: %d bytes freed, %d failed paths",
                category.name,
                deleted,
                failed.size
            )
        }
        CleanupResult(category, success, deleted, failed)
    }

    /** 一键清理全部类别。 */
    suspend fun cleanAll(): List<CleanupResult> = withContext(Dispatchers.IO) {
        CleanupCategory.entries.map { clean(it) }
    }

    // ============ 详情清单与按路径清理 ============

    /**
     * 列出指定类别的顶层项(只展开一层)。
     *
     * - BUILD_INTERMEDIATES:每个项目对应一个 build/ 目录(显示名 = projectId)
     * - 其它类别:对应根目录下的直接子项(文件或子目录)
     */
    suspend fun scanItems(category: CleanupCategory): List<CleanupNode> = withContext(Dispatchers.IO) {
        when (category) {
            CleanupCategory.BUILD_INTERMEDIATES -> {
                val workspaceRoot = ProjectPaths.getWorkspaceRoot(context)
                if (!workspaceRoot.exists() || !workspaceRoot.isDirectory) {
                    emptyList()
                } else {
                    workspaceRoot.listFiles()
                        ?.filter { it.isDirectory }
                        ?.mapNotNull { projectDir ->
                            val buildDir = ProjectPaths.getProjectBuildDir(projectDir)
                            if (!buildDir.exists() || !buildDir.isDirectory) return@mapNotNull null
                            CleanupNode(
                                displayName = projectDir.name,
                                absolutePath = buildDir.absolutePath,
                                bytes = StorageCleanupSupport.computeSize(buildDir),
                                isDirectory = true,
                                isTopLevel = true,
                            )
                        }
                        ?: emptyList()
                }
            }

            else -> resolveDirs(category).flatMap { root ->
                if (!root.exists() || !root.isDirectory) {
                    emptyList()
                } else {
                    root.listFiles()?.map { entry ->
                        CleanupNode(
                            displayName = entry.name,
                            absolutePath = entry.absolutePath,
                            bytes = StorageCleanupSupport.computeSize(entry),
                            isDirectory = entry.isDirectory,
                            isTopLevel = true,
                        )
                    } ?: emptyList()
                }
            }
        }
    }

    /** 列出某顶层项目录下的直接子项(只展开一层)。 */
    suspend fun scanChildren(parent: CleanupNode): List<CleanupNode> = withContext(Dispatchers.IO) {
        if (!parent.isDirectory) return@withContext emptyList()
        val dir = File(parent.absolutePath)
        if (!dir.exists() || !dir.isDirectory) return@withContext emptyList()
        dir.listFiles()?.map { entry ->
            CleanupNode(
                displayName = entry.name,
                absolutePath = entry.absolutePath,
                bytes = StorageCleanupSupport.computeSize(entry),
                isDirectory = entry.isDirectory,
                isTopLevel = false,
            )
        } ?: emptyList()
    }

    /**
     * 按路径集合清理。每个路径必须落在 [category] 的允许范围内,否则被拒绝并计入失败。
     *
     * 调用方传过来的路径会取规范化路径(canonicalPath)再做白名单校验,防止 `..` 越权。
     */
    suspend fun cleanPaths(
        category: CleanupCategory,
        paths: Set<String>
    ): CleanupResult = withContext(Dispatchers.IO) {
        val allowedRoots = resolveDirs(category)
        var deleted = 0L
        val failed = mutableListOf<String>()
        paths.forEach { pathStr ->
            val target = File(pathStr)
            if (!StorageCleanupSupport.isPathUnderAnyRoot(target, allowedRoots)) {
                Timber.tag(TAG).w("Refused to clean unauthorized path: %s", pathStr)
                failed += pathStr
                return@forEach
            }
            if (!target.exists()) return@forEach
            val size = StorageCleanupSupport.computeSize(target)
            if (StorageCleanupSupport.deleteRecursivelySafely(target)) {
                deleted += size
            } else {
                failed += pathStr
            }
        }
        val success = failed.isEmpty()
        if (success) {
            Timber.tag(TAG).i(
                "CleanPaths[%s] freed %d bytes (%d items)",
                category.name,
                deleted,
                paths.size
            )
        } else {
            Timber.tag(TAG).w(
                "CleanPaths[%s] freed %d bytes, %d failed",
                category.name,
                deleted,
                failed.size
            )
        }
        CleanupResult(category, success, deleted, failed)
    }

    private fun resolveDirs(category: CleanupCategory): List<File> = when (category) {
        CleanupCategory.BUILD_INTERMEDIATES -> {
            val workspaceRoot = ProjectPaths.getWorkspaceRoot(context)
            if (!workspaceRoot.exists() || !workspaceRoot.isDirectory) {
                emptyList()
            } else {
                workspaceRoot.listFiles()
                    ?.filter { it.isDirectory }
                    ?.map { ProjectPaths.getProjectBuildDir(it) }
                    ?.filter { it.exists() }
                    ?: emptyList()
            }
        }

        CleanupCategory.PROOT_CACHE ->
            listOf(ProjectPaths.getPRootTmpRoot(context))

        CleanupCategory.DOWNLOAD_CACHE ->
            listOf(ProjectPaths.getDownloadCacheRoot(context))

        CleanupCategory.EXPORT_CACHE ->
            listOf(ProjectPaths.getExportCacheRoot(context))

        CleanupCategory.APP_LOGS -> listOf(
            ProjectPaths.getLogsRoot(context),
            ProjectPaths.getPRootLogsRoot(context)
        )

        CleanupCategory.INSTALL_LOGS ->
            listOf(ProjectPaths.getInstallLogsRoot(context))
    }
}

/**
 * 可清理的类别。id 用于 UI 列表的稳定 key，也可作为未来埋点标识。
 */
enum class CleanupCategory(val id: String) {
    BUILD_INTERMEDIATES("build_intermediates"),
    PROOT_CACHE("proot_cache"),
    DOWNLOAD_CACHE("download_cache"),
    EXPORT_CACHE("export_cache"),
    APP_LOGS("app_logs"),
    INSTALL_LOGS("install_logs");

    companion object {
        fun fromId(id: String): CleanupCategory? = entries.firstOrNull { it.id == id }
    }
}
