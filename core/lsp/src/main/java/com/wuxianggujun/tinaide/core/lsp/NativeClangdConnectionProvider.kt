package com.wuxianggujun.tinaide.core.lsp

import android.content.Context
import android.os.Build
import com.wuxianggujun.tinaide.core.config.ClangdSettings
import com.wuxianggujun.tinaide.core.config.Prefs
import com.wuxianggujun.tinaide.core.ndk.AndroidNativeToolchainManager
import com.wuxianggujun.tinaide.core.util.AndroidSystemLinker
import com.wuxianggujun.tinaide.core.util.NativeExecutableRunner
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import timber.log.Timber

/**
 * 通过原生 linker64 启动 clangd（不走 PRoot），并以 stdio 方式提供 LSP 连接流。
 *
 * 相比 [PRootClangdConnectionProvider]，此实现直接启动 Android 原生 ELF 二进制，
 * 使用“独立 ELF”路径（不依赖 libLLVM.so / libclang-cpp.so）。
 *
 * 优势：
 * - 零 PRoot 开销，启动更快
 * - 与标准 LSP 客户端天然兼容（stdio 通信）
 * - 复用已有的原生工具链资产（无额外体积）
 *
 * 要求：
 * - 工具链已通过 [AndroidNativeToolchainManager] 安装
 * - Android 10+（targetSdk >= 29）应优先使用 linker64（已自动处理）
 */
class NativeClangdConnectionProvider(
    context: Context,
    private val workingDir: String,
    private val compileCommandsDir: String,
    private val clangdSettings: ClangdSettings = Prefs.clangdSettingsFlow.value,
    private val toolchainId: String? = null,
    private val useRecommendedTinaExec: Boolean = false,
) : LspConnectionProvider {

    companion object {
        private const val TAG = "NativeClangd"
        private const val ANDROID_10_API_LEVEL = Build.VERSION_CODES.Q
        private const val START_PROBE_TIMEOUT_MS = 120L
    }

    private val appContext = context.applicationContext
    private val toolchainManager = AndroidNativeToolchainManager(appContext)

    @Volatile
    private var process: Process? = null

    @Volatile
    private var stderrThread: Thread? = null

    @Volatile
    private var stopping = false

    override fun start() {
        // 1. 解析 clangd 二进制路径
        val toolchainBinDir = toolchainManager.getBinDir(toolchainId)
        val clangdBinary = File(toolchainBinDir, "clangd")
        if (!clangdBinary.isFile) {
            throw IllegalStateException("clangd binary not found: ${clangdBinary.absolutePath}. Please ensure native toolchain is installed.")
        }

        require(compileCommandsDir.isNotBlank()) { "compileCommandsDir must not be blank" }
        if (CompileCommandsDebugLogger.isClangdStartupEnabled()) {
            CompileCommandsDebugLogger.logClangdStartupSummary(
                TAG,
                "native-clangd-start",
                File(compileCommandsDir, "compile_commands.json")
            )
        }

        // 3. 构建命令行参数
        val args = buildClangdArgs()

        // 4. 解析 linker64 路径
        val linker64 = AndroidSystemLinker.resolve64BitPreferred()

        // 5. 构建完整命令：
        // - Android 10+（targetSdk >= 29）：优先 linker64，规避 app 私有目录 direct exec 的 W^X 限制。
        // - 其余场景：优先 direct，兼顾启动开销与历史兼容。
        // - 另一条路径仍保留为兜底，降低 ROM 差异带来的启动失败风险。
        val directCommand = mutableListOf(clangdBinary.absolutePath).apply { addAll(args) }
        val linkerCommand = mutableListOf(linker64, clangdBinary.absolutePath).apply { addAll(args) }

        Timber.tag(TAG).i("Starting native clangd: cwd=$workingDir")
        Timber.tag(TAG).i("Using compile_commands.json from: $compileCommandsDir")
        Timber.tag(TAG).i(
            "Native clangd toolchain: id=%s, bin=%s, tinaExec=%s",
            toolchainId ?: "active",
            toolchainBinDir.absolutePath,
            if (useRecommendedTinaExec) "enabled" else "disabled"
        )

        stopping = false

        data class LaunchAttempt(
            val name: String,
            val command: List<String>
        )

        val targetSdk = appContext.applicationInfo.targetSdkVersion
        val preferLinker64First = Build.VERSION.SDK_INT >= ANDROID_10_API_LEVEL && targetSdk >= ANDROID_10_API_LEVEL
        val attempts = buildList {
            if (preferLinker64First) {
                add(LaunchAttempt(name = "linker64", command = linkerCommand))
                add(LaunchAttempt(name = "direct", command = directCommand))
            } else {
                add(LaunchAttempt(name = "direct", command = directCommand))
                add(LaunchAttempt(name = "linker64", command = linkerCommand))
            }
        }
        Timber.tag(TAG).i(
            "Launch preference: sdk=%d, targetSdk=%d, firstAttempt=%s",
            Build.VERSION.SDK_INT,
            targetSdk,
            attempts.firstOrNull()?.name ?: "unknown"
        )

        // 6. 启动进程
        fun startProcess(attempt: LaunchAttempt): Process {
            Timber.tag(TAG).d("Command[%s]: %s", attempt.name, attempt.command.joinToString(" "))
            return ProcessBuilder(attempt.command).apply {
                directory(File(workingDir))
                NativeExecutableRunner.configureEnvironment(
                    builder = this,
                    nativeLibDir = appContext.applicationInfo.nativeLibraryDir,
                    toolchainBinDir = toolchainBinDir.absolutePath,
                    tmpDir = appContext.cacheDir.absolutePath,
                    homeDir = appContext.filesDir.absolutePath,
                    llvmWrapExecLinker64 = attempt.name == "linker64",
                )
                if (useRecommendedTinaExec) {
                    NativeExecutableRunner.applyRecommendedTinaExec(
                        environment = environment(),
                        context = appContext,
                        fullCommand = attempt.command,
                    )
                }
            }.start()
        }

        fun startAndProbe(attempt: LaunchAttempt): Process {
            val p = startProcess(attempt)
            // 避免“进程可启动但瞬间退出”导致误判成功（常见于 ELF 依赖缺失）
            val exitedImmediately = p.waitFor(START_PROBE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            if (!exitedImmediately) return p

            val code = runCatching { p.exitValue() }.getOrDefault(-1)
            val stderrPreview = runCatching {
                p.errorStream.bufferedReader().use { it.readText() }.trim()
            }.getOrDefault("")
            throw IOException(
                buildString {
                    append("clangd exited immediately (attempt=${attempt.name}, code=$code)")
                    if (stderrPreview.isNotBlank()) {
                        append(": ")
                        append(stderrPreview)
                    }
                }
            )
        }

        var started: Process? = null
        var lastError: Throwable? = null
        for (attempt in attempts) {
            val r = runCatching { startAndProbe(attempt) }
            if (r.isSuccess) {
                started = r.getOrNull()
                Timber.tag(TAG).i("Native clangd started via %s", attempt.name)
                break
            }
            val e = r.exceptionOrNull()
            lastError = e
            Timber.tag(TAG).w(e, "Failed to start clangd via %s", attempt.name)
        }

        process = started ?: throw IllegalStateException(
            "Failed to start native clangd with all launch attempts",
            lastError
        )

        // 7. 消费 stderr（避免缓冲区打满导致阻塞）
        val p = process ?: return
        stderrThread = thread(
            name = "clangd-stderr-native",
            isDaemon = true
        ) {
            runCatching {
                p.errorStream.bufferedReader().useLines { lines ->
                    for (line in lines) {
                        if (stopping) break
                        if (line.isNotBlank()) {
                            Timber.tag(TAG).d("stderr: $line")
                        }
                    }
                }
            }.onFailure { e ->
                if (!stopping) {
                    Timber.tag(TAG).d("stderr reader stopped: ${e.message}")
                }
            }
        }
    }

    /**
     * 构建 clangd 命令行参数
     */
    private fun buildClangdArgs(): List<String> {
        val args = mutableListOf<String>()
        args.add("--compile-commands-dir=$compileCommandsDir")

        // 从配置构建参数（ClangdSettings.buildCommandArgs() 返回的是空格分隔的字符串）
        val settingsArgs = clangdSettings.buildCommandArgs().trim()
        if (settingsArgs.isNotEmpty()) {
            // 简单分割（假设参数中没有引号/空格复杂情况）
            args.addAll(settingsArgs.split(Regex("\\s+")))
        }

        return args
    }

    override val inputStream: InputStream
        get() = requireNotNull(process) { "clangd process not started" }.inputStream

    override val outputStream: OutputStream
        get() = requireNotNull(process) { "clangd process not started" }.outputStream

    override fun close() {
        stopping = true
        runCatching { stderrThread?.interrupt() }
        stderrThread = null
        runCatching { process?.destroy() }
        process = null
    }
}
