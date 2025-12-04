package com.wuxianggujun.tinaide.core.lsp

import android.net.Uri
import android.util.Log
import com.wuxianggujun.tinaide.lsp.NativeLspService
import com.wuxianggujun.tinaide.lsp.model.CompletionResult
import com.wuxianggujun.tinaide.lsp.model.HoverResult
import com.wuxianggujun.tinaide.lsp.model.Location
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * 面向编辑器的 Native LSP 请求桥接层。
 *
 * 当前支持 Hover / Completion / Definition / References 四种请求，并在内部确保
 * NativeLspService 已初始化、请求串行化以及取消后释放资源。
 */
object NativeLspRequestBridge {

    private const val TAG = "NativeLspRequestBridge"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val hoverJobs = ConcurrentHashMap<String, Job>()
    private val completionJobs = ConcurrentHashMap<String, Job>()
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
        val key = buildKey(fileUri, line, column)
        launchRequest(
            key = key,
            jobs = hoverJobs,
            workDir = workDir,
            methodLabel = "Hover",
            delayMs = 150,
            request = { NativeLspService.requestHoverAsync(fileUri, line, column) },
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
        val key = buildKey(fileUri, line, column)
        launchRequest(
            key = key,
            jobs = completionJobs,
            workDir = workDir,
            methodLabel = "Completion",
            request = { NativeLspService.requestCompletionAsync(fileUri, line, column) },
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

    private fun <T> launchRequest(
        key: String,
        jobs: ConcurrentHashMap<String, Job>,
        workDir: String?,
        methodLabel: String,
        delayMs: Long = 0L,
        request: suspend () -> T?,
        onResult: (T?) -> Unit
    ) {
        jobs[key]?.cancel()
        jobs[key] = scope.launch {
            try {
                if (!ensureNativeClient(workDir)) {
                    withContext(Dispatchers.Main) { onResult(null) }
                    return@launch
                }
                if (delayMs > 0) {
                    delay(delayMs)
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
