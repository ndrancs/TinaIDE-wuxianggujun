package com.wuxianggujun.tinaide.storage

import android.content.Context
import com.wuxianggujun.tinaide.core.ServiceLifecycle
import com.wuxianggujun.tinaide.project.ProjectMetadataStore
import com.wuxianggujun.tinaide.storage.db.ProjectLocationEntity
import com.wuxianggujun.tinaide.storage.db.StorageDatabase
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber

/**
 * 项目位置管理器
 *
 * 职责：
 * - 管理项目的源码路径与项目构建目录
 * - 持久化项目路径配置（使用 Room 数据库）
 */
class ProjectLocationManager(
    private val context: Context,
    private val scope: CoroutineScope
) : ServiceLifecycle {

    companion object {
        private const val TAG = "ProjectLocationManager"
        private const val LEGACY_PRIVATE_PROJECTS_MIGRATION_MARKER =
            "storage-migrations/private-projects-v1.done"
    }

    private val database = StorageDatabase.getInstance(context)
    private val locationDao = database.projectLocationDao()

    // 项目路径映射缓存
    // 读：ConcurrentHashMap 提供无锁安全读 / 安全迭代（getAllProjects 等可在任意线程调用）。
    // 写：所有“复合读改写”（registerProject / unregisterProject / 加载合并）都走 cacheLock，
    //     保证两个 map 之间以及 read-modify-write 的整体原子性。
    //     init 协程的注册与外部 openProject 可能并发，仅靠 ConcurrentHashMap 不足以保证一致。
    private val cacheLock = Any()
    private val projectMappingsById = ConcurrentHashMap<String, ProjectLocation>()
    private val projectIdBySourceRootPath = ConcurrentHashMap<String, String>()

    override fun onCreate() {
        Timber.tag(TAG).d("ProjectLocationManager initialized")
        // 不在装配线程（可能是主线程）同步读数据库。
        // 整个初始化序列放进 IO 协程，内部保持原有串行顺序：
        // 先加载映射，再迁移遗留项目、注册私有项目（后两者依赖映射已就绪）。
        scope.launch(Dispatchers.IO) {
            loadProjectMappings()
            migrateLegacyPrivateProjectsIfNeeded()
            registerProjectsFromPrivateRoot()
        }
    }

    override fun onDestroy() {
        Timber.tag(TAG).d("ProjectLocationManager destroyed")
        // 缓存已经实时同步到数据库，无需额外保存
    }

    fun getProjectLocation(projectId: String): ProjectLocation? = projectMappingsById[projectId]

    fun registerProject(sourceDir: File): ProjectLocation {
        require(sourceDir.exists() && sourceDir.isDirectory) {
            "Invalid project source dir: ${sourceDir.absolutePath}"
        }

        val normalizedSourceDir = normalizePath(sourceDir)
        val metadata = ProjectMetadataStore.ensure(sourceDir, displayNameFallback = sourceDir.name)
        val projectId = metadata.id
        val projectDirName = sourceDir.name

        // 锁内完成“读 existing → 计算 location → 改两个 map”的复合操作，保证原子性。
        // 文件 IO（ensure）与异步落库（saveProjectMapping）都放在锁外，避免锁内做慢操作。
        val (existing, location) = synchronized(cacheLock) {
            val previous = projectMappingsById[projectId]
            val resolved = when {
                previous == null -> ProjectLocation(
                    projectId = projectId,
                    projectDirName = projectDirName,
                    sourceRootPath = normalizedSourceDir,
                    registered = System.currentTimeMillis()
                )
                previous.projectDirName != projectDirName || previous.sourceRootPath != normalizedSourceDir ->
                    previous.copy(
                        projectDirName = projectDirName,
                        sourceRootPath = normalizedSourceDir
                    )
                else -> previous
            }

            if (previous != null && previous.sourceRootPath != normalizedSourceDir) {
                projectIdBySourceRootPath.remove(previous.sourceRootPath)
            }
            projectMappingsById[projectId] = resolved
            projectIdBySourceRootPath[normalizedSourceDir] = projectId
            previous to resolved
        }

        if (existing != location) {
            saveProjectMapping(location)
            Timber.tag(TAG).i("Registered project: %s (%s)", projectDirName, projectId)
            Timber.tag(TAG).d("  Source path: %s", normalizedSourceDir)
            Timber.tag(TAG).d("  Workspace path: %s", getWorkspaceDir(projectId).absolutePath)
            Timber.tag(TAG).d("  Build path: %s", getBuildDir(projectId).absolutePath)
        }

        return location
    }

    fun getAllProjects(): List<ProjectLocation> = projectMappingsById.values.toList()

    fun getSourceDir(projectId: String): File? = projectMappingsById[projectId]?.let { File(it.sourceRootPath) }

    fun getWorkspaceDir(projectId: String): File {
        require(projectMappingsById.containsKey(projectId)) {
            "Project not registered: $projectId"
        }
        return ProjectPaths.getProjectWorkspaceDir(context, projectId).apply { mkdirs() }
    }

    fun getBuildDir(projectId: String): File = ProjectPaths.getProjectBuildDir(getWorkspaceDir(projectId)).apply { mkdirs() }

    fun unregisterProject(projectId: String, deleteWorkspace: Boolean = false): Boolean {
        // 锁内移除两个 map 的对应条目，与 registerProject 互斥。
        val location = synchronized(cacheLock) {
            val removed = projectMappingsById.remove(projectId) ?: return false
            projectIdBySourceRootPath.remove(removed.sourceRootPath)
            removed
        }

        if (deleteWorkspace) {
            val workspaceDir = ProjectPaths.getProjectWorkspaceDir(context, projectId)
            if (workspaceDir.exists()) {
                workspaceDir.deleteRecursively()
                Timber.tag(TAG).i("Deleted workspace dir for project: %s", location.projectDirName)
            }
        }

        deleteProjectMapping(projectId)
        Timber.tag(TAG).i("Unregistered project: %s (%s)", location.projectDirName, projectId)
        return true
    }

    private suspend fun loadProjectMappings() = withContext(Dispatchers.IO) {
        try {
            val entities = locationDao.getAllLocations()

            // 先在锁外完成每条记录的规整与文件 IO（修正遗留 sourceRootPath、ensure 元数据）。
            val loaded = entities.map { entity ->
                var location = entity.toDomainModel()
                if (location.sourceRootPath.isBlank() || isLegacyPendingSourceRoot(location.sourceRootPath)) {
                    val fallbackDir = ProjectPaths.getPrivateProjectDir(context, location.projectDirName)
                    location = location.copy(sourceRootPath = normalizePath(fallbackDir))
                    saveProjectMapping(location)
                }

                val sourceDir = File(location.sourceRootPath)
                if (sourceDir.exists() && sourceDir.isDirectory) {
                    ProjectMetadataStore.ensure(sourceDir, displayNameFallback = location.projectDirName)
                }
                location
            }

            // 锁内合并：不再 clear。加载是在 IO 协程里异步进行的，期间外部可能已通过
            // openProject/restoreLastSession 注册了项目，那些条目比数据库快照更新鲜。
            // 因此用 putIfAbsent 语义：仅补齐数据库里有、而缓存中尚无的项目，已存在则跳过。
            synchronized(cacheLock) {
                loaded.forEach { location ->
                    if (projectMappingsById.putIfAbsent(location.projectId, location) == null) {
                        projectIdBySourceRootPath[location.sourceRootPath] = location.projectId
                    }
                }
            }

            Timber.tag(TAG).i("Loaded %d project mappings from database", projectMappingsById.size)
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Failed to load project mappings")
        }
    }

    private fun saveProjectMapping(location: ProjectLocation) {
        scope.launch(Dispatchers.IO) {
            try {
                val entity = ProjectLocationEntity.fromDomainModel(location)
                locationDao.insertLocation(entity)
                Timber.tag(TAG).d("Saved project mapping: %s", location.projectId)
            } catch (e: Exception) {
                Timber.tag(TAG).e(e, "Failed to save project mapping")
            }
        }
    }

    private fun deleteProjectMapping(projectId: String) {
        scope.launch(Dispatchers.IO) {
            try {
                locationDao.deleteLocation(projectId)
                Timber.tag(TAG).d("Deleted project mapping: %s", projectId)
            } catch (e: Exception) {
                Timber.tag(TAG).e(e, "Failed to delete project mapping")
            }
        }
    }

    fun findProjectByPath(projectPath: String): ProjectLocation? = projectIdBySourceRootPath[normalizePath(projectPath)]
        ?.let(projectMappingsById::get)

    private fun migrateLegacyPrivateProjectsIfNeeded() {
        val markerFile = File(context.filesDir, LEGACY_PRIVATE_PROJECTS_MIGRATION_MARKER)
        if (markerFile.exists()) {
            return
        }

        val legacyRoot = ProjectPaths.getWorkspaceRoot(context)
        val privateProjectsRoot = ProjectPaths.getPrivateProjectsRoot(context).apply { mkdirs() }
        if (!legacyRoot.exists() || !legacyRoot.isDirectory) {
            writeMigrationMarker(markerFile)
            return
        }

        var failed = false
        legacyRoot.listFiles()
            ?.asSequence()
            ?.filter(File::isDirectory)
            ?.forEach { legacyDir ->
                val targetDir = resolveLegacyProjectTarget(privateProjectsRoot, legacyDir)
                val moved = moveLegacyProjectDir(legacyDir, targetDir)
                if (!moved) {
                    failed = true
                    return@forEach
                }

                runCatching { registerProject(targetDir) }
                    .onFailure {
                        failed = true
                        Timber.tag(TAG).e(it, "Failed to register migrated legacy project: %s", targetDir.absolutePath)
                    }
            }

        if (!failed) {
            writeMigrationMarker(markerFile)
        }
    }

    private fun registerProjectsFromPrivateRoot() {
        val privateProjectsRoot = ProjectPaths.getPrivateProjectsRoot(context).apply { mkdirs() }
        privateProjectsRoot.listFiles()
            ?.asSequence()
            ?.filter(File::isDirectory)
            ?.forEach { dir ->
                runCatching { registerProject(dir) }
                    .onFailure { Timber.tag(TAG).e(it, "Failed to register private project: %s", dir.absolutePath) }
            }
    }

    private fun resolveLegacyProjectTarget(privateProjectsRoot: File, legacyDir: File): File {
        val preferredTarget = File(privateProjectsRoot, legacyDir.name)
        if (!preferredTarget.exists()) {
            return preferredTarget
        }

        val legacyProjectId = ProjectMetadataStore.read(legacyDir)?.id
        if (!legacyProjectId.isNullOrBlank() && ProjectMetadataStore.read(preferredTarget)?.id == legacyProjectId) {
            return preferredTarget
        }

        return buildUniqueTargetDir(privateProjectsRoot, legacyDir.name)
    }

    private fun buildUniqueTargetDir(root: File, baseName: String): File {
        var index = 1
        while (true) {
            val candidate = File(root, "$baseName-$index")
            if (!candidate.exists()) {
                return candidate
            }
            index++
        }
    }

    private fun moveLegacyProjectDir(source: File, target: File): Boolean {
        if (source.canonicalOrAbsolutePath() == target.canonicalOrAbsolutePath()) {
            return true
        }

        val sourceProjectId = ProjectMetadataStore.read(source)?.id
        val targetProjectId = target.takeIf(File::exists)?.let(ProjectMetadataStore::read)?.id
        if (!sourceProjectId.isNullOrBlank() && sourceProjectId == targetProjectId) {
            if (!source.deleteRecursively()) {
                Timber.tag(TAG).w("Failed to delete duplicate legacy project dir: %s", source.absolutePath)
            }
            Timber.tag(TAG).i("Skipped duplicate legacy project dir: %s", source.absolutePath)
            return true
        }

        target.parentFile?.mkdirs()
        if (source.renameTo(target)) {
            Timber.tag(TAG).i("Migrated legacy private project: %s -> %s", source.absolutePath, target.absolutePath)
            return true
        }

        return runCatching {
            source.copyRecursively(target, overwrite = false)
            if (!source.deleteRecursively()) {
                Timber.tag(TAG).w("Legacy project source not fully deleted after copy: %s", source.absolutePath)
            }
            Timber.tag(TAG).i("Migrated legacy private project by copy: %s -> %s", source.absolutePath, target.absolutePath)
            true
        }.getOrElse { throwable ->
            target.deleteRecursively()
            Timber.tag(TAG).e(throwable, "Failed to migrate legacy private project: %s", source.absolutePath)
            false
        }
    }

    private fun writeMigrationMarker(markerFile: File) {
        runCatching {
            markerFile.parentFile?.mkdirs()
            markerFile.writeText("done")
        }.onFailure { Timber.tag(TAG).w(it, "Failed to write migration marker: %s", markerFile.absolutePath) }
    }

    private fun isLegacyPendingSourceRoot(path: String): Boolean = path.startsWith(ProjectLocationEntity.LEGACY_PENDING_SOURCE_ROOT_PREFIX)

    private fun normalizePath(path: String): String = runCatching { File(path).canonicalPath }.getOrElse { File(path).absolutePath }

    private fun normalizePath(file: File): String = runCatching { file.canonicalPath }.getOrElse { file.absolutePath }

    private fun File.canonicalOrAbsolutePath(): String = runCatching { canonicalPath }.getOrElse { absolutePath }
}

/**
 * 项目位置信息
 *
 * @property projectId 项目 ID（稳定）
 * @property projectDirName 项目目录名
 * @property registered 注册时间戳
 */
data class ProjectLocation(
    val projectId: String,
    val projectDirName: String,
    val sourceRootPath: String,
    val registered: Long
)
