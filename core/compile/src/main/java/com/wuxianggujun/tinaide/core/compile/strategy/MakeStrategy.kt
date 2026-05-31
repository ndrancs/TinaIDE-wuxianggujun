package com.wuxianggujun.tinaide.core.compile.strategy

import android.content.Context
import com.wuxianggujun.tinaide.core.compile.BuildResult
import com.wuxianggujun.tinaide.core.compile.BuildSystem
import com.wuxianggujun.tinaide.core.compile.CompileTimeoutConfig
import com.wuxianggujun.tinaide.core.compile.NativeMakeBuildStrategy
import com.wuxianggujun.tinaide.core.compile.PRootMakeBuildStrategy
import com.wuxianggujun.tinaide.core.compile.TargetInfo
import com.wuxianggujun.tinaide.core.compile.artifact.Artifact
import com.wuxianggujun.tinaide.core.compile.artifact.ArtifactId
import com.wuxianggujun.tinaide.core.compile.artifact.ArtifactKind
import com.wuxianggujun.tinaide.core.compile.artifact.ArtifactSpec
import com.wuxianggujun.tinaide.core.compile.artifact.BuildFingerprint
import com.wuxianggujun.tinaide.core.compile.artifact.SourceRef
import com.wuxianggujun.tinaide.core.compile.artifact.TrackedInputCollector
import com.wuxianggujun.tinaide.core.compile.event.BuildEvent
import com.wuxianggujun.tinaide.core.compile.event.BuildEventEmitter
import com.wuxianggujun.tinaide.core.lang.CxxFileSupport
import com.wuxianggujun.tinaide.core.linux.LinuxEnvironmentProvider
import com.wuxianggujun.tinaide.core.linux.LinuxRunModePolicy
import com.wuxianggujun.tinaide.core.linux.UnavailableLinuxEnvironmentProvider
import com.wuxianggujun.tinaide.core.proot.PRootEnvironment
import timber.log.Timber
import java.io.File
import java.security.MessageDigest

/**
 * Make 构建策略。
 *
 * 设计要点:
 * - [describeOutput] 从 Makefile targets 选定 target + 启发式路径预测
 *   (Make 没有 File API 机制,target 名依赖 PRoot/Native make 各自的解析)
 * - [execute] native/proot 双路径分派,直接调用 [NativeMakeBuildStrategy] /
 *   [PRootMakeBuildStrategy] 作为编译引擎
 * - sources 收集为项目内 C/C++ 源与头文件(深度 3 以内),
 *   Planner 用 mtime 作增量二级校验
 */
class MakeStrategy(
    private val context: Context,
    private val prootEnv: PRootEnvironment? = null,
    private val linuxEnvironmentProvider: LinuxEnvironmentProvider = UnavailableLinuxEnvironmentProvider,
    timeoutConfig: CompileTimeoutConfig? = null,
) : BuildStrategy {

    companion object {
        private const val TAG = "MakeStrategy"
        private val SOURCE_EXTENSIONS: Set<String> =
            CxxFileSupport.cSourceExtensions + CxxFileSupport.cxxSourceExtensions + CxxFileSupport.headerExtensions
    }

    private val sharedTimeoutConfig: CompileTimeoutConfig = timeoutConfig ?: CompileTimeoutConfig(context)

    @Volatile
    private var lastResolvedRunMode: LinuxRunModePolicy.RunMode = LinuxRunModePolicy.RunMode.NATIVE

    private val nativeEngine by lazy {
        NativeMakeBuildStrategy(context, sharedTimeoutConfig)
    }
    private val prootEngine by lazy {
        PRootMakeBuildStrategy(context, resolvePRootEnvironment(), sharedTimeoutConfig)
    }

    private fun resolvePRootEnvironment(): PRootEnvironment {
        val fromProvider = (linuxEnvironmentProvider.get() as? PRootEnvironment)
        return fromProvider ?: prootEnv ?: error("PRoot environment is unavailable for Make build")
    }

    override val buildSystem: BuildSystem = BuildSystem.MAKE

    override suspend fun canHandle(projectRoot: File): Boolean = nativeEngine.canHandle(projectRoot)

    override suspend fun describeOutput(ctx: BuildContext): ArtifactSpec? {
        val all = loadTargets(ctx.projectRoot, ctx.buildDir, ctx.options.resolvedRunMode)
        val selected = selectTarget(all, ctx.target) ?: run {
            Timber.tag(TAG).w(
                "describeOutput: no target available (requested=%s, hasMakefile=%s)",
                ctx.target,
                File(ctx.projectRoot, "Makefile").exists(),
            )
            return null
        }

        val kind = mapKind(selected.type)
        val outputName = selected.outputName.orEmpty().ifBlank { selected.name }
        val fileName = when (kind) {
            ArtifactKind.SHARED_LIBRARY -> if (outputName.startsWith("lib")) "$outputName.so" else "lib$outputName.so"
            ArtifactKind.STATIC_LIBRARY -> if (outputName.startsWith("lib")) "$outputName.a" else "lib$outputName.a"
            else -> outputName
        }

        return ArtifactSpec(
            id = ArtifactId(
                projectId = ctx.projectId,
                targetName = selected.name,
                variant = resolveVariant(ctx),
            ),
            expectedPath = File(ctx.buildDir, fileName),
            kind = kind,
            sources = collectSourceFiles(ctx.projectRoot),
        )
    }

    override suspend fun execute(
        ctx: BuildContext,
        spec: ArtifactSpec,
        fingerprint: BuildFingerprint,
        emitter: BuildEventEmitter,
    ): ExecutionOutcome {
        emitter.emit(BuildEvent.Build.CompileStarted(spec.id.targetName))
        val startTime = System.currentTimeMillis()
        val options = ctx.options
        val onProgress: (String) -> Unit = { line -> emitter.tryEmit(BuildEvent.Build.CompileProgress(line)) }
        val optionsWithProgress = options.copy(onProgress = onProgress)
        lastResolvedRunMode = options.resolvedRunMode
        val target = spec.id.targetName.takeIf { it.isNotBlank() } ?: ctx.target

        val result = if (isNativeMode(options.resolvedRunMode)) {
            nativeEngine.build(ctx.projectRoot, ctx.buildDir, target, optionsWithProgress)
        } else {
            prootEngine.build(ctx.projectRoot, ctx.buildDir, target, optionsWithProgress)
        }
        val elapsed = System.currentTimeMillis() - startTime

        return when (result) {
            is BuildResult.Success -> wrapArtifact(ctx, spec, fingerprint, result, elapsed, emitter)
            is BuildResult.Error -> reportFailure(emitter, result)
        }
    }

    override suspend fun clean(ctx: BuildContext, reconfigure: Boolean) {
        // Make 无 reconfigure 概念;统一全量清理,委托给对应引擎沿用其清理语义
        if (isNativeMode(lastResolvedRunMode)) {
            nativeEngine.clean(ctx.buildDir)
        } else {
            prootEngine.clean(ctx.buildDir)
        }
    }

    override suspend fun getTargets(ctx: BuildContext): List<TargetInfo> =
        loadTargets(ctx.projectRoot, ctx.buildDir, ctx.options.resolvedRunMode)

    // ---------- 私有助手 ----------

    private suspend fun loadTargets(
        projectRoot: File,
        buildDir: File,
        runMode: LinuxRunModePolicy.RunMode,
    ): List<TargetInfo> {
        return if (isNativeMode(runMode)) {
            nativeEngine.getTargets(projectRoot, buildDir)
        } else {
            prootEngine.getTargets(projectRoot, buildDir)
        }
    }

    private suspend fun wrapArtifact(
        ctx: BuildContext,
        spec: ArtifactSpec,
        fingerprint: BuildFingerprint,
        success: BuildResult.Success,
        elapsedMs: Long,
        emitter: BuildEventEmitter,
    ): ExecutionOutcome {
        val artifactFile = success.outputPath?.let(::File)?.takeIf { it.isFile }
            ?: spec.expectedPath.takeIf { it.isFile }
        if (artifactFile == null) {
            val reason = "make reported success but artifact missing (expected ${spec.expectedPath.absolutePath})"
            emitter.emit(BuildEvent.Build.CompileFailed(reason, emptyList()))
            return ExecutionOutcome.Failure(reason)
        }
        val artifact = Artifact(
            id = spec.id,
            absolutePath = artifactFile.absolutePath,
            kind = spec.kind,
            contentHash = computeContentHash(artifactFile),
            fingerprint = fingerprint,
            sources = spec.sources.map { captureSourceRef(it, ctx.projectRoot) },
            compiledAt = System.currentTimeMillis(),
            buildTimeMs = elapsedMs,
        )
        emitter.emit(BuildEvent.Build.CompileCompleted(artifact))
        return ExecutionOutcome.Success(artifact, success.message)
    }

    private suspend fun reportFailure(emitter: BuildEventEmitter, error: BuildResult.Error): ExecutionOutcome {
        emitter.emit(BuildEvent.Build.CompileFailed(error.rawOutput, error.diagnostics))
        val firstLine = error.rawOutput.lineSequence().firstOrNull { it.isNotBlank() }.orEmpty()
        return ExecutionOutcome.Failure(
            reason = firstLine.ifBlank { "make failed" },
            diagnostics = error.diagnostics,
            rawOutput = error.rawOutput,
        )
    }

    private fun selectTarget(all: List<TargetInfo>, requested: String?): TargetInfo? {
        if (!requested.isNullOrBlank()) return all.firstOrNull { it.name == requested }
        return all.firstOrNull { it.type == TargetInfo.Type.EXECUTABLE }
            ?: all.firstOrNull { it.type != TargetInfo.Type.OTHER }
            ?: all.firstOrNull()
    }

    private fun mapKind(type: TargetInfo.Type): ArtifactKind = when (type) {
        TargetInfo.Type.EXECUTABLE -> ArtifactKind.EXECUTABLE
        TargetInfo.Type.SHARED_LIBRARY -> ArtifactKind.SHARED_LIBRARY
        TargetInfo.Type.STATIC_LIBRARY -> ArtifactKind.STATIC_LIBRARY
        TargetInfo.Type.OTHER -> ArtifactKind.UNKNOWN
    }

    private fun resolveVariant(ctx: BuildContext): String {
        val buildType = ctx.options.buildType.name.lowercase()
        val runMode = ctx.options.resolvedRunMode.name.lowercase()
        return "$buildType-$runMode"
    }

    private fun isNativeMode(runMode: LinuxRunModePolicy.RunMode): Boolean =
        runMode == LinuxRunModePolicy.RunMode.NATIVE

    private fun collectSourceFiles(projectRoot: File): List<File> =
        TrackedInputCollector.collectMakeInputs(projectRoot, SOURCE_EXTENSIONS)

    private fun captureSourceRef(file: File, projectRoot: File): SourceRef = SourceRef(
        relativePath = file.toRelativeString(projectRoot),
        mtime = file.lastModified(),
        size = file.length(),
    )

    private fun computeContentHash(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) { val n = input.read(buffer); if (n <= 0) break; digest.update(buffer, 0, n) }
        }
        val bytes = digest.digest()
        return buildString(32) {
            for (i in 0 until 16) {
                val b = bytes[i].toInt() and 0xFF
                append(b.toString(16).padStart(2, '0'))
            }
        }
    }
}
