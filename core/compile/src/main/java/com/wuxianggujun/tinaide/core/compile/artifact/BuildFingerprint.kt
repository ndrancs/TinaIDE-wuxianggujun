package com.wuxianggujun.tinaide.core.compile.artifact

import kotlinx.serialization.Serializable

/**
 * 构建输入指纹,完整覆盖所有影响产物二进制结果的参数。
 *
 * - 任一字段变更 → 产物必然失效,触发 Rebuild
 * - `data class` 的 equals 天然保证字段完整性;新增字段自动使旧指纹失效
 * - [schemaVersion] 用于跨 TinaIDE 版本手动使所有旧指纹失效
 *
 * 不含:
 * - 源文件本身内容(由 [SourceRef.mtime] + [SourceRef.size] 在 Planner 层二次校验)
 * - 时间戳/机器标识(这些不影响产物)
 */
@Serializable
data class BuildFingerprint(
    val compilerType: String,
    val compilerPath: String,
    val toolchainId: String?,
    val sysrootProfileId: String? = null,
    val sysrootApiLevel: Int,
    val buildType: String,
    val cmakeBuildType: String?,
    val cmakeGenerator: String?,
    val cFlags: String,
    val cppFlags: String,
    val ldFlags: String,
    val ldLibs: String,
    val cmakeExtraArgs: String,
    val cppStandard: String?,
    val optimizationLevel: String,
    val generateDebugInfo: Boolean,
    val preferSharedLibraryForRun: Boolean,
    val parallelJobs: Int,
    val resolvedRunMode: String,
    val artifactKind: String,
    val expectedOutputPath: String,
    val trackedInputsHash: String,
    val extraEnvHash: String? = null,
    val schemaVersion: Int = SCHEMA_VERSION,
) {
    companion object {
        /** 每次破坏性字段调整时 +1,使所有旧持久化指纹立即失效 */
        const val SCHEMA_VERSION: Int = 3
    }
}
