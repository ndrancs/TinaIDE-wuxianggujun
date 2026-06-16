package com.wuxianggujun.tinaide.core.linux

import java.io.InputStream
import java.io.OutputStream

/**
 * Linux 命令执行结果（抽象层，不绑定具体实现）。
 */
data class LinuxExecutionResult(
    val exitCode: Int,
    val stdout: String,
    val stderr: String,
    val durationMs: Long,
    val timedOut: Boolean = false,
) {
    val isSuccess: Boolean
        get() = exitCode == 0 && !timedOut

    val combinedOutput: String
        get() = buildString {
            if (stdout.isNotEmpty()) append(stdout)
            if (stderr.isNotEmpty()) {
                if (isNotEmpty()) appendLine()
                append(stderr)
            }
        }
}

/**
 * Linux 交互进程抽象（用于 LSP/终端等长连接场景）。
 */
interface LinuxInteractiveProcess {
    val stdin: OutputStream
    val stdout: InputStream
    val stderr: InputStream

    fun isRunning(): Boolean
    fun waitFor(timeout: Long = 0): Int
    fun destroy()
}

/**
 * Linux 环境抽象层。
 *
 * 目标：让业务层不直接依赖具体实现（如 PRoot）。
 */
interface LinuxEnvironment {
    /**
     * 当前环境是否可用。
     */
    fun isAvailable(): Boolean

    /**
     * 在 Linux 环境中执行命令。
     */
    suspend fun execute(
        command: List<String>,
        workDir: String = "/",
        env: Map<String, String> = emptyMap(),
        timeout: Long? = 60_000,
        stdin: String? = null,
    ): LinuxExecutionResult

    /**
     * 以交互模式启动进程。
     */
    fun startInteractive(
        command: List<String>,
        workDir: String = "/",
        env: Map<String, String> = emptyMap(),
    ): LinuxInteractiveProcess

    /**
     * 将宿主路径映射为 guest 路径。
     */
    fun toGuestPath(hostPath: String): String
}
