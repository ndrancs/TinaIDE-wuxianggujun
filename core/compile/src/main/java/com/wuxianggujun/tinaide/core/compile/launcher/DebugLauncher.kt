package com.wuxianggujun.tinaide.core.compile.launcher

import com.wuxianggujun.tinaide.core.compile.artifact.Artifact
import com.wuxianggujun.tinaide.core.compile.event.BuildEvent
import com.wuxianggujun.tinaide.core.compile.event.BuildEventEmitter
import com.wuxianggujun.tinaide.core.compile.strategy.BuildContext
import java.io.File
import timber.log.Timber

/**
 * 调试启动器:准备 gdb/lldb 挂接所需的描述符。
 *
 * 校验产物可达,返回 [LaunchDescriptor.Debug]。
 * UI 层(CompileActionsHelper.uiBridge.startDebugSession)负责实际启动 gdbserver。
 *
 * 若产物 fingerprint 未开启 `generateDebugInfo`,记录 WARN 不失败(允许"无符号调试")。
 */
class DebugLauncher : Launcher {

    companion object {
        private const val TAG = "DebugLauncher"
    }

    override suspend fun launch(
        artifact: Artifact,
        ctx: BuildContext,
        emitter: BuildEventEmitter,
    ): LaunchOutcome {
        val file = File(artifact.absolutePath)
        if (!file.isFile) {
            val reason = "debug artifact not found: ${artifact.absolutePath}"
            emitter.emit(BuildEvent.Launch.Failed(reason, wasArtifactCached = false))
            return LaunchOutcome.Failed(reason)
        }
        if (!artifact.fingerprint.generateDebugInfo) {
            Timber.tag(TAG).w("debug launch without -g symbols; stepping may be limited")
        }
        Timber.tag(TAG).d("debug launch prepared: %s", file.absolutePath)
        emitter.emit(BuildEvent.Launch.Completed)
        return LaunchOutcome.Prepared(
            LaunchDescriptor.Debug(
                artifact = artifact,
                programPath = artifact.absolutePath,
                workingDir = ctx.buildDir.absolutePath,
            )
        )
    }
}
