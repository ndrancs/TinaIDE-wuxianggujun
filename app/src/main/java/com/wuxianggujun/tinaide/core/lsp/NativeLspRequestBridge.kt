package com.wuxianggujun.tinaide.core.lsp

import android.net.Uri
import android.util.Log
import com.wuxianggujun.tinaide.lsp.NativeLspService
import com.wuxianggujun.tinaide.lsp.model.CompletionResult
import com.wuxianggujun.tinaide.lsp.model.HoverResult
import com.wuxianggujun.tinaide.lsp.model.Location
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * 面向编辑器的 Native LSP 请求桥接层。
 *
 * 当前支持 Hover / Definition / References 三种请求，并在内部确保
 * NativeLspService 已初始化、请求串行化以及取消后释放资源。
 */
object NativeLspRequestBridge {

    private const val TAG = "NativeLspRequestBridge"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private class RequestTask<T>(
        private val label: String,
        val identity: String,
        private val block: suspend () -> T?
    ) {
        private val callbacks = mutableListOf<(T?) -> Unit>()

        @Synchronized
        fun addCallback(callback: (T?) -> Unit) {
            callbacks.add(callback)
        }

        @Synchronized
        private fun snapshotCallbacks(): List<(T?) -> Unit> {
            return callbacks.toList().also { callbacks.clear() }
        }

        suspend fun run() {
            val result = try {
                block()
            } catch (cancelled: CancellationException) {
                Log.d(TAG, "$label request cancelled during execution identity=$identity")
                null
            } catch (t: Throwable) {
                Log.e(TAG, "$label request failed identity=$identity", t)
                null
            }
            val callbacks = snapshotCallbacks()
            withContext(Dispatchers.Main) {
                callbacks.forEach { callback -> runCatching { callback(result) } }
            }
        }

        fun markSkipped() {
            Log.d(TAG, "$label request skipped identity=$identity")
        }
    }

    private class RequestWorker<T>(
        private val label: String,
        private val scope: CoroutineScope,
        private val onIdle: (RequestWorker<T>) -> Unit
    ) {
        private var job: Job? = null
        private var currentTask: RequestTask<T>? = null
        private var pendingTask: RequestTask<T>? = null

        fun submit(identity: String, block: suspend () -> T?, callback: (T?) -> Unit) {
            var replacedTask: RequestTask<T>? = null
            var needsStart = false
            synchronized(this) {
                when {
                    currentTask?.identity == identity -> {
                        Log.d(TAG, "$label request deduped (running) identity=$identity")
                        currentTask?.addCallback(callback)
                        return
                    }
                    pendingTask?.identity == identity -> {
                        Log.d(TAG, "$label request deduped (pending) identity=$identity")
                        pendingTask?.addCallback(callback)
                        return
                    }
                    else -> {
                        val newTask = RequestTask(label, identity, block).apply {
                            addCallback(callback)
                        }
                        replacedTask = pendingTask
                        pendingTask = newTask
                        if (job == null) {
                            needsStart = true
                        }
                    }
                }
            }
            replacedTask?.markSkipped()
            if (needsStart) {
                synchronized(this) {
                    if (job == null) {
                        job = scope.launch { runLoop() }
                    }
                }
            }
        }

        private suspend fun runLoop() {
            while (true) {
                val (task, shouldStop) = synchronized(this) {
                    val next = pendingTask
                    if (next == null) {
                        currentTask = null
                        job = null
                        null to true
                    } else {
                        pendingTask = null
                        currentTask = next
                        next to false
                    }
                }
                if (shouldStop) {
                    onIdle(this)
                    return
                }
                task?.run()
            }
        }
    }

    private val hoverWorkers =
        ConcurrentHashMap<String, RequestWorker<HoverResult?>>()
    private val completionWorkers =
        ConcurrentHashMap<String, RequestWorker<CompletionResult?>>()
    private val definitionJobs = ConcurrentHashMap<String, Job>()
    private val referenceJobs = ConcurrentHashMap<String, Job>()

    fun requestHover(
        filePath: String,
        line: Int,
        column: Int,
        workDir: String?,
        onResult: (HoverResult?) -> Unit
    ) {
        val fileUri = buildUri(filePath)
        submitRequest(
            workers = hoverWorkers,
            key = fileUri,
            label = "Hover",
            identity = buildIdentity(filePath, line, column),
            blockProvider = {
                if (!ensureNativeClient(workDir)) return@submitRequest null
                NativeLspService.requestHoverAsync(fileUri, line, column)
            },
            onResult = onResult
        )
    }

    fun requestCompletion(
        filePath: String,
        line: Int,
        column: Int,
        workDir: String?,
        onResult: (CompletionResult?) -> Unit
    ) {
        val fileUri = buildUri(filePath)
        submitRequest(
            workers = completionWorkers,
            key = fileUri,
            label = "Completion",
            identity = buildIdentity(filePath, line, column),
            blockProvider = {
                if (!ensureNativeClient(workDir)) return@submitRequest null
                NativeLspDocumentBridge.flushPendingSync(filePath)
                NativeLspService.requestCompletionAsync(fileUri, line, column)
            },
            onResult = onResult
        )
    }

    fun requestDefinition(
        filePath: String,
        line: Int,
        column: Int,
        workDir: String?,
        onResult: (List<Location>?) -> Unit
    ) {
        val fileUri = buildUri(filePath)
        val key = buildKey(fileUri, line, column)
        launchRequest(
            key = key,
            jobs = definitionJobs,
            workDir = workDir,
            methodLabel = "Definition",
            request = { NativeLspService.requestDefinitionAsync(fileUri, line, column) },
            onResult = onResult
        )
    }

    fun requestReferences(
        filePath: String,
        line: Int,
        column: Int,
        includeDeclaration: Boolean,
        workDir: String?,
        onResult: (List<Location>?) -> Unit
    ) {
        val fileUri = buildUri(filePath)
        val key = buildKey(fileUri, line, column)
        launchRequest(
            key = key,
            jobs = referenceJobs,
            workDir = workDir,
            methodLabel = "References",
            request = {
                NativeLspService.requestReferencesAsync(
                    fileUri = fileUri,
                    line = line,
                    character = column,
                    includeDeclaration = includeDeclaration
                )
            },
            onResult = onResult
        )
    }

    private fun buildUri(filePath: String): String = Uri.fromFile(File(filePath)).toString()

    private fun buildKey(fileUri: String, line: Int, column: Int): String =
        "$fileUri:$line:$column"

    private fun buildIdentity(filePath: String, line: Int, column: Int): String {
        val version = NativeLspDocumentBridge.currentVersion(filePath) ?: -1
        return "$line:$column:$version"
    }

    private fun <T> submitRequest(
        workers: ConcurrentHashMap<String, RequestWorker<T>>,
        key: String,
        label: String,
        identity: String,
        blockProvider: suspend () -> T?,
        onResult: (T?) -> Unit
    ) {
        val worker = workers.computeIfAbsent(key) {
            RequestWorker(label, scope) { finished ->
                workers.remove(key, finished)
            }
        }
        worker.submit(identity, blockProvider, onResult)
    }

    private fun <T> launchRequest(
        key: String,
        jobs: ConcurrentHashMap<String, Job>,
        workDir: String?,
        methodLabel: String,
        request: suspend () -> T?,
        onResult: (T?) -> Unit
    ) {
        jobs[key]?.cancel()
        jobs[key] = scope.launch {
            try {
                Log.d(TAG, "Launching $methodLabel request key=$key workDir=$workDir")
                if (!ensureNativeClient(workDir)) {
                    Log.w(TAG, "Native client unavailable for $methodLabel key=$key")
                    withContext(Dispatchers.Main) { onResult(null) }
                    return@launch
                }
                val result = runCatching { request() }
                    .onFailure {
                        if (it !is kotlinx.coroutines.CancellationException) {
                            Log.e(TAG, "Native $methodLabel request failed", it)
                        } else {
                            Log.d(TAG, "Native $methodLabel request cancelled for $key")
                        }
                    }
                    .getOrNull()
                withContext(Dispatchers.Main) {
                    if (result == null) {
                        Log.d(TAG, "$methodLabel result is null for key=$key")
                    } else {
                        Log.d(TAG, "$methodLabel result ready for key=$key")
                    }
                    onResult(result)
                }
            } finally {
                jobs.remove(key)
            }
        }
    }

    private suspend fun ensureNativeClient(workDir: String?): Boolean {
        if (NativeLspService.nativeIsInitialized()) {
            return true
        }
        val initialized = NativeLspService.initialize(workDir = workDir ?: "/")
        if (!initialized) {
            Log.w(TAG, "NativeLspService initialize failed for workDir=$workDir")
        }
        return initialized
    }
}
