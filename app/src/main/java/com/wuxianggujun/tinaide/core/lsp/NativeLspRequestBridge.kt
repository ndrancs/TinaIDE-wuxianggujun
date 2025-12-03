package com.wuxianggujun.tinaide.core.lsp

import android.net.Uri
import android.util.Log
import com.wuxianggujun.tinaide.lsp.NativeLspService
import com.wuxianggujun.tinaide.lsp.model.HoverResult
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
 * 面向编辑器的 Native LSP 请求桥接层，目前支持 Hover。
 */
object NativeLspRequestBridge {

    private const val TAG = "NativeLspRequestBridge"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val hoverJobs = ConcurrentHashMap<String, Job>()

    fun requestHover(
        filePath: String,
        line: Int,
        column: Int,
        workDir: String?,
        onResult: (HoverResult?) -> Unit
    ) {
        val fileUri = Uri.fromFile(File(filePath)).toString()
        val key = "$fileUri:$line:$column"
        hoverJobs[key]?.cancel()
        hoverJobs[key] = scope.launch {
            try {
                if (!ensureNativeClient(workDir)) {
                    withContext(Dispatchers.Main) { onResult(null) }
                    return@launch
                }
                delay(150)
                val result = runCatching {
                    NativeLspService.requestHoverAsync(fileUri, line, column)
                }.onFailure {
                    if (it !is kotlinx.coroutines.CancellationException) {
                        Log.e(TAG, "Native hover request failed", it)
                    } else {
                        Log.d(TAG, "Hover job cancelled for $fileUri:$line:$column")
                    }
                }.getOrNull()
                withContext(Dispatchers.Main) {
                    onResult(result)
                }
            } finally {
                hoverJobs.remove(key)
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
