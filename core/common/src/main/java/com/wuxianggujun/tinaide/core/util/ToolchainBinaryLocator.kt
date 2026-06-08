package com.wuxianggujun.tinaide.core.util

import java.io.File

/**
 * 工具链二进制定位器（支持失败降级）
 *
 * 目标：在不依赖 update-alternatives / 软链接的前提下，找到可用的 clang/clang++/clangd 可执行文件路径。
 */
object ToolchainBinaryLocator {

    fun findClangdExecutable(rootfsDir: File): String? = findBinary(rootfsDir, baseName = "clangd", includeLlvmBinDir = true)

    fun findLldbExecutable(rootfsDir: File): String? = findBinary(rootfsDir, baseName = "lldb", includeLlvmBinDir = true)

    fun findClangExecutable(rootfsDir: File): String? = findBinary(rootfsDir, baseName = "clang", includeLlvmBinDir = true)

    fun findClangPlusPlusExecutable(rootfsDir: File): String? = findBinary(rootfsDir, baseName = "clang++", includeLlvmBinDir = true)

    fun findClangFormatExecutable(rootfsDir: File): String? = findBinary(rootfsDir, baseName = "clang-format", includeLlvmBinDir = true)

    /**
     * 检测已安装的 LLVM 版本列表
     *
     * 扫描 /usr/bin/clang-* 文件，提取版本号
     *
     * @param rootfsDir rootfs 目录
     * @return 已安装版本列表（高版本优先）
     */
    fun detectInstalledVersions(rootfsDir: File): List<Int> {
        val usrBin = File(rootfsDir, "usr/bin")
        if (!usrBin.isDirectory) return emptyList()

        return usrBin.listFiles()
            ?.asSequence()
            ?.filter { file ->
                // 匹配 clang-<version>，排除 clang++、clang-format 等
                file.name.startsWith("clang-") &&
                    !file.name.contains("++") &&
                    !file.name.contains("format") &&
                    !file.name.contains("tidy") &&
                    !file.name.contains("check")
            }
            ?.mapNotNull { file ->
                // clang-19 → 19
                file.name.removePrefix("clang-").toIntOrNull()
            }
            ?.sorted()
            ?.toList()
            ?.reversed() // 高版本优先
            ?: emptyList()
    }

    /**
     * 获取指定版本的工具路径
     *
     * @param rootfsDir rootfs 目录
     * @param toolName 工具名称（如 "clang", "clangd", "lldb"）
     * @param version LLVM 版本号
     * @return 工具路径，如果不存在则返回 null
     */
    fun getVersionedToolPath(rootfsDir: File, toolName: String, version: Int): String? {
        val versionedPath = "/usr/bin/$toolName-$version"
        val hostFile = File(rootfsDir, "usr/bin/$toolName-$version")
        return if (hostFile.exists()) versionedPath else null
    }

    /**
     * 检查指定版本是否已安装
     *
     * @param rootfsDir rootfs 目录
     * @param version LLVM 版本号
     * @return 是否已安装
     */
    fun isVersionInstalled(rootfsDir: File, version: Int): Boolean {
        val clangFile = File(rootfsDir, "usr/bin/clang-$version")
        return clangFile.exists()
    }

    private fun findBinary(
        rootfsDir: File,
        baseName: String,
        preferredLlvmVersions: List<Int> = ClangResourceDirLocator.DEFAULT_LLVM_VERSIONS,
        includeLlvmBinDir: Boolean,
    ): String? {
        if (!rootfsDir.isDirectory) return null

        // 先查无版本路径（保持对现有实现最友好）
        val direct = "/usr/bin/$baseName"
        if (hostFile(rootfsDir, direct).isFile) return direct

        // 再按版本降级查找（/usr/bin/<name>-<ver>）
        for (version in preferredLlvmVersions) {
            val versioned = "/usr/bin/$baseName-$version"
            if (hostFile(rootfsDir, versioned).isFile) return versioned
        }

        // 再尝试 LLVM 安装布局（/usr/lib/llvm-<ver>/bin/<name>）
        if (includeLlvmBinDir) {
            for (version in preferredLlvmVersions) {
                val candidates = listOf(
                    "/usr/lib/llvm-$version/bin/$baseName",
                    "/usr/lib/llvm$version/bin/$baseName"
                )
                candidates.firstOrNull { hostFile(rootfsDir, it).isFile }?.let { return it }
            }
        }

        return null
    }

    private fun hostFile(rootfsDir: File, guestPath: String): File = File(rootfsDir, guestPath.trimStart('/'))
}
