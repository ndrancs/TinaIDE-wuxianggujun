package com.wuxianggujun.tinaide.editor.bookmark

import android.content.Context
import com.wuxianggujun.tinaide.editor.bookmark.model.Bookmark
import com.wuxianggujun.tinaide.editor.bookmark.model.ProjectBookmarkState
import com.wuxianggujun.tinaide.editor.bookmark.persistence.BookmarkStateStorage
import java.io.File
import java.util.TreeMap
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * 书签服务（项目级）
 *
 * 目标：
 * - 提供书签数据的 CRUD
 * - 支持跨会话持久化（保存到项目目录）
 * - 提供"上一个/下一个书签"导航查询
 */
class BookmarkService(context: Context) : BookmarkRepository {
    private val storage = BookmarkStateStorage(context)

    private data class BookmarkKey(
        val filePath: String,
        val line: Int
    ) : Comparable<BookmarkKey> {
        override fun compareTo(other: BookmarkKey): Int {
            val pathCompare = filePath.compareTo(other.filePath)
            if (pathCompare != 0) return pathCompare
            return line.compareTo(other.line)
        }
    }

    private data class ProjectData(
        val mutex: Mutex = Mutex(),
        val index: TreeMap<BookmarkKey, Bookmark> = TreeMap(),
        val flow: MutableStateFlow<List<Bookmark>> = MutableStateFlow(emptyList()),
        var loaded: Boolean = false
    )

    private data class UpdateNoteResult(
        val ok: Boolean,
        val changedSnapshot: List<Bookmark>? = null
    )

    private data class PruneResult(
        val removedCount: Int,
        val snapshot: List<Bookmark>
    )

    private val projectDataMap = ConcurrentHashMap<String, ProjectData>()

    private fun getProjectData(projectPath: String): ProjectData = projectDataMap.computeIfAbsent(projectPath) { ProjectData() }

    private fun bookmarkKey(filePath: String, line: Int): BookmarkKey = BookmarkKey(filePath = filePath, line = line)

    private fun bookmarkKey(bookmark: Bookmark): BookmarkKey = bookmarkKey(bookmark.filePath, bookmark.line)

    private fun publishSnapshotLocked(data: ProjectData): List<Bookmark> {
        val snapshot = data.index.values.toList()
        data.flow.value = snapshot
        return snapshot
    }

    override fun bookmarksFlow(projectPath: String): StateFlow<List<Bookmark>> = getProjectData(projectPath).flow.asStateFlow()

    override suspend fun prefetch(projectPath: String) {
        ensureLoaded(projectPath)
    }

    override suspend fun toggle(projectPath: String, filePath: String, line: Int): Bookmark? {
        ensureLoaded(projectPath)
        val data = getProjectData(projectPath)
        val (snapshot, added) = data.mutex.withLock {
            val key = bookmarkKey(filePath, line)
            if (data.index.remove(key) != null) {
                publishSnapshotLocked(data) to null
            } else {
                val bookmark = Bookmark(filePath = filePath, line = line)
                data.index[key] = bookmark
                publishSnapshotLocked(data) to bookmark
            }
        }
        persist(projectPath, snapshot)
        return added
    }

    override suspend fun remove(projectPath: String, filePath: String, line: Int): Boolean {
        ensureLoaded(projectPath)
        val data = getProjectData(projectPath)
        val snapshot = data.mutex.withLock {
            val key = bookmarkKey(filePath, line)
            if (data.index.remove(key) == null) return@withLock null
            publishSnapshotLocked(data)
        } ?: return false
        persist(projectPath, snapshot)
        return true
    }

    override suspend fun updateNote(projectPath: String, filePath: String, line: Int, note: String): Boolean {
        ensureLoaded(projectPath)
        val data = getProjectData(projectPath)
        val trimmed = note.trim()
        val result = data.mutex.withLock {
            val key = bookmarkKey(filePath, line)
            val existing = data.index[key] ?: return@withLock UpdateNoteResult(ok = false)
            if (existing.note == trimmed) return@withLock UpdateNoteResult(ok = true, changedSnapshot = null)
            data.index[key] = existing.copy(note = trimmed)
            UpdateNoteResult(ok = true, changedSnapshot = publishSnapshotLocked(data))
        }
        if (!result.ok) return false
        result.changedSnapshot?.let { persist(projectPath, it) }
        return true
    }

    override suspend fun findNext(projectPath: String, currentFilePath: String, currentLine: Int): Bookmark? {
        ensureLoaded(projectPath)
        val data = getProjectData(projectPath)
        return data.mutex.withLock {
            if (data.index.isEmpty()) return@withLock null
            val entry = data.index.higherEntry(bookmarkKey(currentFilePath, currentLine))
            entry?.value ?: data.index.firstEntry()?.value
        }
    }

    override suspend fun findPrevious(projectPath: String, currentFilePath: String, currentLine: Int): Bookmark? {
        ensureLoaded(projectPath)
        val data = getProjectData(projectPath)
        return data.mutex.withLock {
            if (data.index.isEmpty()) return@withLock null
            val entry = data.index.lowerEntry(bookmarkKey(currentFilePath, currentLine))
            entry?.value ?: data.index.lastEntry()?.value
        }
    }

    private suspend fun ensureLoaded(projectPath: String) {
        val data = getProjectData(projectPath)
        data.mutex.withLock {
            if (data.loaded) return
            val loaded = storage.load(projectPath)?.bookmarks ?: emptyList()
            data.index.clear()
            loaded.forEach { bookmark ->
                data.index[bookmarkKey(bookmark)] = bookmark
            }
            publishSnapshotLocked(data)
            data.loaded = true
        }
    }

    override suspend fun clearAll(projectPath: String) {
        ensureLoaded(projectPath)
        val data = getProjectData(projectPath)
        data.mutex.withLock {
            data.index.clear()
            data.flow.value = emptyList()
        }
        persist(projectPath, emptyList())
    }

    override suspend fun pruneMissingFiles(projectPath: String): Int {
        ensureLoaded(projectPath)
        val data = getProjectData(projectPath)
        var attempt = 0
        while (attempt++ < 3) {
            val snapshot = data.mutex.withLock { data.index.values.toList() }
            val kept = withContext(Dispatchers.IO) {
                snapshot.filter { bm -> File(bm.filePath).exists() }
            }
            if (kept.size == snapshot.size) return 0

            val result = data.mutex.withLock {
                if (data.index.values.toList() != snapshot) return@withLock null
                data.index.clear()
                kept.forEach { bookmark ->
                    data.index[bookmarkKey(bookmark)] = bookmark
                }
                val updatedSnapshot = publishSnapshotLocked(data)
                PruneResult(
                    removedCount = snapshot.size - kept.size,
                    snapshot = updatedSnapshot
                )
            } ?: continue
            persist(projectPath, result.snapshot)
            return result.removedCount
        }
        return 0
    }

    private suspend fun persist(projectPath: String, bookmarks: List<Bookmark>) {
        storage.save(projectPath, ProjectBookmarkState(bookmarks = bookmarks))
    }
}
