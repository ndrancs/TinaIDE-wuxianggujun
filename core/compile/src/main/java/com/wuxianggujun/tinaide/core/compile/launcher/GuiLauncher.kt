package com.wuxianggujun.tinaide.core.compile.launcher

import com.wuxianggujun.tinaide.core.compile.artifact.Artifact
import com.wuxianggujun.tinaide.core.compile.artifact.ArtifactKind
import com.wuxianggujun.tinaide.core.compile.event.BuildEvent
import com.wuxianggujun.tinaide.core.compile.event.BuildEventEmitter
import com.wuxianggujun.tinaide.core.compile.strategy.BuildContext
import com.wuxianggujun.tinaide.core.i18n.Strings
import com.wuxianggujun.tinaide.core.i18n.strOr
import timber.log.Timber
import java.io.File

/**
 * Validates a `.so` artifact before the UI starts the SDL graphical runtime.
 */
class GuiLauncher : Launcher {

    companion object {
        private const val TAG = "GuiLauncher"
    }

    override suspend fun launch(
        artifact: Artifact,
        ctx: BuildContext,
        emitter: BuildEventEmitter,
    ): LaunchOutcome {
        val file = File(artifact.absolutePath)
        if (!file.isFile) {
            val reason = Strings.sdl_runtime_error_main_library_invalid.strOr(ctx.appContext, artifact.absolutePath)
            emitter.emit(BuildEvent.Launch.Failed(reason, wasArtifactCached = false))
            return LaunchOutcome.Failed(reason)
        }
        if (artifact.kind != ArtifactKind.SHARED_LIBRARY || !file.name.endsWith(".so", ignoreCase = true)) {
            val reason = Strings.sdl_runtime_invalid_shared_library.strOr(ctx.appContext, artifact.absolutePath)
            emitter.emit(BuildEvent.Launch.Failed(reason, wasArtifactCached = false))
            return LaunchOutcome.Failed(reason)
        }
        Timber.tag(TAG).d("SDL graphical launch prepared: %s", file.absolutePath)
        emitter.emit(BuildEvent.Launch.Completed)
        return LaunchOutcome.Prepared(LaunchDescriptor.Gui(artifact = artifact, libraryPath = artifact.absolutePath))
    }
}
