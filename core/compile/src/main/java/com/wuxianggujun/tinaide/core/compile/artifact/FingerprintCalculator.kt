package com.wuxianggujun.tinaide.core.compile.artifact

import com.wuxianggujun.tinaide.core.compile.BuildOptions
import com.wuxianggujun.tinaide.core.compile.strategy.BuildContext
import java.io.File

/**
 * 构建指纹计算器:根据 [BuildContext.options] 与 [ArtifactSpec] 生成
 * [BuildFingerprint]。
 *
 * - 纯函数,无 IO
 * - 所有影响产物二进制的参数必须显式入指纹(新增字段见 [BuildFingerprint.SCHEMA_VERSION] 规则)
 * - `compilerPath` 由调用方在必要时解析(此处仅反映 options 中已知信息)
 */
class FingerprintCalculator {

    fun compute(ctx: BuildContext, spec: ArtifactSpec): BuildFingerprint {
        val options = ctx.options
        return BuildFingerprint(
            compilerType = options.compilerType.name,
            compilerPath = resolveCompilerPath(options),
            toolchainId = options.toolchainId,
            sysrootProfileId = options.sysrootProfileId,
            sysrootApiLevel = options.sysrootApiLevel,
            buildType = options.buildType.name,
            cmakeBuildType = options.cmakeBuildType.cmakeValue,
            cmakeGenerator = options.cmakeGenerator.cmakeValue,
            cFlags = options.nativeCFlags.trim(),
            cppFlags = options.nativeCppFlags.trim(),
            ldFlags = options.nativeLdFlags.trim(),
            ldLibs = options.nativeLdLibs.trim(),
            cmakeExtraArgs = options.nativeCMakeArgs.joinToString(" "),
            cppStandard = options.cppStandard?.trim(),
            optimizationLevel = options.optimizationLevel.trim(),
            generateDebugInfo = options.generateDebugInfo,
            preferSharedLibraryForRun = options.preferSharedLibraryForRun,
            parallelJobs = options.parallelJobs,
            resolvedRunMode = options.resolvedRunMode.name,
            artifactKind = spec.kind.name,
            expectedOutputPath = normalizePath(spec.expectedPath, ctx.buildDir),
            trackedInputsHash = hashTrackedInputs(spec.sources, ctx.projectRoot),
            extraEnvHash = null,
        )
    }

    /**
     * 解析出实际入指纹的 compiler 路径。
     *
     * - CUSTOM:使用 options 提供的自定义路径(c/cpp 分别记,取非空者)
     * - CLANG/GCC:路径依赖工具链 id 与 sysroot,这里用 "<toolchain>:<id>" 表达,避免引入 Context
     * - 具体 sysroot 变化由 sysrootProfileId + sysrootApiLevel 的联合指纹捕获
     */
    private fun resolveCompilerPath(options: BuildOptions): String = when (options.compilerType.name) {
        "CUSTOM" -> buildString {
            append("custom:")
            append(options.customCCompiler.orEmpty())
            append("|")
            append(options.customCppCompiler.orEmpty())
        }
        else -> "${options.compilerType.name.lowercase()}:${options.toolchainId ?: "default"}"
    }

    private fun hashTrackedInputs(files: List<File>, projectRoot: File): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        files.asSequence()
            .map { normalizePath(it, projectRoot) }
            .sorted()
            .forEach { path ->
                digest.update(path.toByteArray(Charsets.UTF_8))
                digest.update('\n'.code.toByte())
            }
        return digest.digest()
            .take(16)
            .joinToString(separator = "") { byte ->
                (byte.toInt() and 0xFF).toString(16).padStart(2, '0')
            }
    }

    private fun normalizePath(file: File, baseDir: File): String = file.absoluteFile.relativeToOrSelf(baseDir.absoluteFile).path.replace(File.separatorChar, '/')
}
