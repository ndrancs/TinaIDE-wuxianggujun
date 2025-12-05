package com.wuxianggujun.tinaide.core.lsp

import android.content.Context
import android.net.Uri
import android.util.Log
import com.wuxianggujun.tinaide.lsp.NativeLspService
import com.wuxianggujun.tinaide.lsp.NativeLspMode
import com.wuxianggujun.tinaide.lsp.LspDebugPanel
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
import kotlinx.coroutines.withContext

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
        private val resolvedMode = if (!clangdPath.isNullOrBlank()) {
            NativeLspMode.REAL
        } else {
            NativeLspMode.MOCK
        }

        fun start() {
            workerScope.launch {
                if (!ensureNativeClient()) {
                    Log.w(TAG, "Native client unavailable, skip $filePath")
                    return@launch
                }
                val content = readEditorText()
                LspDebugPanel.onDocumentOpened(fileUri)
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
        }

        private suspend fun sendSnapshot() {
            val snapshot = readEditorText()
            val nextVersion = incrementVersion()
            LspDebugPanel.onDocumentChanged(fileUri, nextVersion)
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
                val activeMode = NativeLspService.getServerMode()
                if (activeMode == resolvedMode) {
                    return true
                }
                Log.w(TAG, "Native LSP running in $activeMode, restarting for ${resolvedMode.name}")
                runCatching { NativeLspService.nativeShutdown() }
                    .onFailure { Log.w(TAG, "Failed to shutdown existing NativeLspService cleanly", it) }
            }
            NativeLspService.setServerMode(resolvedMode)
            val workDir = projectPath ?: "/"
            val requestedClangdPath = clangdPath ?: NativeLspService.getConfiguredClangdBinary()
                ?: NativeLspService.defaultClangdBinaryPath()
            Log.i(TAG, "Initializing NativeLspService in ${resolvedMode.name}: $requestedClangdPath")
            LspDebugPanel.onLspInitializing(requestedClangdPath, workDir)
            val result = NativeLspService.initialize(
                clangdPath = requestedClangdPath,
                workDir = workDir
            )
            if (result) {
                LspDebugPanel.onLspInitialized()
            } else {
                LspDebugPanel.onLspInitFailed("initialize returned false")
            }
            if (!result) {
                Log.w(TAG, "Failed to initialize NativeLspService for $workDir")
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
                    LspDebugPanel.onDocumentClosed(fileUri)
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
