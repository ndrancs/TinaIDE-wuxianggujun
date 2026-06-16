package com.wuxianggujun.tinaide.core.util

import java.io.File

object ClangResourceDirLocator {

    /**
     * 支持的 LLVM 版本列表（按优先级排序，从新到旧）
     *
     * 注意：这里只用于快速定位常见版本；如果这些版本都未命中，仍会走 fallback scan。
     */
    val DEFAULT_LLVM_VERSIONS: List<Int> = listOf(22, 21, 20, 19, 18, 17, 16, 15, 14)

    /**
     * 查找 clang resource directory（用于 clang 内置头文件，如 stdarg.h、stddef.h 等）
     *
     * Ubuntu/Debian 常见路径模式：
     * - /usr/lib/llvm-{version}/lib/clang/{version}
     * - /usr/lib/llvm{version}/lib/clang/{version}
     * - /usr/lib/clang/{version}
     * - /lib/clang/{version}
     *
     * 如果按版本号未命中，则会在 /usr/lib/clang 与 /lib/clang 下扫描任意版本目录并取最高版本。
     */
    fun find(
        rootfsDir: File,
        preferredLlvmVersions: List<Int> = DEFAULT_LLVM_VERSIONS
    ): File? {
        if (!rootfsDir.isDirectory) return null

        for (version in preferredLlvmVersions) {
            val llvmDashBase = File(rootfsDir, "usr/lib/llvm-$version/lib/clang")
            if (llvmDashBase.isDirectory) {
                File(llvmDashBase, version.toString()).takeIf { it.isDirectory }?.let { return it }
                scanVersionDirs(llvmDashBase)?.let { return it }
            }

            val llvmNoDashBase = File(rootfsDir, "usr/lib/llvm$version/lib/clang")
            if (llvmNoDashBase.isDirectory) {
                File(llvmNoDashBase, version.toString()).takeIf { it.isDirectory }?.let { return it }
                scanVersionDirs(llvmNoDashBase)?.let { return it }
            }

            File(rootfsDir, "usr/lib/clang/$version").takeIf { it.isDirectory }?.let { return it }
            File(rootfsDir, "lib/clang/$version").takeIf { it.isDirectory }?.let { return it }
        }

        val fallbackRoots = sequenceOf(
            File(rootfsDir, "lib/clang"),
            File(rootfsDir, "usr/lib/clang")
        ).filter { it.isDirectory }

        val versionDirs = fallbackRoots.flatMap { base ->
            base.listFiles()?.asSequence().orEmpty().filter { it.isDirectory && it.name.matches(Regex("\\d+(\\.\\d+)*")) }
        }

        return versionDirs.maxWithOrNull { left, right -> compareVersionNames(left.name, right.name) }
    }

    private fun scanVersionDirs(baseDir: File): File? {
        val versionDirs = baseDir.listFiles()
            ?.asSequence()
            ?.filter { it.isDirectory && it.name.matches(Regex("\\d+(\\.\\d+)*")) }
            ?: return null
        return versionDirs.maxWithOrNull { left, right -> compareVersionNames(left.name, right.name) }
    }

    private fun compareVersionNames(a: String, b: String): Int {
        val aTokens = a.split('.')
        val bTokens = b.split('.')
        val maxSize = maxOf(aTokens.size, bTokens.size)
        for (i in 0 until maxSize) {
            val ai = aTokens.getOrNull(i)?.toIntOrNull() ?: -1
            val bi = bTokens.getOrNull(i)?.toIntOrNull() ?: -1
            if (ai != bi) return ai.compareTo(bi)
        }
        return 0
    }
}
