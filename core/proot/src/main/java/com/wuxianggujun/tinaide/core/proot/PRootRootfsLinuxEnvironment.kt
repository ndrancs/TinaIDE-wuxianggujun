package com.wuxianggujun.tinaide.core.proot

import android.content.Context
import com.wuxianggujun.tinaide.core.linux.LinuxEnvironment
import com.wuxianggujun.tinaide.core.linux.LinuxExecutionResult
import com.wuxianggujun.tinaide.core.linux.LinuxInteractiveProcess

internal class PRootRootfsLinuxEnvironment(
    context: Context,
    rootfsPath: String,
) : LinuxEnvironment {
    private val manager = PRootManager(context.applicationContext, rootfsPath)

    override fun isAvailable(): Boolean = manager.isInstalled()

    override suspend fun execute(
        command: List<String>,
        workDir: String,
        env: Map<String, String>,
        timeout: Long?,
        stdin: String?,
    ): LinuxExecutionResult {
        val result = manager.execute(
            command = command,
            workDir = workDir,
            env = env,
            timeout = timeout,
            stdin = stdin,
        )
        return LinuxExecutionResult(
            exitCode = result.exitCode,
            stdout = result.stdout,
            stderr = result.stderr,
            durationMs = result.durationMs,
            timedOut = result.timedOut,
        )
    }

    override fun startInteractive(
        command: List<String>,
        workDir: String,
        env: Map<String, String>,
    ): LinuxInteractiveProcess = PRootInteractiveAdapter(
        manager.startInteractive(
            command = command,
            workDir = workDir,
            extraEnv = env,
        )
    )

    override fun toGuestPath(hostPath: String): String = manager.toGuestPath(hostPath)
}
