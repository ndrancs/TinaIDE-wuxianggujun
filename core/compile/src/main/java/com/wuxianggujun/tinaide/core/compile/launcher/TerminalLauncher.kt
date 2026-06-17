package com.wuxianggujun.tinaide.core.compile.launcher

import com.wuxianggujun.tinaide.core.compile.artifact.Artifact
import com.wuxianggujun.tinaide.core.compile.artifact.ArtifactKind
import com.wuxianggujun.tinaide.core.compile.event.BuildEvent
import com.wuxianggujun.tinaide.core.compile.event.BuildEventEmitter
import com.wuxianggujun.tinaide.core.compile.strategy.BuildContext
import com.wuxianggujun.tinaide.core.i18n.Strings
import com.wuxianggujun.tinaide.core.i18n.strOr
import java.io.File
import timber.log.Timber

/**
 * 终端启动器:准备在终端中运行产物的描述符。
 *
 * 校验产物可达,返回 [LaunchDescriptor.Terminal]。
 * UI 层(CompileActionsHelper + TerminalCommandBuilder)拿到 descriptor 后
 * 组装带 LD_LIBRARY_PATH / staged copy / wait-for-enter 的完整 shell 命令,
 * 交给 TerminalBackend 打开终端会话。
 */
class TerminalLauncher : Launcher {

    companion object {
        private const val TAG = "TerminalLauncher"
    }

    override suspend fun launch(
        artifact: Artifact,
        ctx: BuildContext,
        emitter: BuildEventEmitter,
    ): LaunchOutcome = launchWithWorkingDir(artifact, workingDir = null, ctx = ctx, emitter = emitter)

    suspend fun launchWithWorkingDir(
        artifact: Artifact,
        workingDir: File?,
        ctx: BuildContext,
        emitter: BuildEventEmitter,
    ): LaunchOutcome {
        val file = File(artifact.absolutePath)
        if (!file.isFile) {
            val reason = "terminal artifact not found: ${artifact.absolutePath}"
            emitter.emit(BuildEvent.Launch.Failed(reason, wasArtifactCached = false))
            return LaunchOutcome.Failed(reason)
        }
        if (artifact.kind != ArtifactKind.EXECUTABLE) {
            val reason = Strings.terminal_runtime_invalid_executable_artifact.strOr(
                ctx.appContext,
                artifact.absolutePath,
                artifact.kind.name,
            )
            emitter.emit(BuildEvent.Launch.Failed(reason, wasArtifactCached = false))
            return LaunchOutcome.Failed(reason)
        }
        val cwd = workingDir?.takeIf { it.isDirectory } ?: file.parentFile ?: ctx.projectRoot
        Timber.tag(TAG).d("terminal launch prepared: %s in %s", file.absolutePath, cwd.absolutePath)
        emitter.emit(BuildEvent.Launch.Completed)
        return LaunchOutcome.Prepared(
            LaunchDescriptor.Terminal(
                artifact = artifact,
                runnablePath = artifact.absolutePath,
                workingDir = cwd,
            )
        )
    }
}
