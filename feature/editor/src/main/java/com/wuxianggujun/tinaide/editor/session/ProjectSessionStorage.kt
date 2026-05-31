package com.wuxianggujun.tinaide.editor.session

import android.content.Context
import com.wuxianggujun.tinaide.editor.bookmark.db.BookmarkDatabase
import com.wuxianggujun.tinaide.editor.session.db.EditorFileStateEntity
import com.wuxianggujun.tinaide.editor.session.db.EditorSessionEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber

/**
 * 负责将编辑器会话信息保存到 Room 数据库
 * 既给 EditorManager 使用，也能被 ProjectManager 读取。
 */
data class ProjectSessionFileSnapshot(
    val path: String = "",
    val cursorLine: Int = 0,
    val cursorColumn: Int = 0,
    val scrollX: Int = 0,
    val scrollY: Int = 0
)

data class ProjectSessionSnapshot(
    val activeFile: String? = null,
    val files: List<ProjectSessionFileSnapshot> = emptyList(),
    val updatedAt: Long = 0L
) {
    fun normalized(currentTime: Long = System.currentTimeMillis()): ProjectSessionSnapshot {
        val sanitizedFiles = files.filter { it.path.isNotBlank() }
        val sanitizedActive = activeFile?.takeIf { it.isNotBlank() }
        val timestamp = if (updatedAt <= 0L) currentTime else updatedAt
        return copy(activeFile = sanitizedActive, files = sanitizedFiles, updatedAt = timestamp)
    }
}

class ProjectSessionStorage(context: Context) {
    private val database = BookmarkDatabase.getInstance(context)
    private val sessionDao = database.editorSessionDao()

    companion object {
        private const val TAG = "ProjectSessionStorage"
    }

    suspend fun load(projectPath: String): ProjectSessionSnapshot? = withContext(Dispatchers.IO) {
        return@withContext runCatching {
            val session = sessionDao.getSession(projectPath)
            val fileStates = sessionDao.getFileStates(projectPath)

            if (session == null && fileStates.isEmpty()) {
                return@runCatching null
            }

            ProjectSessionSnapshot(
                activeFile = session?.activeFile,
                files = fileStates.map { it.toDomainModel() },
                updatedAt = session?.updatedAt ?: System.currentTimeMillis()
            ).normalized()
        }.onFailure { e ->
            Timber.tag(TAG).e(e, "Failed to load editor session for project: %s", projectPath)
        }.getOrNull()
    }

    suspend fun save(projectPath: String, snapshot: ProjectSessionSnapshot) = withContext(Dispatchers.IO) {
        runCatching {
            val normalized = snapshot.normalized()

            // 保存会话
            val sessionEntity = EditorSessionEntity.fromSnapshot(projectPath, normalized)
            sessionDao.insertSession(sessionEntity)

            // 先删除旧的文件状态，再插入新的
            sessionDao.deleteFileStates(projectPath)
            val fileStateEntities = normalized.files.map { file ->
                EditorFileStateEntity.fromDomainModel(projectPath, file)
            }
            sessionDao.insertFileStates(fileStateEntities)

            Timber.tag(TAG).d("Saved editor session for project: %s (files=%d)", projectPath, normalized.files.size)
        }.onFailure { e ->
            Timber.tag(TAG).e(e, "Failed to save editor session for project: %s", projectPath)
        }
    }

    suspend fun clear(projectPath: String) = withContext(Dispatchers.IO) {
        runCatching {
            sessionDao.clearProjectSession(projectPath)
            Timber.tag(TAG).d("Cleared editor session for project: %s", projectPath)
        }.onFailure { e ->
            Timber.tag(TAG).e(e, "Failed to clear editor session for project: %s", projectPath)
        }
    }
}
