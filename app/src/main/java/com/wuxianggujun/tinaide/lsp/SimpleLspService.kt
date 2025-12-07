package com.wuxianggujun.tinaide.lsp

import android.os.Handler
import android.os.Looper
import android.util.Log
import com.wuxianggujun.tinaide.lsp.model.CompletionItem
import com.wuxianggujun.tinaide.lsp.model.CompletionResult
import com.wuxianggujun.tinaide.lsp.model.DiagnosticItem
import com.wuxianggujun.tinaide.lsp.model.HoverResult
import com.wuxianggujun.tinaide.lsp.model.Location
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.CopyOnWriteArraySet

/**
 * 简化的 LSP 服务 - 直接使用 JSON 协议
 */
object SimpleLspService {
    private const val TAG = "SimpleLspService"
    private const val DEFAULT_CLANGD_PATH = "/data/data/com.wuxianggujun.tinaide/clangd"
    private const val COMPLETION_TIMEOUT_MS = 2500L
    private const val HOVER_TIMEOUT_MS = 350L
    private const val DEFINITION_TIMEOUT_MS = 5000L
    private const val REFERENCES_TIMEOUT_MS = 10000L

    init {
        try {
            System.loadLibrary("native_compiler")
            nativeOnLoad()
            Log.i(TAG, "Native library loaded")
        } catch (e: UnsatisfiedLinkError) {
            Log.e(TAG, "Failed to load native library", e)
        }
    }

    // 监听器接口
    fun interface DiagnosticsListener {
        fun onDiagnostics(fileUri: String, diagnostics: List<DiagnosticItem>)
    }

    fun interface HealthListener {
        fun onHealthEvent(type: String, message: String)
    }

    fun interface InitializationListener {
        fun onInitializationChanged(initialized: Boolean)
    }


    private val diagnosticsListeners = CopyOnWriteArraySet<DiagnosticsListener>()
    private val healthListeners = CopyOnWriteArraySet<HealthListener>()
    private val initializationListeners = CopyOnWriteArraySet<InitializationListener>()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val restartMutex = Mutex()

    @Volatile
    private var overrideClangdPath: String? = null
    @Volatile
    private var lastWorkDir: String = "/"
    @Volatile
    private var lastClangdPath: String = DEFAULT_CLANGD_PATH

    // Native 方法
    @JvmStatic private external fun nativeOnLoad(): Int
    @JvmStatic private external fun nativeInitialize(clangdPath: String, workDir: String): Boolean
    @JvmStatic private external fun nativeShutdown()
    @JvmStatic external fun nativeIsInitialized(): Boolean
    @JvmStatic private external fun nativeRequestHover(fileUri: String, line: Int, character: Int): Long
    @JvmStatic private external fun nativeRequestCompletion(fileUri: String, line: Int, character: Int, triggerCharacter: String?): Long
    @JvmStatic private external fun nativeRequestDefinition(fileUri: String, line: Int, character: Int): Long
    @JvmStatic private external fun nativeRequestReferences(fileUri: String, line: Int, character: Int, includeDeclaration: Boolean): Long
    @JvmStatic private external fun nativeGetResult(requestId: Long): String?
    @JvmStatic private external fun nativeDidOpen(fileUri: String, content: String, languageId: String?)
    @JvmStatic private external fun nativeDidChange(fileUri: String, content: String, version: Int)
    @JvmStatic private external fun nativeDidClose(fileUri: String)
    @JvmStatic private external fun nativeCancelRequestInternal(requestId: Long)
    @JvmStatic private external fun nativeNotifyRequestTimeout(requestId: Long)

    // Native 回调
    @JvmStatic
    fun handleNativeHealthEvent(type: String, message: String) {
        Log.d(TAG, "Health event: $type - $message")
        mainHandler.post {
            healthListeners.forEach { it.onHealthEvent(type, message) }
            if (type == "INIT_FAILURE" || type == "CLANGD_EXIT" || type == "IO_ERROR") {
                initializationListeners.forEach { it.onInitializationChanged(false) }
            }
        }
        if (type == "IO_ERROR") {
            scheduleRestart(message)
        }
    }

    @JvmStatic
    fun handleNativeDiagnostics(fileUri: String, diagnostics: List<*>) {
        mainHandler.post {
            diagnosticsListeners.forEach { it.onDiagnostics(fileUri, emptyList()) }
        }
    }

    // 配置
    fun setDefaultClangdBinary(path: String?) {
        if (!path.isNullOrBlank()) {
            overrideClangdPath = path
            Log.i(TAG, "Configured clangd: $path")
        }
    }

    fun getConfiguredClangdBinary(): String? = overrideClangdPath
    fun defaultClangdBinaryPath(): String = DEFAULT_CLANGD_PATH

    // 生命周期
    fun initialize(clangdPath: String = DEFAULT_CLANGD_PATH, workDir: String = "/"): Boolean {
        val effectivePath = overrideClangdPath ?: clangdPath
        lastWorkDir = workDir
        lastClangdPath = effectivePath
        Log.i(TAG, "Initializing: clangdPath=$effectivePath, workDir=$workDir")
        val success = try {
            nativeInitialize(effectivePath, workDir)
        } catch (e: Exception) {
            Log.e(TAG, "Initialize failed", e)
            false
        }
        val initialized = nativeIsInitialized()
        mainHandler.post {
            initializationListeners.forEach { it.onInitializationChanged(initialized) }
        }
        return success || initialized
    }

    fun shutdown() {
        serviceScope.launch {
            try { nativeShutdown() } catch (e: Exception) { Log.e(TAG, "Shutdown failed", e) }
        }
    }

    // 监听器
    fun addDiagnosticsListener(listener: DiagnosticsListener) { diagnosticsListeners.add(listener) }
    fun removeDiagnosticsListener(listener: DiagnosticsListener) { diagnosticsListeners.remove(listener) }
    fun addHealthListener(listener: HealthListener) { healthListeners.add(listener) }
    fun removeHealthListener(listener: HealthListener) { healthListeners.remove(listener) }
    fun addInitializationListener(listener: InitializationListener) {
        initializationListeners.add(listener)
        mainHandler.post { listener.onInitializationChanged(nativeIsInitialized()) }
    }
    fun removeInitializationListener(listener: InitializationListener) { initializationListeners.remove(listener) }


    // 文档同步
    fun nativeDidOpenTextDocument(fileUri: String, content: String) {
        serviceScope.launch {
            try { nativeDidOpen(fileUri, content, getLanguageId(fileUri)) }
            catch (e: Exception) { Log.e(TAG, "didOpen failed", e) }
        }
    }

    fun nativeDidChangeTextDocument(fileUri: String, content: String, version: Int) {
        serviceScope.launch {
            try { nativeDidChange(fileUri, content, version) }
            catch (e: Exception) { Log.e(TAG, "didChange failed", e) }
        }
    }

    fun nativeDidCloseTextDocument(fileUri: String) {
        serviceScope.launch {
            try { nativeDidClose(fileUri) }
            catch (e: Exception) { Log.e(TAG, "didClose failed", e) }
        }
    }

    // LSP 请求
    suspend fun requestCompletionAsync(
        fileUri: String, line: Int, character: Int,
        triggerKind: Int = 1, triggerCharacter: String = "",
        timeoutOverrideMs: Long? = null
    ): CompletionResult? = withContext(Dispatchers.IO) {
        val requestId = nativeRequestCompletion(fileUri, line, character, triggerCharacter.takeIf { it.isNotEmpty() })
        if (requestId == 0L) return@withContext null
        val timeout = timeoutOverrideMs?.coerceAtLeast(350L) ?: COMPLETION_TIMEOUT_MS
        if (timeoutOverrideMs != null) {
            Log.d(TAG, "Completion request: id=$requestId using extended timeout=${timeout}ms")
        } else {
            Log.d(TAG, "Completion request: id=$requestId")
        }
        awaitJsonResult(requestId, timeout, ::parseCompletionResult)
    }

    suspend fun requestHoverAsync(fileUri: String, line: Int, character: Int): HoverResult? = withContext(Dispatchers.IO) {
        val requestId = nativeRequestHover(fileUri, line, character)
        if (requestId == 0L) return@withContext null
        awaitJsonResult(requestId, HOVER_TIMEOUT_MS, ::parseHoverResult)
    }

    suspend fun requestDefinitionAsync(fileUri: String, line: Int, character: Int): List<Location>? = withContext(Dispatchers.IO) {
        val requestId = nativeRequestDefinition(fileUri, line, character)
        if (requestId == 0L) return@withContext null
        awaitJsonResult(requestId, DEFINITION_TIMEOUT_MS, ::parseLocations)
    }

    suspend fun requestReferencesAsync(
        fileUri: String, line: Int, character: Int, includeDeclaration: Boolean = true
    ): List<Location>? = withContext(Dispatchers.IO) {
        val requestId = nativeRequestReferences(fileUri, line, character, includeDeclaration)
        if (requestId == 0L) return@withContext null
        awaitJsonResult(requestId, REFERENCES_TIMEOUT_MS, ::parseLocations)
    }

    fun cancelRequest(requestId: Long) {
        serviceScope.launch {
            safeNativeCancel(requestId)
        }
    }

    private suspend fun waitForResult(requestId: Long, timeoutMs: Long): String? {
        val ctx = currentCoroutineContext()
        val start = System.currentTimeMillis()
        while (System.currentTimeMillis() - start < timeoutMs) {
            if (!ctx.isActive) {
                return null
            }
            nativeGetResult(requestId)?.let { return it }
            delay(10)
        }
        Log.w(TAG, "Request $requestId timed out after ${timeoutMs}ms")
        notifyNativeTimeout(requestId)
        return null
    }

    private suspend fun <T> awaitJsonResult(
        requestId: Long,
        timeoutMs: Long,
        parser: (String) -> T?
    ): T? {
        if (requestId == 0L) return null
        val ctx = currentCoroutineContext()
        val cancellationHandle = ctx[Job]?.invokeOnCompletion { cause ->
            if (cause is CancellationException) {
                safeNativeCancel(requestId)
            }
        }
        return try {
            val json = waitForResult(requestId, timeoutMs)
            if (json == null) {
                if (ctx.isActive) {
                    safeNativeCancel(requestId)
                }
                null
            } else {
                parser(json)
            }
        } finally {
            cancellationHandle?.dispose()
        }
    }

    private fun safeNativeCancel(requestId: Long) {
        if (requestId == 0L) return
        runCatching { nativeCancelRequestInternal(requestId) }
            .onFailure { Log.w(TAG, "cancelRequest failed for id=$requestId", it) }
    }

    private fun notifyNativeTimeout(requestId: Long) {
        if (requestId == 0L) return
        runCatching { nativeNotifyRequestTimeout(requestId) }
            .onFailure { Log.w(TAG, "Failed to notify native timeout for id=$requestId", it) }
    }

    private fun scheduleRestart(reason: String) {
        serviceScope.launch {
            restartMutex.withLock {
                Log.w(TAG, "Restarting clangd due to: $reason")
                runCatching { nativeShutdown() }.onFailure { Log.e(TAG, "nativeShutdown failed", it) }
                // 等待更长时间确保 clangd 完全关闭
                delay(1000)
                Log.i(TAG, "Attempting to reinitialize clangd...")
                val success = initialize(lastClangdPath, lastWorkDir)
                Log.i(TAG, "Restart completed, success=$success")
                if (!success) {
                    // 如果第一次失败，再等待一段时间重试
                    delay(2000)
                    Log.i(TAG, "Retrying clangd initialization...")
                    val retrySuccess = initialize(lastClangdPath, lastWorkDir)
                    Log.i(TAG, "Retry completed, success=$retrySuccess")
                }
            }
        }
    }


    // JSON 解析
    private fun parseCompletionResult(json: String): CompletionResult? {
        return try {
            val root = JSONObject(json)
            if (root.has("error")) return null
            val result = root.optJSONObject("result") ?: return null
            if (!result.has("items")) return null
            val isIncomplete = result.optBoolean("isIncomplete", false)
            val items = mutableListOf<CompletionItem>()
            val itemsArray = result.getJSONArray("items")
            for (i in 0 until itemsArray.length()) {
                parseCompletionItem(itemsArray.getJSONObject(i))?.let { items.add(it) }
            }
            CompletionResult(items, isIncomplete)
        } catch (e: Exception) { Log.e(TAG, "Parse completion failed", e); null }
    }

    private fun parseCompletionItem(obj: JSONObject): CompletionItem? {
        return try {
            CompletionItem(
                obj.getString("label"),
                obj.optString("detail", ""),
                obj.optString("insertText", "").ifEmpty { obj.optString("label", "") },
                parseDocumentation(obj.opt("documentation")),
                obj.optInt("kind", 1),
                obj.optBoolean("deprecated", false)
            )
        } catch (e: Exception) { null }
    }

    private fun parseDocumentation(doc: Any?): String = when (doc) {
        is String -> doc
        is JSONObject -> doc.optString("value", "")
        else -> ""
    }

    private fun parseHoverResult(json: String): HoverResult? {
        return try {
            val root = JSONObject(json)
            if (root.has("error")) return null
            val result = root.optJSONObject("result") ?: return null
            val contents = when (val c = result.opt("contents")) {
                is String -> c
                is JSONObject -> c.optString("value", "")
                is JSONArray -> (0 until c.length()).joinToString("\n") {
                    when (val item = c.get(it)) {
                        is String -> item
                        is JSONObject -> item.optString("value", "")
                        else -> ""
                    }
                }
                else -> ""
            }
            val range = result.optJSONObject("range")
            HoverResult(contents,
                range?.optJSONObject("start")?.optInt("line", 0) ?: 0,
                range?.optJSONObject("start")?.optInt("character", 0) ?: 0,
                range?.optJSONObject("end")?.optInt("line", 0) ?: 0,
                range?.optJSONObject("end")?.optInt("character", 0) ?: 0)
        } catch (e: Exception) { Log.e(TAG, "Parse hover failed", e); null }
    }

    private fun parseLocations(json: String): List<Location>? {
        return try {
            val root = JSONObject(json)
            if (root.has("error")) return null
            val result = root.opt("result") ?: return null
            val locations = mutableListOf<Location>()
            when (result) {
                is JSONArray -> (0 until result.length()).mapNotNull { parseLocation(result.getJSONObject(it)) }.let { locations.addAll(it) }
                is JSONObject -> parseLocation(result)?.let { locations.add(it) }
            }
            locations
        } catch (e: Exception) { Log.e(TAG, "Parse locations failed", e); null }
    }

    private fun parseLocation(obj: JSONObject): Location? {
        return try {
            val uri = obj.getString("uri")
            val filePath = if (uri.startsWith("file://")) uri.substring(7) else uri
            val range = obj.getJSONObject("range")
            Location(filePath,
                range.getJSONObject("start").getInt("line"),
                range.getJSONObject("start").getInt("character"),
                range.getJSONObject("end").getInt("line"),
                range.getJSONObject("end").getInt("character"))
        } catch (e: Exception) { null }
    }

    private fun getLanguageId(fileUri: String): String {
        val path = if (fileUri.startsWith("file://")) fileUri.substring(7) else fileUri
        return when (File(path).extension.lowercase()) {
            "c" -> "c"
            "cpp", "cc", "cxx", "c++" -> "cpp"
            "h", "hpp", "hxx", "h++" -> "cpp"
            else -> "cpp"
        }
    }
}
