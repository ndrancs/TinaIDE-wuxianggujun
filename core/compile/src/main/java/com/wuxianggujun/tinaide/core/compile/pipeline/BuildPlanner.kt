package com.wuxianggujun.tinaide.core.compile.pipeline

import com.wuxianggujun.tinaide.core.compile.action.BuildIntent
import com.wuxianggujun.tinaide.core.compile.action.CompileRequest
import com.wuxianggujun.tinaide.core.compile.artifact.ArtifactSpec
import com.wuxianggujun.tinaide.core.compile.artifact.ArtifactStore
import com.wuxianggujun.tinaide.core.compile.artifact.BuildFingerprint
import com.wuxianggujun.tinaide.core.compile.artifact.FingerprintCalculator
import com.wuxianggujun.tinaide.core.compile.strategy.BuildContext
import com.wuxianggujun.tinaide.core.compile.strategy.BuildStrategy
import com.wuxianggujun.tinaide.core.compile.strategy.BuildStrategyRegistry
import java.io.File
import timber.log.Timber

/**
 * 决策中心:把 `CompileRequest` 翻译成具体的 [BuildPlan]。
 *
 * 唯一职责:
 * - 基于 [BuildStrategyRegistry] 查找策略
 * - 调用 [BuildStrategy.describeOutput] 得到产物规格
 * - 综合 [ArtifactStore] 的缓存 + [FingerprintCalculator] 的指纹做增量判定
 *
 * 不做的事情:
 * - 不执行构建(交给 [BuildExecutor])
 * - 不发射 BuildEvent(由 Orchestrator 发)
 */
class BuildPlanner(
    private val strategyRegistry: BuildStrategyRegistry,
    private val artifactStore: ArtifactStore,
    private val fingerprintCalculator: FingerprintCalculator,
) {

    companion object {
        private const val TAG = "BuildPlanner"
    }

    suspend fun plan(request: CompileRequest, ctx: BuildContext): BuildPlan {
        val strategy = strategyRegistry.resolve(ctx.buildSystem)
            ?: return BuildPlan.Invalid("no strategy registered for buildSystem=${ctx.buildSystem}")

        if (request.build is BuildIntent.Clean) {
            return BuildPlan.CleanOnly(strategy, request.build.reconfigure)
        }

        val spec = strategy.describeOutput(ctx)
            ?: return BuildPlan.Invalid("strategy cannot describe output (target=${ctx.target})")

        val expectedFingerprint = fingerprintCalculator.compute(ctx, spec)

        return when (val intent = request.build) {
            BuildIntent.Force -> BuildPlan.Build(strategy, spec, expectedFingerprint, "force rebuild requested")
            BuildIntent.None -> planLaunchOnly(spec, ctx)
            BuildIntent.IfNeeded -> planIncremental(strategy, spec, expectedFingerprint, ctx)
            is BuildIntent.Clean -> error("unreachable (handled above): $intent")
        }
    }

    private suspend fun planLaunchOnly(spec: ArtifactSpec, ctx: BuildContext): BuildPlan {
        val cached = artifactStore.find(spec.id, ctx.buildDir)
        return if (cached != null && cached.file().isFile) {
            BuildPlan.Skip(cached, "BuildIntent.None: using existing cached artifact")
        } else {
            BuildPlan.Invalid("BuildIntent.None but no cached artifact available for ${spec.id.storageKey()}")
        }
    }

    private suspend fun planIncremental(
        strategy: BuildStrategy,
        spec: ArtifactSpec,
        expected: BuildFingerprint,
        ctx: BuildContext,
    ): BuildPlan {
        val cached = artifactStore.find(spec.id, ctx.buildDir)
            ?: return BuildPlan.Build(strategy, spec, expected, "no cached artifact")

        val cachedFile = cached.file()
        if (!cachedFile.isFile) {
            Timber.tag(TAG).d("cached artifact file missing: %s", cachedFile.absolutePath)
            return BuildPlan.Build(strategy, spec, expected, "cached artifact file missing")
        }
        if (cached.fingerprint != expected) {
            Timber.tag(TAG).d("fingerprint mismatch for %s", spec.id.storageKey())
            return BuildPlan.Build(strategy, spec, expected, fingerprintDiffReason(cached.fingerprint, expected))
        }

        val changedInput = cached.sources.firstOrNull { ref ->
            val f = File(ctx.projectRoot, ref.relativePath)
            !f.isFile || f.lastModified() != ref.mtime || f.length() != ref.size
        }
        if (changedInput != null) {
            Timber.tag(TAG).d("tracked input changed: %s", changedInput.relativePath)
            return BuildPlan.Build(strategy, spec, expected, "tracked input changed: ${changedInput.relativePath}")
        }

        return BuildPlan.Skip(cached, "up-to-date")
    }

    /** 给出 fingerprint 差异的简短说明(首个变化的字段),便于 UI "为什么要重建" 展示。 */
    private fun fingerprintDiffReason(old: BuildFingerprint, expected: BuildFingerprint): String {
        val checks: List<Pair<String, () -> Boolean>> = listOf(
            "schemaVersion" to { old.schemaVersion != expected.schemaVersion },
            "compilerType" to { old.compilerType != expected.compilerType },
            "compilerPath" to { old.compilerPath != expected.compilerPath },
            "toolchainId" to { old.toolchainId != expected.toolchainId },
            "sysrootApiLevel" to { old.sysrootApiLevel != expected.sysrootApiLevel },
            "buildType" to { old.buildType != expected.buildType },
            "cmakeBuildType" to { old.cmakeBuildType != expected.cmakeBuildType },
            "cmakeGenerator" to { old.cmakeGenerator != expected.cmakeGenerator },
            "cFlags" to { old.cFlags != expected.cFlags },
            "cppFlags" to { old.cppFlags != expected.cppFlags },
            "ldFlags" to { old.ldFlags != expected.ldFlags },
            "ldLibs" to { old.ldLibs != expected.ldLibs },
            "cmakeExtraArgs" to { old.cmakeExtraArgs != expected.cmakeExtraArgs },
            "cppStandard" to { old.cppStandard != expected.cppStandard },
            "optimizationLevel" to { old.optimizationLevel != expected.optimizationLevel },
            "generateDebugInfo" to { old.generateDebugInfo != expected.generateDebugInfo },
            "preferSharedLibraryForRun" to { old.preferSharedLibraryForRun != expected.preferSharedLibraryForRun },
            "parallelJobs" to { old.parallelJobs != expected.parallelJobs },
            "resolvedRunMode" to { old.resolvedRunMode != expected.resolvedRunMode },
            "artifactKind" to { old.artifactKind != expected.artifactKind },
            "expectedOutputPath" to { old.expectedOutputPath != expected.expectedOutputPath },
            "trackedInputsHash" to { old.trackedInputsHash != expected.trackedInputsHash },
        )
        return checks.firstOrNull { it.second() }?.first?.let { "fingerprint changed: $it" }
            ?: "fingerprint changed"
    }
}
