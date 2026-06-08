package com.wuxianggujun.tinaide.core.compile.artifact

import kotlinx.serialization.Serializable

/**
 * 产物身份标识。同一 projectId + targetName + variant 对应同一个产物槽位。
 *
 * - [projectId] 项目稳定标识(建议用项目根目录绝对路径的 SHA-256 前 16 字节)
 * - [targetName] CMake target 名或单文件编译的源文件 basename
 * - [variant] 变体,区分同 target 不同输出(如 Debug/Release、arm64/x86_64),默认 `default`
 */
@Serializable
data class ArtifactId(
    val projectId: String,
    val targetName: String,
    val variant: String = "default",
) {
    fun storageKey(): String = "$targetName.$variant"
}
