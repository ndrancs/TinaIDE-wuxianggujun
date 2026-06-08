package com.wuxianggujun.tinaide.core.debug

import android.content.Context
import com.wuxianggujun.tinaide.core.proot.InteractiveProcess
import com.wuxianggujun.tinaide.core.proot.PRootManager
import com.wuxianggujun.tinaide.core.proot.ToolchainPathResolver
import java.io.Closeable
import java.io.InputStream
import java.nio.charset.StandardCharsets
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import timber.log.Timber

/**
 * 通过 PRoot 启动 LLDB 并进行 stdin/stdout 交互。
 *
 * 说明：
 * - LLDB 的 prompt 通常不会带换行，因此不能依赖 readLine()。
 * - 这里使用字节流读取并检测 "(lldb) " prompt 来分割响应。
 */
class PRootDebugger(
    context: Context,
    private val prootManager: PRootManager
) : Closeable {
    companion object {
        private val envNamePattern = Regex("[A-Za-z_][A-Za-z0-9_]*")
    }

    private val pathResolver = ToolchainPathResolver(context)

    data class OutputChunk(
        val text: String,
        val isStderr: Boolean
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val promptVariants = listOf("(lldb) ", "(lldb)")
    private val maxPromptLen = promptVariants.maxOf { it.length }
    private val captureLock = Any()
    private val commandMutex = Mutex()

    private var process: InteractiveProcess? = null
    private var startupPromptSeen: CompletableDeferred<Unit>? = null
    private var pendingResponse: CompletableDeferred<String>? = null
    private var captureBuffer: StringBuilder? = null
    private var promptCarry = ""

    private var stdoutJobStarted = false
    private var stderrJobStarted = false

    private val _output = kotlinx.coroutines.flow.MutableSharedFlow<OutputChunk>(
        extraBufferCapacity = 256
    )
    val output: kotlinx.coroutines.flow.SharedFlow<OutputChunk> = _output

    suspend fun start(
        programPath: String,
        arguments: List<String> = emptyList(),
        workingDirectory: String,
        environment: Map<String, String> = emptyMap(),
    ): Result<Unit> = try {
        commandMutex.withLock {
            check(process == null) { "Debugger already started" }

            startupPromptSeen = CompletableDeferred()

            val command = buildList {
                add(pathResolver.getLldb())
                add("--no-use-colors")
                add("--no-lldbinit")
                add(programPath)
                if (arguments.isNotEmpty()) {
                    add("--")
                    addAll(arguments)
                }
            }

            process = prootManager.startInteractive(
                command = command,
                workDir = workingDirectory,
                extraEnv = environment.filterKeys { key -> envNamePattern.matches(key) },
            )

            startPumpsIfNeeded(process!!)

            withTimeout(15_000) {
                startupPromptSeen?.await()
            }
        }

        // 注意：不能在持有 Mutex 时再次调用 sendCommand（Mutex 非可重入）
        sendCommand("settings set auto-confirm true").getOrThrow()
        sendCommand("settings set stop-line-count-before 0").getOrThrow()
        sendCommand("settings set stop-line-count-after 0").getOrThrow()

        Result.success(Unit)
    } catch (t: Throwable) {
        safeDestroy()
        Result.failure(t)
    }

    suspend fun sendCommand(
        command: String,
        timeoutMs: Long = 10_000
    ): Result<String> = commandMutex.withLock {
        runCatching { sendCommandUnlocked(command, timeoutMs) }
            .fold(onSuccess = { Result.success(it) }, onFailure = { Result.failure(it) })
    }

    suspend fun stop(): Result<Unit> = commandMutex.withLock {
        val p = process ?: return Result.success(Unit)
        if (!p.isRunning()) {
            safeDestroy()
            return Result.success(Unit)
        }

        // 尝试优雅退出；失败则强杀进程
        withTimeoutOrNull(2_000) { runCatching { sendCommandUnlocked("process kill", timeoutMs = 2_000) } }
        withTimeoutOrNull(2_000) { runCatching { sendCommandUnlocked("quit", timeoutMs = 2_000) } }
        safeDestroy()
        Result.success(Unit)
    }

    override fun close() {
        safeDestroy()
    }

    private fun startPumpsIfNeeded(p: InteractiveProcess) {
        if (!stdoutJobStarted) {
            stdoutJobStarted = true
            scope.launch { pump(p.stdout, isStderr = false) }
        }
        if (!stderrJobStarted) {
            stderrJobStarted = true
            scope.launch { pump(p.stderr, isStderr = true) }
        }
    }

    private suspend fun pump(stream: InputStream, isStderr: Boolean) {
        val buf = ByteArray(4096)
        while (true) {
            val n = try {
                stream.read(buf)
            } catch (_: Throwable) {
                -1
            }
            if (n <= 0) break

            val text = String(buf, 0, n, StandardCharsets.UTF_8)
            handleChunk(text, isStderr)
        }
    }

    private fun handleChunk(rawText: String, isStderr: Boolean) {
        val text = if (isStderr) rawText else stripPromptForUi(rawText)
        _output.tryEmit(OutputChunk(text = text, isStderr = isStderr))

        // stderr 不参与 prompt 分割，但需要参与“当前命令输出”的收集
        if (isStderr) {
            synchronized(captureLock) {
                captureBuffer?.append(rawText)
            }
            return
        }

        // stdout：既要收集，也要检测 prompt
        synchronized(captureLock) {
            // 1) 记录到命令捕获 buffer（如果存在）
            captureBuffer?.append(rawText)

            // 2) prompt 检测：考虑 prompt 跨 chunk 的情况
            val combined = promptCarry + rawText
            if (combined.isEmpty()) return

            var searchStart = 0
            while (true) {
                val match = findNextPrompt(combined, searchStart) ?: break
                val idx = match.first
                val len = match.second

                // 启动 prompt
                startupPromptSeen?.let { starter ->
                    if (!starter.isCompleted) starter.complete(Unit)
                }

                // 命令响应 prompt
                pendingResponse?.let { resp ->
                    if (!resp.isCompleted) {
                        // response 内容来自 captureBuffer（更完整，含 stderr）
                        val responseText = captureBuffer?.toString().orEmpty()
                        resp.complete(responseText)
                    }
                    pendingResponse = null
                    captureBuffer = null
                }

                searchStart = idx + len
            }

            // carry 需要覆盖最长 prompt 的跨 chunk 情况
            promptCarry = combined.takeLast(maxPromptLen - 1)
        }
    }

    private suspend fun sendCommandUnlocked(command: String, timeoutMs: Long): String {
        val p = process ?: throw IllegalStateException("Debugger not started")
        if (!p.isRunning()) throw IllegalStateException("Debugger not running")

        val deferred = CompletableDeferred<String>()
        synchronized(captureLock) {
            pendingResponse = deferred
            captureBuffer = StringBuilder()
        }

        try {
            p.stdin.write((command + "\n").toByteArray(StandardCharsets.UTF_8))
            p.stdin.flush()
            return withTimeout(timeoutMs) { deferred.await() }
        } catch (t: Throwable) {
            synchronized(captureLock) {
                if (pendingResponse === deferred) {
                    pendingResponse = null
                    captureBuffer = null
                }
            }
            throw t
        }
    }

    private fun stripPromptForUi(text: String): String {
        var out = text
        // 先移除更长的 prompt，避免 "(lldb)" 抢先匹配导致残留空格
        promptVariants.sortedByDescending { it.length }.forEach { p ->
            out = out.replace(p, "")
        }
        return out
    }

    private fun findNextPrompt(text: String, startIndex: Int): Pair<Int, Int>? {
        var bestIndex = -1
        var bestLen = 0
        for (p in promptVariants) {
            val idx = text.indexOf(p, startIndex = startIndex)
            if (idx < 0) continue
            if (bestIndex < 0 || idx < bestIndex || (idx == bestIndex && p.length > bestLen)) {
                bestIndex = idx
                bestLen = p.length
            }
        }
        return if (bestIndex >= 0) bestIndex to bestLen else null
    }

    private fun safeDestroy() {
        process?.destroy()
        process = null
        pendingResponse = null
        captureBuffer = null
        startupPromptSeen = null
        try {
            scope.cancel()
        } catch (t: Throwable) {
            Timber.d(t, "PRootDebugger: scope.cancel() in safeDestroy")
        }
    }
}
