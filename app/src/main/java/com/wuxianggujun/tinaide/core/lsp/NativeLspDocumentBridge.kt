package com.wuxianggujun.tinaide.core.lsp

import android.content.Context
import android.net.Uri
import android.util.Log
import com.wuxianggujun.tinaide.lsp.NativeLspService
import io.github.rosemoe.sora.event.ContentChangeEvent
import io.github.rosemoe.sora.event.SubscriptionReceipt
import io.github.rosemoe.sora.widget.subscribeEvent
import io.github.rosemoe.sora.widget.CodeEditor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.cancel
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * 将 CodeEditor 的文本更新同步到 NativeLspService，确保 clangd 拥有最新文档。
 */
object NativeLspDocumentBridge {

    private const val TAG = "NativeLspDocBridge"

    private val sessions = ConcurrentHashMap<String, Session>()
    private val mainScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    fun bind(context: Context, editor: CodeEditor, filePath: String, projectPath: String?): Handle? {
        NativeLspHealthMonitor.start(context)
        val absolutePath = File(filePath).absolutePath
        
        val existingSession = sessions[absolutePath]
        if (existingSession != null) {
            Log.d(TAG, "Reusing existing session for $absolutePath")
            return Handle(absolutePath)
        }

        val session = Session(
            context = context.applicationContext,
            editor = editor,
            filePath = absolutePath,
            projectPath = projectPath ?: File(absolutePath).parent
        )

        sessions[absolutePath] = session
        session.start()
        return Handle(absolutePath)
    }

    fun dispose(filePath: String) {
        val absolutePath = File(filePath).absolutePath
        sessions.remove(absolutePath)?.dispose()
    }

    suspend fun flushPendingSync(filePath: String) {
        val absolutePath = File(filePath).absolutePath
        sessions[absolutePath]?.flushPendingSync()
    }

    class Handle internal constructor(private val key: String) {
        fun dispose() {
            NativeLspDocumentBridge.dispose(key)
        }
    }
    private class Session(
        private val context: Context,
        private val editor: CodeEditor,
        private val filePath: String,
        private val projectPath: String?
    ) {
        private val workerScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        private val fileUri = Uri.fromFile(File(filePath)).toString()
        private var subscription: SubscriptionReceipt<ContentChangeEvent>? = null
        private var pendingSync: Job? = null
        @Volatile private var opened = false
        @Volatile private var disposed = false
        private var version = 1
        private val clangdPath: String? = NativeLspBinaryResolver.resolveClangdBinary(context).also { resolved ->
            if (!resolved.isNullOrBlank()) {
                NativeLspService.setDefaultClangdBinary(resolved)
            }
        }

        fun start() {
            if (clangdPath.isNullOrBlank()) {
                Log.w(TAG, "clangd binary not found, LSP features disabled for $filePath")
                return
            }
            
            workerScope.launch {
                if (!ensureNativeClient()) {
                    Log.w(TAG, "Native client unavailable, skip $filePath")
                    return@launch
                }
                val content = readEditorText()
                Log.d(TAG, "Document opened: $fileUri")
                val openedResult = runCatching {
                    NativeLspService.nativeDidOpenTextDocument(fileUri, content)
                }
                if (openedResult.isFailure) {
                    Log.e(TAG, "Failed to send didOpen", openedResult.exceptionOrNull())
                    return@launch
                }
                opened = true
                registerListeners()
                Log.i(TAG, "Native LSP synced document: $filePath")
            }
        }
        private suspend fun registerListeners() {
            withContext(Dispatchers.Main) {
                subscription = editor.subscribeEvent<ContentChangeEvent> { _, _ ->
                    scheduleSync()
                }
            }
        }

        private fun scheduleSync() {
            if (disposed || !opened) return
            pendingSync?.cancel()
            pendingSync = workerScope.launch {
                delay(300)
                sendSnapshot()
            }
        }

        suspend fun flushPendingSync() {
            if (disposed || !opened) return
            pendingSync?.cancel()
            sendSnapshot()
            // 给 clangd 一点时间处理 didChange
            delay(50)
        }

        private suspend fun sendSnapshot() {
            val snapshot = readEditorText()
            val nextVersion = incrementVersion()
            Log.d(TAG, "Document changed: $fileUri, version=$nextVersion")
            val changeResult = runCatching {
                NativeLspService.nativeDidChangeTextDocument(fileUri, snapshot, nextVersion)
            }
            if (changeResult.isFailure) {
                Log.e(TAG, "Failed to send didChange", changeResult.exceptionOrNull())
            }
        }
        private suspend fun readEditorText(): String {
            return withContext(Dispatchers.Main) {
                editor.text.toString()
            }
        }

        private fun incrementVersion(): Int {
            version += 1
            return version
        }

        private suspend fun ensureNativeClient(): Boolean {
            if (NativeLspService.nativeIsInitialized()) {
                return true
            }
            
            val workDir = projectPath ?: "/"
            val requestedClangdPath = clangdPath ?: NativeLspService.getConfiguredClangdBinary()
                ?: NativeLspService.defaultClangdBinaryPath()
            Log.i(TAG, "LSP initializing: clangd=$requestedClangdPath, workDir=$workDir")
            val result = NativeLspService.initialize(
                clangdPath = requestedClangdPath,
                workDir = workDir
            )
            if (result) {
                Log.i(TAG, "LSP initialized successfully")
            } else {
                Log.e(TAG, "LSP initialization failed - clangd may not be available")
            }
            return result
        }
        fun dispose() {
            if (disposed) return
            disposed = true
            pendingSync?.cancel()
            subscription?.let { receipt ->
                mainScope.launch { receipt.unsubscribe() }
            }
            workerScope.launch {
                if (opened) {
                    Log.d(TAG, "Document closed: $fileUri")
                    val closeResult = runCatching {
                        NativeLspService.nativeDidCloseTextDocument(fileUri)
                    }
                    if (closeResult.isFailure) {
                        Log.w(TAG, "Failed to send didClose", closeResult.exceptionOrNull())
                    }
                }
            }.invokeOnCompletion {
                workerScope.cancel()
            }
        }
    }
}
