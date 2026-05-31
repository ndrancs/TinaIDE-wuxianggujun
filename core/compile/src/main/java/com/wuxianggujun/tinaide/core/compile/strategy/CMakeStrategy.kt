package com.wuxianggujun.tinaide.core.compile.strategy

import android.content.Context
import com.wuxianggujun.tinaide.cmake.CMake
import com.wuxianggujun.tinaide.cmake.CMakeDoc
import com.wuxianggujun.tinaide.cmake.analysis.CMakeAnalyzer
import com.wuxianggujun.tinaide.core.compile.BuildOptions
import com.wuxianggujun.tinaide.core.compile.BuildResult
import com.wuxianggujun.tinaide.core.compile.BuildSystem
import com.wuxianggujun.tinaide.core.compile.CMakeBuildTypeOption
import com.wuxianggujun.tinaide.core.compile.CMakeGeneratorOption
import com.wuxianggujun.tinaide.core.compile.CompileTimeoutConfig
import com.wuxianggujun.tinaide.core.compile.ConfigureResult
import com.wuxianggujun.tinaide.core.compile.RunConfiguration
import com.wuxianggujun.tinaide.core.compile.TargetInfo
import com.wuxianggujun.tinaide.core.compile.artifact.Artifact
import com.wuxianggujun.tinaide.core.compile.artifact.ArtifactId
import com.wuxianggujun.tinaide.core.compile.artifact.ArtifactKind
import com.wuxianggujun.tinaide.core.compile.artifact.ArtifactSpec
import com.wuxianggujun.tinaide.core.compile.artifact.BuildFingerprint
import com.wuxianggujun.tinaide.core.compile.artifact.SourceRef
import com.wuxianggujun.tinaide.core.compile.artifact.TrackedInputCollector
import com.wuxianggujun.tinaide.core.compile.cmake.CMakeBuildExecutor
import com.wuxianggujun.tinaide.core.compile.cmake.NativeCMakeBuildExecutor
import com.wuxianggujun.tinaide.core.compile.event.BuildEvent
import com.wuxianggujun.tinaide.core.compile.event.BuildEventEmitter
import com.wuxianggujun.tinaide.core.i18n.Strings
import com.wuxianggujun.tinaide.core.i18n.strOr
import com.wuxianggujun.tinaide.core.linux.LinuxEnvironmentProvider
import com.wuxianggujun.tinaide.core.linux.LinuxRunModePolicy
import com.wuxianggujun.tinaide.core.linux.UnavailableLinuxEnvironmentProvider
import com.wuxianggujun.tinaide.core.ndk.AndroidNativeToolchainManager
import com.wuxianggujun.tinaide.core.proot.PRootEnvironment
import com.wuxianggujun.tinaide.core.proot.ToolchainPathResolver
import com.wuxianggujun.tinaide.project.ProjectMetadataStore
import timber.log.Timber
import java.io.File
import java.security.MessageDigest

/**
 * CMake 构建策略。
 *
 * 设计要点:
 * - [describeOutput] 用 CMakeAnalyzer 解析 CMakeLists.txt,选定 target + 预测路径/kind,不 spawn 构建
 * - [execute] 自带 needsReconfigure 检测 + configure(PRoot / Native 双路径分派) + build + Artifact 包装
 * - 产物定位靠 [CMakeBuildExecutor] / [NativeCMakeBuildExecutor] 返回的 outputPath
 *   作为权威值,`spec.expectedPath` 仅用于首次构建前的 Planner 粗判
 * - 事件经 [BuildEventEmitter] 下发
 */
class CMakeStrategy(
    private val context: Context,
    private val prootEnv: PRootEnvironment? = null,
    private val linuxEnvironmentProvider: LinuxEnvironmentProvider = UnavailableLinuxEnvironmentProvider,
    timeoutConfig: CompileTimeoutConfig? = null,
) : BuildStrategy {

    companion object {
        private const val TAG = "CMakeStrategy"
    }

    private val sharedTimeoutConfig: CompileTimeoutConfig = timeoutConfig ?: CompileTimeoutConfig(context)

    @Volatile
    private var lastResolvedRunMode: LinuxRunModePolicy.RunMode = LinuxRunModePolicy.RunMode.NATIVE

    private val prootExecutor by lazy {
        CMakeBuildExecutor(context, resolvePRootEnvironment(), sharedTimeoutConfig)
    }
    private val nativeExecutor by lazy {
        NativeCMakeBuildExecutor(context, sharedTimeoutConfig)
    }
    private val nativeToolchainManager by lazy {
        AndroidNativeToolchainManager(context.applicationContext)
    }
    private val pathResolver by lazy { ToolchainPathResolver(context) }

    override val buildSystem: BuildSystem = BuildSystem.CMAKE

    override suspend fun canHandle(projectRoot: File): Boolean =
        File(projectRoot, "CMakeLists.txt").exists()

    override suspend fun describeOutput(ctx: BuildContext): ArtifactSpec? {
        val all = loadTargets(ctx.projectRoot, ctx.buildDir)
        if (all.isEmpty()) {
            Timber.tag(TAG).w("describeOutput: no targets in %s", ctx.projectRoot.absolutePath)
            return null
        }
        val selected = selectTarget(all, ctx.target) ?: run {
            Timber.tag(TAG).w("describeOutput: cannot resolve target=%s", ctx.target)
            return null
        }

        val kind = mapKind(selected.type)
        val outputName = selected.outputName.orEmpty().ifBlank { selected.name }
        val expectedFileName = when (kind) {
            ArtifactKind.SHARED_LIBRARY -> if (outputName.startsWith("lib")) "$outputName.so" else "lib$outputName.so"
            ArtifactKind.STATIC_LIBRARY -> if (outputName.startsWith("lib")) "$outputName.a" else "lib$outputName.a"
            else -> outputName
        }

        val sourceFiles = selected.sources
            .map { relative -> File(ctx.projectRoot, relative) }
            .filter { it.isFile }
        val trackedInputs = TrackedInputCollector.collectCMakeInputs(ctx.projectRoot, sourceFiles)

        return ArtifactSpec(
            id = ArtifactId(
                projectId = ctx.projectId,
                targetName = selected.name,
                variant = resolveVariant(ctx),
            ),
            expectedPath = File(ctx.buildDir, expectedFileName),
            kind = kind,
            sources = trackedInputs,
        )
    }

    override suspend fun execute(
        ctx: BuildContext,
        spec: ArtifactSpec,
        fingerprint: BuildFingerprint,
        emitter: BuildEventEmitter,
    ): ExecutionOutcome {
        emitter.emit(BuildEvent.Build.CompileStarted(spec.id.targetName))
        val start = System.currentTimeMillis()
        val options = ctx.options
        val onProgress: (String) -> Unit = { line -> emitter.tryEmit(BuildEvent.Build.CompileProgress(line)) }
        val optionsWithProgress = options.copy(onProgress = onProgress)

        // 1. 需要时重新 configure(CMakeCache 变旧 / 参数变更 / 元数据变更)
        lastResolvedRunMode = options.resolvedRunMode
        if (needsReconfigure(ctx.projectRoot, ctx.buildDir, optionsWithProgress)) {
            emitter.emit(BuildEvent.Build.ConfigureStarted(spec.id.targetName))
            onProgress(Strings.cmake_progress_reconfiguring.strOr(context))
            val cfgStart = System.currentTimeMillis()
            val cfgResult = configure(ctx.projectRoot, ctx.buildDir, optionsWithProgress)
            if (cfgResult is ConfigureResult.Error) {
                emitter.emit(BuildEvent.Build.ConfigureFailed(cfgResult.message))
                return reportFailure(emitter, cfgResult.message, emptyList(), cfgResult.message)
            }
            emitter.emit(BuildEvent.Build.ConfigureCompleted(System.currentTimeMillis() - cfgStart))
        }

        // 2. build
        val buildResult = if (isNativeMode(options.resolvedRunMode)) {
            buildNative(ctx.projectRoot, ctx.buildDir, spec.id.targetName.takeIf { it.isNotBlank() } ?: ctx.target, optionsWithProgress)
        } else {
            buildPRoot(ctx.projectRoot, ctx.buildDir, spec.id.targetName.takeIf { it.isNotBlank() } ?: ctx.target, optionsWithProgress)
        }
        val elapsed = System.currentTimeMillis() - start

        return when (buildResult) {
            is BuildResult.Success -> wrapArtifact(ctx, spec, fingerprint, buildResult, elapsed, emitter)
            is BuildResult.Error -> reportFailure(emitter, buildResult.rawOutput, buildResult.diagnostics, buildResult.rawOutput)
        }
    }

    override suspend fun clean(ctx: BuildContext, reconfigure: Boolean) {
        if (reconfigure) {
            File(ctx.buildDir, "CMakeCache.txt").delete()
            File(ctx.buildDir, "CMakeFiles").deleteRecursively()
        } else {
            // 完整清理:委托给对应执行器以享有各自的清理语义
            if (isNativeMode(lastResolvedRunMode)) {
                nativeExecutor.clean(ctx.buildDir)
            } else {
                prootExecutor.clean(ctx.buildDir)
            }
        }
    }

    override suspend fun getTargets(ctx: BuildContext): List<TargetInfo> =
        loadTargets(ctx.projectRoot, ctx.buildDir)

    // ---------- configure / build 分派 ----------

    private suspend fun configure(
        projectRoot: File,
        buildDir: File,
        options: BuildOptions,
    ): ConfigureResult {
        return if (isNativeMode(options.resolvedRunMode)) {
            configureNative(projectRoot, buildDir, options)
        } else {
            configurePRoot(projectRoot, buildDir, options)
        }
    }

    private suspend fun configureNative(
        projectRoot: File,
        buildDir: File,
        options: BuildOptions,
    ): ConfigureResult {
        val customCCompiler = RunConfiguration.normalizeCompilerPath(options.customCCompiler)
        val customCppCompiler = RunConfiguration.normalizeCompilerPath(options.customCppCompiler)
        val cmakeOptions = NativeCMakeBuildExecutor.Options(
            buildType = mapNativeBuildType(options.cmakeBuildType),
            generator = mapNativeGenerator(options.cmakeGenerator),
            parallelJobs = resolveParallelJobs(options.parallelJobs),
            extraCMakeArgs = options.nativeCMakeArgs,
            generateCompileCommands = true,
            compilerType = options.compilerType,
            toolchainId = options.toolchainId,
            cCompilerPath = customCCompiler,
            cxxCompilerPath = customCppCompiler,
            sysrootApiLevel = options.sysrootApiLevel,
            cFlags = options.nativeCFlags,
            cppFlags = options.nativeCppFlags,
            ldFlags = options.nativeLdFlags,
            ldLibs = options.nativeLdLibs,
            cppStandard = options.cppStandard,
            onProgress = options.onProgress,
        )
        return nativeExecutor.configure(
            projectDir = projectRoot,
            buildDir = buildDir,
            options = cmakeOptions,
        )
    }

    private suspend fun configurePRoot(
        projectRoot: File,
        buildDir: File,
        options: BuildOptions,
    ): ConfigureResult {
        val (cCompiler, cxxCompiler) = try {
            resolveCompilerPaths(options)
        } catch (e: IllegalArgumentException) {
            return ConfigureResult.Error(e.message ?: Strings.cmake_error_invalid_compiler_config.strOr(context))
        }
        val cmakeOptions = CMakeBuildExecutor.Options(
            buildType = mapPRootBuildType(options.cmakeBuildType),
            generator = mapPRootGenerator(options.cmakeGenerator),
            parallelJobs = resolveParallelJobs(options.parallelJobs),
            extraCMakeArgs = options.nativeCMakeArgs,
            generateCompileCommands = true,
            compilerType = options.compilerType,
            cCompilerPath = cCompiler,
            cxxCompilerPath = cxxCompiler,
            cFlags = options.nativeCFlags,
            cppFlags = options.nativeCppFlags,
            ldFlags = options.nativeLdFlags,
            ldLibs = options.nativeLdLibs,
            cppStandard = options.cppStandard,
        )
        return prootExecutor.configure(
            projectRoot = projectRoot,
            buildDir = buildDir,
            options = cmakeOptions,
            progress = options.onProgress ?: {},
        )
    }

    private suspend fun buildNative(
        projectRoot: File,
        buildDir: File,
        target: String?,
        options: BuildOptions,
    ): BuildResult {
        val cmakeOptions = NativeCMakeBuildExecutor.Options(
            generator = mapNativeGenerator(options.cmakeGenerator),
            parallelJobs = resolveParallelJobs(options.parallelJobs),
            toolchainId = options.toolchainId,
            sysrootApiLevel = options.sysrootApiLevel,
            onProgress = options.onProgress,
        )
        return nativeExecutor.build(
            projectDir = projectRoot,
            buildDir = buildDir,
            target = target,
            options = cmakeOptions,
        )
    }

    private suspend fun buildPRoot(
        projectRoot: File,
        buildDir: File,
        target: String?,
        options: BuildOptions,
    ): BuildResult {
        return prootExecutor.build(
            projectRoot = projectRoot,
            buildDir = buildDir,
            target = target,
            parallelJobs = resolveParallelJobs(options.parallelJobs),
            progress = options.onProgress ?: {},
        )
    }

    // ---------- needsReconfigure ----------

    private fun needsReconfigure(projectRoot: File, buildDir: File, options: BuildOptions): Boolean {
        val cacheFile = File(buildDir, "CMakeCache.txt")
        if (!cacheFile.exists()) return true

        val cmakeListsFile = File(projectRoot, "CMakeLists.txt")
        if (cmakeListsFile.exists() && cmakeListsFile.lastModified() > cacheFile.lastModified()) return true

        val metadataFile = ProjectMetadataStore.getMetaFile(projectRoot)
        if (metadataFile.isFile && metadataFile.lastModified() > cacheFile.lastModified()) return true

        try {
            val cacheContent = cacheFile.readText()
            val (expectedCCompiler, expectedCxxCompiler) = resolveExpectedCompilerConfig(options)

            val cCompilerRegex = Regex("""(?m)^CMAKE_C_COMPILER:FILEPATH=(.+)$""")
            val cMatch = cCompilerRegex.find(cacheContent)
            if (cMatch == null || cMatch.groupValues[1].trim() != expectedCCompiler) return true

            val cxxCompilerRegex = Regex("""(?m)^CMAKE_CXX_COMPILER:FILEPATH=(.+)$""")
            val cxxMatch = cxxCompilerRegex.find(cacheContent)
            if (cxxMatch == null || cxxMatch.groupValues[1].trim() != expectedCxxCompiler) return true

            val generatorRegex = Regex("""(?m)^CMAKE_GENERATOR:INTERNAL=(.+)$""")
            val cachedGenerator = generatorRegex.find(cacheContent)?.groupValues?.get(1)?.trim()
            val expectedGenerator = options.cmakeGenerator.cmakeValue
            if (cachedGenerator == null || cachedGenerator != expectedGenerator) return true

            val buildTypeRegex = Regex("""(?m)^CMAKE_BUILD_TYPE:STRING=(.*)$""")
            val cachedBuildType = buildTypeRegex.find(cacheContent)?.groupValues?.get(1)?.trim()
            val expectedBuildType = options.cmakeBuildType.cmakeValue
            if (cachedBuildType == null || cachedBuildType != expectedBuildType) return true
        } catch (e: Exception) {
            Timber.tag(TAG).w("Failed to read CMakeCache.txt: %s", e.message)
            return true
        }
        return false
    }

    private fun resolveExpectedCompilerConfig(options: BuildOptions): Pair<String, String> {
        if (!isNativeMode(options.resolvedRunMode)) {
            val (cCompiler, cxxCompiler) = resolveCompilerPaths(options)
            return cCompiler to cxxCompiler
        }
        val binDir = nativeToolchainManager.getBinDir(options.toolchainId)
        val realCCompiler = NativeCMakeBuildExecutor.resolveConfiguredCompilerPath(
            configuredPath = options.customCCompiler,
            fallbackPath = File(binDir, "clang").absolutePath,
        )
        val realCxxCompiler = NativeCMakeBuildExecutor.resolveConfiguredCompilerPath(
            configuredPath = options.customCppCompiler,
            fallbackPath = File(binDir, "clang++").absolutePath,
        )
        return realCCompiler to realCxxCompiler
    }

    private fun resolveCompilerPaths(options: BuildOptions): Pair<String, String> {
        val c = pathResolver.getCCompiler(options.compilerType, options.customCCompiler)
        val cxx = pathResolver.getCppCompiler(options.compilerType, options.customCppCompiler)
        Timber.tag(TAG).d("Resolved compilers: C=%s, C++=%s", c, cxx)
        return c to cxx
    }

    private fun resolvePRootEnvironment(): PRootEnvironment {
        val fromProvider = (linuxEnvironmentProvider.get() as? PRootEnvironment)
        return fromProvider ?: prootEnv ?: error("PRoot environment is unavailable for CMake build")
    }

    private fun resolveParallelJobs(fallbackJobs: Int): Int = fallbackJobs.coerceIn(1, 8)

    private fun isNativeMode(runMode: LinuxRunModePolicy.RunMode): Boolean =
        runMode == LinuxRunModePolicy.RunMode.NATIVE

    // ---------- targets / 产物 / variant / hash ----------

    private suspend fun loadTargets(projectRoot: File, buildDir: File): List<TargetInfo> {
        val cmakeFile = File(projectRoot, "CMakeLists.txt")
        if (cmakeFile.exists()) {
            try {
                val doc = CMake.parse(cmakeFile.readText()).getOrNull()
                if (doc != null) {
                    val analysis = CMakeAnalyzer(doc).analyze()
                    return doc.targets.map { target ->
                        val targetType = mapTargetType(target.type)
                        val properties = analysis.targets[target.name]?.properties.orEmpty()
                        val outputName = when (targetType) {
                            TargetInfo.Type.SHARED_LIBRARY -> properties["LIBRARY_OUTPUT_NAME"] ?: properties["OUTPUT_NAME"]
                            TargetInfo.Type.STATIC_LIBRARY -> properties["ARCHIVE_OUTPUT_NAME"] ?: properties["OUTPUT_NAME"]
                            TargetInfo.Type.EXECUTABLE -> properties["RUNTIME_OUTPUT_NAME"] ?: properties["OUTPUT_NAME"]
                            else -> properties["OUTPUT_NAME"]
                        }
                        TargetInfo(
                            name = target.name,
                            type = targetType,
                            sources = target.sources,
                            outputName = outputName,
                        )
                    }
                }
            } catch (e: Exception) {
                Timber.tag(TAG).d("CMakeLists.txt parse failed, fallback to build-system query: %s", e.message)
            }
        }

        val fromBuildSystem = if (isNativeMode(lastResolvedRunMode)) {
            emptyList()
        } else {
            prootExecutor.getTargetsFromBuildSystem(buildDir)
        }
        return fromBuildSystem.map { name ->
            TargetInfo(name = name, type = TargetInfo.Type.OTHER, sources = emptyList(), outputName = null)
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
            val reason = "CMake reported success but artifact missing (expected ${spec.expectedPath.absolutePath})"
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

    private suspend fun reportFailure(
        emitter: BuildEventEmitter,
        rawOutput: String,
        diagnostics: List<com.wuxianggujun.tinaide.core.compile.BuildDiagnostic>,
        fallbackReason: String,
    ): ExecutionOutcome {
        emitter.emit(BuildEvent.Build.CompileFailed(rawOutput, diagnostics))
        val firstLine = rawOutput.lineSequence().firstOrNull { it.isNotBlank() }.orEmpty()
        return ExecutionOutcome.Failure(
            reason = firstLine.ifBlank { fallbackReason.ifBlank { "cmake build failed" } },
            diagnostics = diagnostics,
            rawOutput = rawOutput,
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

    private fun mapTargetType(type: CMakeDoc.TargetType): TargetInfo.Type = when (type) {
        CMakeDoc.TargetType.EXECUTABLE -> TargetInfo.Type.EXECUTABLE
        CMakeDoc.TargetType.STATIC_LIBRARY -> TargetInfo.Type.STATIC_LIBRARY
        CMakeDoc.TargetType.SHARED_LIBRARY -> TargetInfo.Type.SHARED_LIBRARY
        CMakeDoc.TargetType.MODULE_LIBRARY -> TargetInfo.Type.SHARED_LIBRARY
        CMakeDoc.TargetType.OBJECT_LIBRARY -> TargetInfo.Type.STATIC_LIBRARY
        CMakeDoc.TargetType.INTERFACE_LIBRARY -> TargetInfo.Type.OTHER
        CMakeDoc.TargetType.CUSTOM_TARGET -> TargetInfo.Type.OTHER
        CMakeDoc.TargetType.UNKNOWN -> TargetInfo.Type.OTHER
    }

    private fun mapNativeBuildType(type: CMakeBuildTypeOption): NativeCMakeBuildExecutor.CMakeBuildType = when (type) {
        CMakeBuildTypeOption.DEBUG -> NativeCMakeBuildExecutor.CMakeBuildType.DEBUG
        CMakeBuildTypeOption.RELEASE -> NativeCMakeBuildExecutor.CMakeBuildType.RELEASE
        CMakeBuildTypeOption.REL_WITH_DEB_INFO -> NativeCMakeBuildExecutor.CMakeBuildType.REL_WITH_DEB_INFO
        CMakeBuildTypeOption.MIN_SIZE_REL -> NativeCMakeBuildExecutor.CMakeBuildType.MIN_SIZE_REL
    }

    private fun mapPRootBuildType(type: CMakeBuildTypeOption): CMakeBuildExecutor.CMakeBuildType = when (type) {
        CMakeBuildTypeOption.DEBUG -> CMakeBuildExecutor.CMakeBuildType.DEBUG
        CMakeBuildTypeOption.RELEASE -> CMakeBuildExecutor.CMakeBuildType.RELEASE
        CMakeBuildTypeOption.REL_WITH_DEB_INFO -> CMakeBuildExecutor.CMakeBuildType.REL_WITH_DEB_INFO
        CMakeBuildTypeOption.MIN_SIZE_REL -> CMakeBuildExecutor.CMakeBuildType.MIN_SIZE_REL
    }

    private fun mapNativeGenerator(generator: CMakeGeneratorOption): NativeCMakeBuildExecutor.CMakeGenerator = when (generator) {
        CMakeGeneratorOption.NINJA -> NativeCMakeBuildExecutor.CMakeGenerator.NINJA
        CMakeGeneratorOption.UNIX_MAKEFILES -> NativeCMakeBuildExecutor.CMakeGenerator.UNIX_MAKEFILES
    }

    private fun mapPRootGenerator(generator: CMakeGeneratorOption): CMakeBuildExecutor.CMakeGenerator = when (generator) {
        CMakeGeneratorOption.NINJA -> CMakeBuildExecutor.CMakeGenerator.NINJA
        CMakeGeneratorOption.UNIX_MAKEFILES -> CMakeBuildExecutor.CMakeGenerator.UNIX_MAKEFILES
    }

    private fun resolveVariant(ctx: BuildContext): String {
        val buildType = ctx.options.cmakeBuildType.cmakeValue.lowercase()
        val generator = ctx.options.cmakeGenerator.name.lowercase()
        return "$buildType-$generator"
    }

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
