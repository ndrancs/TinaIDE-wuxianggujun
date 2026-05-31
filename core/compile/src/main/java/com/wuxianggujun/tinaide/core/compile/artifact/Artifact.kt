package com.wuxianggujun.tinaide.core.compile.artifact

import kotlinx.serialization.Serializable
import java.io.File

/**
 * 构建产物:IDE 中的"一等公民",持久化到 `<buildDir>/.tina/artifacts/<storageKey>.json`。
 *
 * - [absolutePath] 产物文件绝对路径(跨重启仍有效)
 * - [contentHash] 产物二进制 SHA-256 前 16 字节十六进制(用于跨编译去重、完整性校验)
 * - [fingerprint] 生成该产物时使用的全部输入参数指纹
 * - [sources] 参与编译的源文件引用,供 Planner 做 mtime/size 增量校验
 * - [compiledAt] epoch millis
 * - [buildTimeMs] 构建耗时(观测用)
 */
@Serializable
data class Artifact(
    val id: ArtifactId,
    val absolutePath: String,
    val kind: ArtifactKind,
    val contentHash: String,
    val fingerprint: BuildFingerprint,
    val sources: List<SourceRef>,
    val compiledAt: Long,
    val buildTimeMs: Long,
) {
    fun file(): File = File(absolutePath)
}
