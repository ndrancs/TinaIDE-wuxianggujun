package com.wuxianggujun.tinaide.editor.session

import android.content.Context
import android.os.Build
import android.os.FileObserver
import com.wuxianggujun.tinaide.core.i18n.Strings
import com.wuxianggujun.tinaide.core.i18n.strOr
import com.wuxianggujun.tinaide.editor.io.FileCharsetDetector
import com.wuxianggujun.tinaide.editor.symbol.ProjectSymbolIndexService
import java.io.File
import java.io.IOException
import java.nio.charset.Charset
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
data class DocumentSessionState(
    val tabId: String,
    val file: File,
    val title: String,
    val isDirty: Boolean = false,
    val isSaving: Boolean = false,
    val canUndo: Boolean = false,
    val canRedo: Boolean = false,
    val lastSavedAt: Long? = null,
    val lastEditAt: Long? = null,
    val lastError: String? = null,
    val cursorLine: Int = 0,
    val cursorColumn: Int = 0,
    val scrollX: Int = 0,
    val scrollY: Int = 0,
    val hasExternalModification: Boolean = false,
    val charsetName: String = Charsets.UTF_8.name()
)

enum class SaveReason {
    MANUAL,
    AUTO,
    CLOSE
}

sealed class SaveResult {
    data class Success(val timestamp: Long, val reason: SaveReason) : SaveResult()
    data class Failure(val message: String) : SaveResult()
    data object NoOp : SaveResult()
}

data class EditorViewState(
    val cursorLine: Int = 0,
    val cursorColumn: Int = 0,
    val scrollX: Int = 0,
    val scrollY: Int = 0
)

data class DetachedEditorSnapshot(
    val text: String,
    val viewState: EditorViewState,
    val isDirty: Boolean,
    val canUndo: Boolean,
    val canRedo: Boolean,
    val documentVersion: Long,
    val charsetName: String
)

class DocumentSession(
    private val context: Context,
    val tabId: String,
    val file: File,
    private val projectSymbolIndexServiceProvider: () -> ProjectSymbolIndexService? = { null },
    initialViewState: EditorViewState? = null,
    private val coroutineScope: CoroutineScope
) {
    companion object {
        private const val INTERNAL_WRITE_SUPPRESS_WINDOW_MS = 1500L
    }

    interface EditorBinding {
        fun readText(): String
        fun setText(text: CharSequence)
        fun textLength(): Int
        fun canUndo(): Boolean
        fun canRedo(): Boolean
        fun undo()
        fun redo()
        fun currentDocumentVersion(): Long
        fun currentViewState(): EditorViewState? = null
    }

    private data class SaveSnapshot(
        val text: String,
        val timestamp: Long,
        val documentVersion: Long,
        val fingerprint: TextFingerprint,
        val fileModifiedAt: Long,
        val fileSize: Long,
        val isUpToDate: Boolean,
    )

    private data class TextFingerprint(
        val length: Int,
        val hash: Long
    )

    private data class FileWriteMarker(
        val modifiedAt: Long,
        val fileSize: Long,
        val observedAt: Long
    )

    private enum class BaselineState {
        INITIAL_LOADING,
        READY
    }

    private val editorBinding = AtomicReference<EditorBinding?>()
    private val saveMutex = Mutex()
    private val initialFileCharset = FileCharsetDetector.detect(file)

    @Volatile
    private var fileCharset: Charset = initialFileCharset

    @Volatile
    private var cleanVersion: Long = -1L

    @Volatile
    private var cleanFingerprint: TextFingerprint? = null
    private var lastEditTimestamp: Long? = null

    @Volatile
    private var baselineState: BaselineState = BaselineState.INITIAL_LOADING

    @Volatile
    private var detachedEditorSnapshot: DetachedEditorSnapshot? = null

    // FileObserver 相关字段
    private var fileObserver: FileObserver? = null

    @Volatile
    private var isSavingInternally: Boolean = false

    @Volatile
    private var fileLastModifiedOnOpen: Long = if (file.exists()) file.lastModified() else 0L

    @Volatile
    private var fileSizeOnOpen: Long = if (file.exists()) file.length() else 0L

    @Volatile
    private var lastInternalWriteMarker: FileWriteMarker? = readCurrentWriteMarker()

    @Volatile
    private var lastObservedWriteMarker: FileWriteMarker? = readCurrentWriteMarker()

    private val _state = MutableStateFlow(
        DocumentSessionState(
            tabId = tabId,
            file = file,
            title = file.name.ifBlank { "Untitled" },
            cursorLine = initialViewState?.cursorLine ?: 0,
            cursorColumn = initialViewState?.cursorColumn ?: 0,
            scrollX = initialViewState?.scrollX ?: 0,
            scrollY = initialViewState?.scrollY ?: 0,
            charsetName = initialFileCharset.name()
        )
    )
    val state: StateFlow<DocumentSessionState> = _state.asStateFlow()

    init {
        startFileWatcher()
    }
    fun attachEditor(binding: EditorBinding) {
        editorBinding.set(binding)
        if (cleanVersion < 0L) {
            cleanVersion = binding.currentDocumentVersion()
        }
        if (cleanFingerprint == null) {
            val text = binding.readText()
            cleanFingerprint = buildTextFingerprint(text)
        }
        refreshState(binding.canUndo(), binding.canRedo())
    }

    fun detachEditor(binding: EditorBinding) {
        if (!editorBinding.compareAndSet(binding, null)) return
        if (baselineState == BaselineState.INITIAL_LOADING && !_state.value.isDirty) return

        val viewState = binding.currentViewState() ?: currentViewState()
        val dirty = computeDirty(binding, changeCausedByUndoManager = false, forceCompare = true)
        val snapshot = DetachedEditorSnapshot(
            text = binding.readText(),
            viewState = viewState,
            isDirty = dirty,
            canUndo = binding.canUndo(),
            canRedo = binding.canRedo(),
            documentVersion = binding.currentDocumentVersion(),
            charsetName = fileCharset.name()
        )
        detachedEditorSnapshot = snapshot
        _state.update {
            it.copy(
                isDirty = snapshot.isDirty,
                canUndo = snapshot.canUndo,
                canRedo = snapshot.canRedo,
                cursorLine = snapshot.viewState.cursorLine,
                cursorColumn = snapshot.viewState.cursorColumn,
                scrollX = snapshot.viewState.scrollX,
                scrollY = snapshot.viewState.scrollY,
                charsetName = snapshot.charsetName,
                lastError = null
            )
        }
    }

    fun hasActiveEditor(): Boolean = editorBinding.get() != null

    fun detachedEditorSnapshot(): DetachedEditorSnapshot? = detachedEditorSnapshot

    fun markDetachedEditorSnapshotRestored(snapshot: DetachedEditorSnapshot) {
        if (detachedEditorSnapshot === snapshot) {
            detachedEditorSnapshot = null
        }
        fileCharset = runCatching { Charset.forName(snapshot.charsetName) }.getOrDefault(fileCharset)
        baselineState = BaselineState.READY
        val dirty = cleanFingerprint?.let { buildTextFingerprint(snapshot.text) != it } ?: snapshot.isDirty
        _state.update {
            it.copy(
                isDirty = dirty,
                canUndo = false,
                canRedo = false,
                cursorLine = snapshot.viewState.cursorLine,
                cursorColumn = snapshot.viewState.cursorColumn,
                scrollX = snapshot.viewState.scrollX,
                scrollY = snapshot.viewState.scrollY,
                charsetName = fileCharset.name(),
                lastError = null
            )
        }
    }

    fun markEditorSnapshotClean(charset: Charset? = null) {
        val binding = editorBinding.get() ?: return
        val effectiveCharset = charset ?: fileCharset
        fileCharset = effectiveCharset
        cleanVersion = binding.currentDocumentVersion()
        val text = binding.readText()
        cleanFingerprint = buildTextFingerprint(text)
        baselineState = BaselineState.READY
        val marker = readCurrentWriteMarker()
        if (marker != null) {
            lastInternalWriteMarker = marker
            lastObservedWriteMarker = marker
            fileLastModifiedOnOpen = marker.modifiedAt
            fileSizeOnOpen = marker.fileSize
        }
        _state.update {
            it.copy(
                isDirty = false,
                canUndo = binding.canUndo(),
                canRedo = binding.canRedo(),
                lastError = null,
                charsetName = effectiveCharset.name()
            )
        }
        detachedEditorSnapshot = null
    }

    fun notifyEditorContentChanged(
        canUndo: Boolean,
        canRedo: Boolean,
        changeCausedByUndoManager: Boolean = false
    ) {
        lastEditTimestamp = System.currentTimeMillis()
        val binding = editorBinding.get()
        if (binding == null) {
            _state.update {
                it.copy(
                    canUndo = canUndo,
                    canRedo = canRedo,
                    lastEditAt = lastEditTimestamp,
                    lastError = null
                )
            }
            return
        }
        detachedEditorSnapshot = null

        val dirty = computeDirty(binding, changeCausedByUndoManager = changeCausedByUndoManager, forceCompare = false)
        _state.update {
            it.copy(
                isDirty = dirty,
                canUndo = canUndo,
                canRedo = canRedo,
                lastEditAt = lastEditTimestamp,
                lastError = null
            )
        }
    }

    fun updateCursorPosition(line: Int, column: Int) {
        _state.update { current ->
            if (current.cursorLine == line && current.cursorColumn == column) {
                current
            } else {
                current.copy(cursorLine = line, cursorColumn = column)
            }
        }
    }

    fun updateScrollPosition(scrollX: Int, scrollY: Int) {
        _state.update { current ->
            if (current.scrollX == scrollX && current.scrollY == scrollY) {
                current
            } else {
                current.copy(scrollX = scrollX, scrollY = scrollY)
            }
        }
    }

    fun updateViewState(state: EditorViewState) {
        _state.update { current ->
            val targetCursorLine = state.cursorLine
            val targetCursorColumn = state.cursorColumn
            val targetScrollX = state.scrollX
            val targetScrollY = state.scrollY
            if (
                current.cursorLine == targetCursorLine &&
                current.cursorColumn == targetCursorColumn &&
                current.scrollX == targetScrollX &&
                current.scrollY == targetScrollY
            ) {
                current
            } else {
                current.copy(
                    cursorLine = targetCursorLine,
                    cursorColumn = targetCursorColumn,
                    scrollX = targetScrollX,
                    scrollY = targetScrollY
                )
            }
        }
    }

    private fun currentViewState(): EditorViewState {
        val state = _state.value
        return EditorViewState(
            cursorLine = state.cursorLine,
            cursorColumn = state.cursorColumn,
            scrollX = state.scrollX,
            scrollY = state.scrollY
        )
    }

    private fun refreshState(canUndo: Boolean, canRedo: Boolean) {
        val binding = editorBinding.get()
        val dirty = binding?.let { computeDirty(it, changeCausedByUndoManager = false, forceCompare = true) }
            ?: _state.value.isDirty
        _state.update {
            it.copy(
                canUndo = canUndo,
                canRedo = canRedo,
                isDirty = dirty,
                lastError = null
            )
        }
    }
    fun requestUndo() {
        editorBinding.get()?.let {
            it.undo()
            refreshState(it.canUndo(), it.canRedo())
        }
    }

    fun requestRedo() {
        editorBinding.get()?.let {
            it.redo()
            refreshState(it.canUndo(), it.canRedo())
        }
    }
    suspend fun save(reason: SaveReason): SaveResult {
        _state.update { it.copy(isSaving = true, lastError = null) }
        return try {
            saveMutex.withLock {
                isSavingInternally = true
                try {
                    val binding = editorBinding.get()
                    val detachedSnapshot = if (binding == null) detachedEditorSnapshot else null
                    if (binding == null && detachedSnapshot == null) {
                        _state.update { it.copy(isSaving = false) }
                        return@withLock SaveResult.Failure(Strings.editor_error_not_initialized.strOr(context))
                    }

                    val snapshot = withContext(Dispatchers.IO) {
                        val text = binding?.readText() ?: detachedSnapshot!!.text
                        val snapshotVersion = binding?.currentDocumentVersion() ?: detachedSnapshot!!.documentVersion
                        val fingerprint = buildTextFingerprint(text)
                        writeFileSafely(text)
                        val versionAfterWrite = binding?.currentDocumentVersion() ?: snapshotVersion
                        SaveSnapshot(
                            text = text,
                            timestamp = System.currentTimeMillis(),
                            documentVersion = snapshotVersion,
                            fingerprint = fingerprint,
                            fileModifiedAt = file.lastModified(),
                            fileSize = file.length(),
                            isUpToDate = versionAfterWrite == snapshotVersion,
                        )
                    }

                    cleanVersion = snapshot.documentVersion
                    cleanFingerprint = snapshot.fingerprint
                    baselineState = BaselineState.READY
                    fileLastModifiedOnOpen = snapshot.fileModifiedAt
                    fileSizeOnOpen = snapshot.fileSize
                    val internalMarker = FileWriteMarker(
                        modifiedAt = snapshot.fileModifiedAt,
                        fileSize = snapshot.fileSize,
                        observedAt = snapshot.timestamp
                    )
                    lastInternalWriteMarker = internalMarker
                    lastObservedWriteMarker = internalMarker
                    _state.update {
                        it.copy(
                            isDirty = !snapshot.isUpToDate,
                            isSaving = false,
                            lastSavedAt = snapshot.timestamp,
                            lastError = null,
                            charsetName = fileCharset.name()
                        )
                    }

                    // 保存后更新项目级符号索引（只影响当前文件，后台异步处理）
                    if (binding != null) {
                        detachedEditorSnapshot = null
                    } else if (detachedEditorSnapshot != null) {
                        detachedEditorSnapshot = detachedEditorSnapshot?.copy(
                            isDirty = !snapshot.isUpToDate,
                            canUndo = detachedEditorSnapshot?.canUndo ?: false,
                            canRedo = detachedEditorSnapshot?.canRedo ?: false,
                            documentVersion = snapshot.documentVersion,
                            charsetName = fileCharset.name()
                        )
                    }
                    projectSymbolIndexServiceProvider()?.onFileSaved(file, snapshot.text)
                    SaveResult.Success(snapshot.timestamp, reason)
                } finally {
                    isSavingInternally = false
                }
            }
        } catch (e: Exception) {
            isSavingInternally = false
            _state.update { it.copy(isSaving = false, lastError = e.message) }
            SaveResult.Failure(e.message ?: Strings.editor_error_save_failed.strOr(context))
        }
    }
    private fun writeFileSafely(content: String) {
        val parent = file.parentFile ?: throw IOException(Strings.editor_error_cannot_resolve_dir.strOr(context))
        if (!parent.exists() && !parent.mkdirs()) {
            throw IOException(Strings.editor_error_cannot_create_dir.strOr(context, parent.absolutePath))
        }
        val tmpFile = File(parent, "${file.name}.autosave.tmp")
        tmpFile.writeText(content, fileCharset)
        try {
            try {
                Files.move(
                    tmpFile.toPath(),
                    file.toPath(),
                    StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE
                )
            } catch (ignored: AtomicMoveNotSupportedException) {
                Files.move(
                    tmpFile.toPath(),
                    file.toPath(),
                    StandardCopyOption.REPLACE_EXISTING
                )
            }
        } finally {
            runCatching { if (tmpFile.exists()) tmpFile.delete() }
        }
    }

    fun lastEditAt(): Long? = lastEditTimestamp

    // ========== FileObserver 相关方法 ==========

    private fun startFileWatcher() {
        if (!file.exists() || file.isDirectory) return

        val mask = FileObserver.CLOSE_WRITE
        fileObserver = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            object : FileObserver(file, mask) {
                override fun onEvent(event: Int, path: String?) {
                    handleFileEvent(event, path)
                }
            }
        } else {
            @Suppress("DEPRECATION")
            object : FileObserver(file.absolutePath, mask) {
                override fun onEvent(event: Int, path: String?) {
                    handleFileEvent(event, path)
                }
            }
        }
        fileObserver?.startWatching()
    }

    private fun handleFileEvent(event: Int, eventPath: String?) {
        if (event != FileObserver.CLOSE_WRITE) return
        if (!eventPath.isNullOrBlank()) {
            val normalized = eventPath.replace('\\', '/')
            val fileName = file.name.replace('\\', '/')
            val fullPath = file.absolutePath.replace('\\', '/')
            if (normalized != fileName && normalized != fullPath) {
                return
            }
        }

        coroutineScope.launch(Dispatchers.IO) {
            if (isSavingInternally) return@launch

            val marker = readCurrentWriteMarker() ?: return@launch
            if (isDuplicateObservedWrite(marker)) return@launch
            if (shouldIgnoreInternalWrite(marker)) return@launch

            val changed = marker.modifiedAt > fileLastModifiedOnOpen ||
                marker.fileSize != fileSizeOnOpen
            if (changed) {
                _state.update { current ->
                    if (current.hasExternalModification) {
                        current
                    } else {
                        current.copy(hasExternalModification = true)
                    }
                }
            }
        }
    }

    fun stopFileWatcher() {
        fileObserver?.stopWatching()
        fileObserver = null
    }

    fun acknowledgeExternalModification() {
        val marker = readCurrentWriteMarker()
        if (marker != null) {
            fileLastModifiedOnOpen = marker.modifiedAt
            fileSizeOnOpen = marker.fileSize
            lastObservedWriteMarker = marker
        }
        _state.update { it.copy(hasExternalModification = false) }
    }

    suspend fun forceOverwrite(reason: SaveReason): SaveResult {
        acknowledgeExternalModification()
        return save(reason)
    }

    fun reloadFromDisk(): Boolean {
        return try {
            val binding = editorBinding.get() ?: return false

            val charset = FileCharsetDetector.detect(file)
            val newContent = file.readText(charset)

            binding.setText(newContent)
            fileCharset = charset
            cleanFingerprint = buildTextFingerprint(binding.readText())
            cleanVersion = binding.currentDocumentVersion()
            baselineState = BaselineState.READY
            val marker = readCurrentWriteMarker()
            if (marker != null) {
                fileLastModifiedOnOpen = marker.modifiedAt
                fileSizeOnOpen = marker.fileSize
                lastInternalWriteMarker = marker
                lastObservedWriteMarker = marker
            }

            acknowledgeExternalModification()
            _state.update {
                it.copy(
                    isDirty = false,
                    canUndo = binding.canUndo(),
                    canRedo = binding.canRedo(),
                    lastError = null,
                    charsetName = charset.name()
                )
            }
            true
        } catch (e: Exception) {
            _state.update { it.copy(lastError = e.message) }
            false
        }
    }

    private fun computeDirty(
        binding: EditorBinding,
        changeCausedByUndoManager: Boolean,
        forceCompare: Boolean
    ): Boolean {
        if (binding.currentDocumentVersion() == cleanVersion) {
            return false
        }

        val baseline = cleanFingerprint
        if (baseline == null) {
            val text = binding.readText()
            cleanFingerprint = buildTextFingerprint(text)
            return false
        }

        val shouldCompare = forceCompare ||
            changeCausedByUndoManager ||
            baselineState == BaselineState.INITIAL_LOADING
        if (!shouldCompare) {
            return true
        }

        val currentFingerprint = buildTextFingerprint(binding.readText())
        return currentFingerprint != baseline
    }

    private fun buildTextFingerprint(text: String): TextFingerprint {
        var hash = -0x340d631b8c4675d9L // FNV-1a 64-bit offset basis
        val prime = 0x100000001b3L
        for (ch in text) {
            hash = hash xor ch.code.toLong()
            hash *= prime
        }
        return TextFingerprint(length = text.length, hash = hash)
    }

    private fun readCurrentWriteMarker(): FileWriteMarker? {
        if (!file.exists() || !file.isFile) return null
        return FileWriteMarker(
            modifiedAt = file.lastModified(),
            fileSize = file.length(),
            observedAt = System.currentTimeMillis()
        )
    }

    private fun isDuplicateObservedWrite(marker: FileWriteMarker): Boolean {
        val previous = lastObservedWriteMarker
        if (previous != null &&
            previous.modifiedAt == marker.modifiedAt &&
            previous.fileSize == marker.fileSize
        ) {
            return true
        }
        lastObservedWriteMarker = marker
        return false
    }

    private fun shouldIgnoreInternalWrite(marker: FileWriteMarker): Boolean {
        val internal = lastInternalWriteMarker ?: return false
        val sameSignature = internal.modifiedAt == marker.modifiedAt &&
            internal.fileSize == marker.fileSize
        if (!sameSignature) return false
        val delta = marker.observedAt - internal.observedAt
        return delta in 0..INTERNAL_WRITE_SUPPRESS_WINDOW_MS
    }
}
