package com.wuxianggujun.tinaide.core.linux

/**
 * Linux 环境不可用占位实现。
 */
object UnavailableLinuxEnvironment : LinuxEnvironment {

    override fun isAvailable(): Boolean = false

    override suspend fun execute(
        command: List<String>,
        workDir: String,
        env: Map<String, String>,
        timeout: Long?,
        stdin: String?,
    ): LinuxExecutionResult = LinuxExecutionResult(
        exitCode = -1,
        stdout = "",
        stderr = "Linux environment is unavailable",
        durationMs = 0L,
        timedOut = false,
    )

    override fun startInteractive(
        command: List<String>,
        workDir: String,
        env: Map<String, String>,
    ): LinuxInteractiveProcess = throw IllegalStateException("Linux environment is unavailable")

    override fun toGuestPath(hostPath: String): String = hostPath
}
