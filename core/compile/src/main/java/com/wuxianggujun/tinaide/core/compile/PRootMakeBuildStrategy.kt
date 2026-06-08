package com.wuxianggujun.tinaide.core.compile

import android.content.Context
import com.wuxianggujun.tinaide.core.i18n.Strings
import com.wuxianggujun.tinaide.core.i18n.strOr
import com.wuxianggujun.tinaide.core.packages.InstalledPackagePathResolver
import com.wuxianggujun.tinaide.core.proot.PRootEnvironment
import java.io.File
import timber.log.Timber

/**
 * PRoot Makefile 构建引擎。
 *
 * 使用 PRoot 环境中的 make 工具执行构建，由 MakeStrategy 负责调度。
 *
 * @param context Android 应用上下文
 * @param prootEnv PRoot 环境管理器
 * @param timeoutConfig 可选的超时配置（如果为 null，将创建新实例）
 */
class PRootMakeBuildStrategy(
    private val context: Context,
    private val prootEnv: PRootEnvironment,
    timeoutConfig: CompileTimeoutConfig? = null
) {

    companion object {
        private const val TAG = "PRootMakeBuildStrategy"
    }

    // 超时配置（支持共享）
    private val sharedTimeoutConfig: CompileTimeoutConfig = timeoutConfig ?: CompileTimeoutConfig(context)

    val buildSystem = BuildSystem.MAKE

    suspend fun canHandle(projectRoot: File): Boolean = hasMakefile(projectRoot)

    suspend fun build(
        projectRoot: File,
        buildDir: File,
        target: String?,
        options: BuildOptions
    ): BuildResult {
        val startTime = System.currentTimeMillis()
        val outputBuilder = StringBuilder()

        options.onProgress?.invoke(Strings.make_building.strOr(context))

        val prootManager = prootEnv.getPRootManager()
        if (!buildDir.exists()) {
            buildDir.mkdirs()
        }
        val guestBuildDir = prootManager.toGuestPath(buildDir.absolutePath)

        // 构建 make 命令
        val makeCommand = mutableListOf("make")
        val packagePaths = InstalledPackagePathResolver.resolve(context.applicationContext, projectRoot)
        val packageEnv = MakeBuildEnvironment.build(
            packagePaths = packagePaths,
            nativeCFlags = options.nativeCFlags,
            nativeCppFlags = options.nativeCppFlags,
            nativeLdFlags = options.nativeLdFlags,
            nativeLdLibs = options.nativeLdLibs,
            pathMapper = prootManager::toGuestPath
        )

        // 添加并行任务数
        if (options.parallelJobs > 1) {
            makeCommand.add("-j${options.parallelJobs}")
        }

        // 尽量把常见构建输出变量统一指向当前项目的 build 目录。
        makeCommand.add("BUILD_DIR=$guestBuildDir")
        makeCommand.add("OUT_DIR=$guestBuildDir")
        makeCommand.add("OBJDIR=$guestBuildDir")
        makeCommand.add("O=$guestBuildDir")
        makeCommand.add("BUILD_TYPE=${options.buildType.name.lowercase()}")

        // 添加目标（如果指定）
        if (!target.isNullOrBlank()) {
            makeCommand.add(target)
        }

        Timber.tag(TAG).d("Executing: ${makeCommand.joinToString(" ")}")
        Timber.tag(TAG).d("Working directory: ${projectRoot.absolutePath}")

        // 在 PRoot 环境中执行 make
        val result = prootManager.executeWithOutput(
            command = makeCommand,
            workDir = projectRoot.absolutePath,
            env = packageEnv,
            timeout = sharedTimeoutConfig.getMakeBuildTimeout()
        ) { line ->
            outputBuilder.appendLine(line)
            options.onProgress?.invoke(line)
        }

        val buildTime = System.currentTimeMillis() - startTime

        return if (result.exitCode == 0) {
            val executablePath = MakeBuildOutputLocator.findExecutable(
                projectRoot = projectRoot,
                buildDir = buildDir,
                target = target,
                makefile = findMakefile(projectRoot)
            )

            Timber.tag(TAG).d("Build succeeded in ${buildTime}ms")
            BuildResult.Success(
                message = Strings.make_build_success.strOr(context),
                buildTimeMs = buildTime,
                outputPath = executablePath
            )
        } else {
            val rawOutput = outputBuilder.toString().ifBlank { result.combinedOutput }
            Timber.tag(TAG).w("Build failed with exit code ${result.exitCode}")
            Timber.tag(TAG).d("Output: $rawOutput")

            // 解析诊断信息
            val diagnostics = parseDiagnostics(rawOutput)

            BuildResult.Error(
                rawOutput = rawOutput,
                diagnostics = diagnostics
            )
        }
    }

    suspend fun clean(buildDir: File): CleanResult {
        return try {
            // 尝试执行 make clean
            val prootManager = prootEnv.getPRootManager()
            val projectRoot = buildDir.parentFile ?: return CleanResult.Error("Invalid build directory")

            if (hasMakefile(projectRoot)) {
                val result = prootManager.executeWithOutput(
                    command = listOf("make", "clean"),
                    workDir = projectRoot.absolutePath,
                    env = emptyMap(),
                    timeout = sharedTimeoutConfig.getMakeCleanTimeout()
                ) { }

                if (result.exitCode == 0) {
                    Timber.tag(TAG).d("make clean succeeded")
                } else {
                    Timber.tag(TAG).w("make clean failed, falling back to manual cleanup")
                }
            }

            // 无论 make clean 是否成功，都清理 build 目录
            if (buildDir.exists()) {
                buildDir.deleteRecursively()
            }

            CleanResult.Success
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Clean failed")
            CleanResult.Error(e.message ?: Strings.make_clean_failed.strOr(context))
        }
    }

    suspend fun getTargets(projectRoot: File, buildDir: File): List<TargetInfo> {
        // 从 Makefile 中解析目标
        val makefile = findMakefile(projectRoot) ?: return emptyList()

        return try {
            val content = makefile.readText()
            parseMakefileTargets(content)
        } catch (e: Exception) {
            Timber.tag(TAG).w(e, "Failed to parse Makefile targets")
            emptyList()
        }
    }

    /**
     * 检查是否存在 Makefile
     */
    private fun hasMakefile(projectRoot: File): Boolean = findMakefile(projectRoot) != null

    /**
     * 查找 Makefile
     */
    private fun findMakefile(projectRoot: File): File? {
        val candidates = listOf("Makefile", "makefile", "GNUmakefile")
        return candidates.map { File(projectRoot, it) }.firstOrNull { it.exists() }
    }

    /**
     * 从 Makefile 内容中解析目标列表
     */
    private fun parseMakefileTargets(content: String): List<TargetInfo> {
        val targets = mutableListOf<TargetInfo>()

        // 匹配目标定义：target: dependencies
        val targetRegex = Regex("""^([a-zA-Z_][a-zA-Z0-9_-]*)\s*:(?!=)""", RegexOption.MULTILINE)

        for (match in targetRegex.findAll(content)) {
            val targetName = match.groupValues[1]
            // 排除 .PHONY 等特殊目标
            if (!targetName.startsWith(".")) {
                targets.add(
                    TargetInfo(
                        name = targetName,
                        type = if (targetName in listOf("all", "clean", "install", "run", "test")) {
                            TargetInfo.Type.OTHER
                        } else {
                            TargetInfo.Type.EXECUTABLE
                        },
                        sources = emptyList()
                    )
                )
            }
        }

        return targets
    }

    /**
     * 解析编译错误诊断信息
     */
    private fun parseDiagnostics(output: String): List<BuildDiagnostic> = BuildDiagnosticParser.parse(output)
}
