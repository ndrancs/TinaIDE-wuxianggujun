package com.wuxianggujun.tinaide.core.proot

import android.content.Context
import com.wuxianggujun.tinaide.core.compile.CompilerType
import com.wuxianggujun.tinaide.core.util.ToolchainBinaryLocator
import java.io.File

/**
 * PRoot rootfs 内工具路径解析器。
 *
 * 仅基于当前 rootfs 的真实文件布局做动态发现，
 * 不再依赖已移除的 manifest / symlink 兼容层。
 */
class ToolchainPathResolver(context: Context) {

    private val appContext = context.applicationContext

    companion object {
        private const val DEFAULT_CLANG = "/usr/bin/clang"
        private const val DEFAULT_CLANGPP = "/usr/bin/clang++"
        private const val DEFAULT_CLANGD = "/usr/bin/clangd"
        private const val DEFAULT_CLANG_FORMAT = "/usr/bin/clang-format"
        private const val DEFAULT_LLDB = "/usr/bin/lldb"
        private const val DEFAULT_GCC = "/usr/bin/gcc"
        private const val DEFAULT_GPP = "/usr/bin/g++"
    }

    data class ResolvedPaths(
        val clang: String,
        val clangpp: String,
        val clangd: String,
        val clangFormat: String,
        val lldb: String,
        val gcc: String,
        val gpp: String,
    )

    @Volatile
    private var cachedPaths: ResolvedPaths? = null

    @Volatile
    private var cachedRootfsPath: String? = null

    fun resolve(): ResolvedPaths {
        val rootfsPath = PRootBootstrap.getActiveRootfsPath(appContext)
        cachedPaths?.takeIf { cachedRootfsPath == rootfsPath }?.let { return it }

        val rootfsDir = File(rootfsPath)
        val clang = ToolchainBinaryLocator.findClangExecutable(rootfsDir) ?: DEFAULT_CLANG
        val paths = ResolvedPaths(
            clang = clang,
            clangpp = ToolchainBinaryLocator.findClangPlusPlusExecutable(rootfsDir) ?: clang,
            clangd = ToolchainBinaryLocator.findClangdExecutable(rootfsDir) ?: DEFAULT_CLANGD,
            clangFormat = ToolchainBinaryLocator.findClangFormatExecutable(rootfsDir) ?: DEFAULT_CLANG_FORMAT,
            lldb = ToolchainBinaryLocator.findLldbExecutable(rootfsDir) ?: DEFAULT_LLDB,
            gcc = DEFAULT_GCC,
            gpp = DEFAULT_GPP,
        )

        cachedPaths = paths
        cachedRootfsPath = rootfsPath
        return paths
    }

    fun getInstalledVersions(): List<Int> {
        val rootfsDir = File(PRootBootstrap.getActiveRootfsPath(appContext))
        return ToolchainBinaryLocator.detectInstalledVersions(rootfsDir)
    }

    fun invalidateCache() {
        cachedPaths = null
        cachedRootfsPath = null
    }

    fun getCCompiler(compilerType: CompilerType, customCCompiler: String? = null): String = when (compilerType) {
        CompilerType.CLANG -> resolve().clang
        CompilerType.GCC -> resolve().gcc
        CompilerType.CUSTOM -> customCCompiler?.takeIf { it.isNotBlank() }
            ?: throw IllegalArgumentException("Custom C compiler path is empty")
    }

    fun getCppCompiler(compilerType: CompilerType, customCppCompiler: String? = null): String = when (compilerType) {
        CompilerType.CLANG -> resolve().clangpp
        CompilerType.GCC -> resolve().gpp
        CompilerType.CUSTOM -> customCppCompiler?.takeIf { it.isNotBlank() }
            ?: throw IllegalArgumentException("Custom C++ compiler path is empty")
    }

    fun getClangd(): String = resolve().clangd

    fun getClangFormat(): String = resolve().clangFormat

    fun getLldb(): String = resolve().lldb
}
