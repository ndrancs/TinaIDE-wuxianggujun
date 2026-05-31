package com.wuxianggujun.tinaide.core.compile.event

import com.wuxianggujun.tinaide.core.compile.BuildDiagnostic
import com.wuxianggujun.tinaide.core.compile.action.CompileRequest
import com.wuxianggujun.tinaide.core.compile.artifact.Artifact

/**
 * 细粒度构建/启动事件。由 `BuildOrchestrator` 在各阶段发射,UI 订阅后可精确区分:
 *
 * - 「检查产物中」 ← [Planning.Started]
 * - 「命中缓存,正在启动」 ← [Build.Skipped]
 * - 「正在编译」 ← [Build.CompileStarted] / [Build.CompileProgress]
 * - 「产物异常,已自动重建」 ← [AutoFallback]
 *
 * 替代旧 `CompileEvent`(仅 Progress/Success/Error 三值)的粗粒度。
 */
sealed interface BuildEvent {

    /** 整个 `run(request, ctx)` 开始 */
    data class Started(val request: CompileRequest) : BuildEvent

    /** 整个 `run(request, ctx)` 结束 */
    data class Finished(val report: BuildReport) : BuildEvent

    /** Auto-Fallback 触发:缓存产物 launch 失败,即将以 Force 重试(见设计文档 §4.6) */
    data class AutoFallback(
        val reason: String,
        val firstFailure: BuildReport.LaunchFailed,
    ) : BuildEvent

    /** Planner 阶段事件 */
    sealed interface Planning : BuildEvent {
        data object Started : Planning
        data class Decided(val summary: String) : Planning
    }

    /** Build 阶段事件(含 configure + compile + link) */
    sealed interface Build : BuildEvent {
        data class Skipped(val artifact: Artifact, val reason: String) : Build

        data class ConfigureStarted(val target: String?) : Build
        data class ConfigureCompleted(val durationMs: Long) : Build
        data class ConfigureFailed(val reason: String) : Build

        data class CompileStarted(val target: String?) : Build
        data class CompileProgress(val message: String) : Build
        data class CompileCompleted(val artifact: Artifact) : Build
        data class CompileFailed(
            val reason: String,
            val diagnostics: List<BuildDiagnostic>,
        ) : Build

        data class Cleaned(val clearedArtifactCount: Int) : Build
    }

    /** Launch 阶段事件 */
    sealed interface Launch : BuildEvent {
        data class Started(val artifact: Artifact) : Launch
        data object Completed : Launch
        data class Failed(val reason: String, val wasArtifactCached: Boolean) : Launch
    }
}
