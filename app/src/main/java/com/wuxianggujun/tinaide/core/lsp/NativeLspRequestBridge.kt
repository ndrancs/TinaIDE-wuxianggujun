package com.wuxianggujun.tinaide.core.lsp

import android.net.Uri
import android.os.SystemClock
import android.util.Log
import com.wuxianggujun.tinaide.lsp.NativeLspService
import com.wuxianggujun.tinaide.lsp.model.CompletionResult
import com.wuxianggujun.tinaide.lsp.model.HoverResult
import com.wuxianggujun.tinaide.lsp.model.Location
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Native LSP 请求调度桥接层：
 * - 同一文件复用单个调度通道，串行发送 Hover / Completion，避免 clangd 同时处理两种请求。
 * - Completion 拥有更高优先级，Hover 仅在光标真正移动后才触发。
 */
object NativeLspRequestBridge {

    private const val TAG = "NativeLspRequestBridge"
    private const val MIN_HOVER_INTERVAL_MS = 120L
    private const val HOVER_COMPLETION_SUPPRESS_MS = 800L
    private const val HOVER_TYPING_COOLDOWN_MS = 600L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val requestChannels = ConcurrentHashMap<String, FileRequestChannel>()
    private val hoverLimiter = HoverRateLimiter(MIN_HOVER_INTERVAL_MS)
    private val completionActivity = ConcurrentHashMap<String, AtomicLong>()
    private val typingActivity = ConcurrentHashMap<String, AtomicLong>()
    private val definitionJobs = ConcurrentHashMap<String, Job>()
    private val referenceJobs = ConcurrentHashMap<String, Job>()

    private sealed class RequestType<T>(val label: String, val priority: Int) {
        object Hover : RequestType<HoverResult?>("Hover", priority = 1)
        object Completion : RequestType<CompletionResult?>("Completion", priority = 2)
    }

    private class MethodTask<T>(
        val type: RequestType<T>,
        val identity: String,
        private val label: String,
        val priority: Int,
        val sequence: Long,
        private val block: suspend () -> T?,
        initialCallback: (T?) -> Unit
    ) {
        private val callbacks = mutableListOf(initialCallback)

        fun matches(otherType: RequestType<*>, otherIdentity: String): Boolean {
            return type == otherType && identity == otherIdentity
        }

        fun addCallback(callback: (T?) -> Unit) {
            callbacks.add(callback)
        }

        fun markSkipped() {
            Log.d(TAG, "$label request skipped identity=$identity")
        }

        private fun snapshotCallbacks(): List<(T?) -> Unit> {
            return callbacks.toList().also { callbacks.clear() }
        }

        suspend fun run() {
            val result = runCatching { block() }
                .onFailure { error ->
                    Log.e(TAG, "$label request failed identity=$identity", error)
                }
                .getOrNull()
            val snapshot = snapshotCallbacks()
            withContext(Dispatchers.Main) {
                snapshot.forEach { callback -> runCatching { callback(result) } }
            }
        }
    }

    private class FileRequestChannel(
        private val key: String,
        private val scope: CoroutineScope,
        private val onIdle: (String, FileRequestChannel) -> Unit
    ) {
        private var job: Job? = null
        private var currentTask: MethodTask<*>? = null
        private val pendingTasks = HashMap<RequestType<*>, MethodTask<*>>()
        private val sequence = AtomicLong(0)

        fun cancelPending(type: RequestType<*>) {
            val removed = synchronized(this) {
                pendingTasks.remove(type)
            }
            removed?.markSkipped()
        }

        fun <T> submit(
            type: RequestType<T>,
            identity: String,
            block: suspend () -> T?,
            callback: (T?) -> Unit
        ) {
            var replacedTask: MethodTask<*>? = null
            var appended = false
            var needsStart = false

            synchronized(this) {
                when {
                    currentTask?.matches(type, identity) == true -> {
                        @Suppress("UNCHECKED_CAST")
                        (currentTask as MethodTask<T>).addCallback(callback)
                        appended = true
                    }
                    pendingTasks[type]?.identity == identity -> {
                        @Suppress("UNCHECKED_CAST")
                        (pendingTasks[type] as MethodTask<T>).addCallback(callback)
                        appended = true
                    }
                    else -> {
                        val task = MethodTask(
                            type = type,
                            identity = identity,
                            label = type.label,
                            priority = type.priority,
                            sequence = sequence.incrementAndGet(),
                            block = block,
                            initialCallback = callback
                        )
                        replacedTask = pendingTasks.put(type, task)
                        if (job == null) {
                            needsStart = true
                        }
                    }
                }
            }

            if (appended) {
                return
            }
            replacedTask?.markSkipped()
            if (needsStart) {
                startLoop()
            }
        }

        private fun startLoop() {
            synchronized(this) {
                if (job == null) {
                    job = scope.launch { runLoop() }
                }
            }
        }

        fun hasActiveOrPending(type: RequestType<*>): Boolean {
            synchronized(this) {
                if (currentTask?.type == type) {
                    return true
                }
                return pendingTasks.containsKey(type)
            }
        }

        private suspend fun runLoop() {
            while (true) {
                val next = synchronized(this) {
                    val candidate = pendingTasks.values.maxWithOrNull(TASK_COMPARATOR)
                    if (candidate == null) {
                        currentTask = null
                        job = null
                        null
                    } else {
                        pendingTasks.remove(candidate.type)
                        currentTask = candidate
                        candidate
                    }
                } ?: break
                next.run()
            }
            onIdle(key, this)
        }

        companion object {
            private val TASK_COMPARATOR = Comparator<MethodTask<*>> { a, b ->
                when {
                    a.priority != b.priority -> b.priority - a.priority
                    a.sequence == b.sequence -> 0
                    a.sequence < b.sequence -> 1
                    else -> -1
                }
            }
        }
    }

    private class HoverRateLimiter(private val minIntervalMs: Long) {
        private data class HoverState(var signature: String, var timestamp: Long)

        private val states = ConcurrentHashMap<String, HoverState>()

        fun shouldAllow(key: String, caretSignature: String): Boolean {
            val now = SystemClock.elapsedRealtime()
            val state = states[key]
            if (state == null || state.signature != caretSignature || now - state.timestamp >= minIntervalMs) {
                states[key] = HoverState(caretSignature, now)
                return true
            }
            return false
        }
    }

    fun notifyDocumentChange(filePath: String) {
        val fileUri = buildUri(filePath)
        val now = SystemClock.elapsedRealtime()
        typingActivity.computeIfAbsent(fileUri) { AtomicLong(now) }.set(now)
    }

    fun requestHover(
        filePath: String,
        line: Int,
        column: Int,
        workDir: String?,
        onResult: (HoverResult?) -> Unit
    ) {
        val fileUri = buildUri(filePath)
        val caretSignature = buildCaretSignature(line, column)
        if (shouldSuppressHover(fileUri, caretSignature)) {
            return
        }
        val identity = buildIdentity(filePath, line, column)
        if (!hoverLimiter.shouldAllow(fileUri, caretSignature)) {
            Log.d(TAG, "Hover throttled key=$fileUri caret=$caretSignature")
            return
        }
        submitToChannel(
            fileUri = fileUri,
            type = RequestType.Hover,
            identity = identity,
            blockProvider = {
                if (!ensureNativeClient(workDir)) return@submitToChannel null
                NativeLspService.requestHoverAsync(fileUri, line, column).also { result ->
                    if (result == null) {
                        Log.d(TAG, "Hover result null (timeout/cancel) uri=$fileUri pos=$line:$column")
                    }
                }
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
        markCompletionActivity(fileUri)
        submitToChannel(
            fileUri = fileUri,
            type = RequestType.Completion,
            identity = buildIdentity(filePath, line, column),
            blockProvider = {
                if (!ensureNativeClient(workDir)) return@submitToChannel null
                NativeLspDocumentBridge.flushPendingSync(filePath)
                NativeLspService.requestCompletionAsync(fileUri, line, column).also { result ->
                    if (result == null) {
                        Log.w(TAG, "Completion result null (timeout/cancel) uri=$fileUri pos=$line:$column")
                    }
                }
            },
            onResult = onResult,
            dropPendingHover = true
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

    private fun buildKey(fileUri: String, line: Int, column: Int): String = "$fileUri:$line:$column"

    private fun buildCaretSignature(line: Int, column: Int): String = "$line:$column"

    private fun buildIdentity(filePath: String, line: Int, column: Int): String {
        val version = NativeLspDocumentBridge.currentVersion(filePath) ?: -1
        return "$line:$column:$version"
    }

    private fun shouldSuppressHover(fileUri: String, caretSignature: String): Boolean {
        val channel = requestChannels[fileUri]
        val hasCompletionInFlight = channel?.hasActiveOrPending(RequestType.Completion) == true
        if (hasCompletionInFlight) {
            Log.d(TAG, "Hover suppressed due to active completion key=$fileUri caret=$caretSignature")
            return true
        }
        val lastActivity = completionActivity[fileUri]?.get() ?: 0L
        if (lastActivity == 0L) {
            val now = SystemClock.elapsedRealtime()
            val lastTyping = typingActivity[fileUri]?.get() ?: 0L
            if (lastTyping != 0L && now - lastTyping < HOVER_TYPING_COOLDOWN_MS) {
                Log.d(TAG, "Hover suppressed due to typing cooldown key=$fileUri caret=$caretSignature")
                return true
            }
            return false
        }
        val now = SystemClock.elapsedRealtime()
        if (now - lastActivity < HOVER_COMPLETION_SUPPRESS_MS) {
            Log.d(TAG, "Hover suppressed due to recent completion key=$fileUri caret=$caretSignature")
            return true
        }
        val lastTyping = typingActivity[fileUri]?.get() ?: 0L
        if (lastTyping != 0L && now - lastTyping < HOVER_TYPING_COOLDOWN_MS) {
            Log.d(TAG, "Hover suppressed due to typing cooldown key=$fileUri caret=$caretSignature")
            return true
        }
        return false
    }

    private fun markCompletionActivity(fileUri: String) {
        val now = SystemClock.elapsedRealtime()
        completionActivity.computeIfAbsent(fileUri) { AtomicLong(now) }.set(now)
    }

    private fun <T> submitToChannel(
        fileUri: String,
        type: RequestType<T>,
        identity: String,
        blockProvider: suspend () -> T?,
        onResult: (T?) -> Unit,
        dropPendingHover: Boolean = false
    ) {
        val channel = requestChannels.computeIfAbsent(fileUri) { key ->
            FileRequestChannel(key, scope) { releasedKey, worker ->
                requestChannels.remove(releasedKey, worker)
            }
        }
        if (dropPendingHover) {
            channel.cancelPending(RequestType.Hover)
        }
        channel.submit(type, identity, blockProvider, onResult)
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
