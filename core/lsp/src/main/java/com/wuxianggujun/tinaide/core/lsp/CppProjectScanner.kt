package com.wuxianggujun.tinaide.core.lsp

import com.wuxianggujun.tinaide.core.lang.CxxFileSupport
import java.io.File

/**
 * 扫描 C/C++ 项目的辅助工具。
 * 负责收集源文件与常见的 include 目录，供生成 compile_commands.json 等场景复用。
 */
object CppProjectScanner {

    private val SOURCE_EXTENSIONS: Set<String> = CxxFileSupport.clangdTranslationUnitExtensions
    private val HEADER_EXTENSIONS: Set<String> = CxxFileSupport.headerExtensions
    private val INCLUDE_DIR_NAMES = setOf("include", "includes", "inc", "headers")
    private val SKIP_DIRECTORIES = setOf("build", ".git", ".idea", ".gradle", "out", "external", "obj")

    enum class ScanMode {
        /** 全量递归扫描（最准确，但在大项目中开销较大） */
        FULL,

        /**
         * LSP 轻量扫描：尽量避免递归 walkTopDown，仅收集必要的 include 目录与当前文件。
         *
         * 适用场景：单文件/小项目打开时，为生成 compile_commands.json 服务。
         */
        LSP_SHALLOW,
    }

    data class ScanResult(
        val sourceFiles: List<String>,
        val includeDirs: List<String>,
        val hasCppSources: Boolean
    )

    fun scanProject(
        projectPath: String,
        mode: ScanMode = ScanMode.FULL,
        primaryFile: File? = null
    ): ScanResult {
        val projectDir = File(projectPath)
        if (!projectDir.exists() || !projectDir.isDirectory) {
            return ScanResult(emptyList(), emptyList(), false)
        }
        if (mode == ScanMode.LSP_SHALLOW) {
            return scanProjectShallow(projectDir, primaryFile)
        }
        val sources = linkedSetOf<String>()
        val includeDirs = linkedSetOf<String>()

        projectDir.walkTopDown()
            .onEnter { dir ->
                if (dir == projectDir) return@onEnter true
                val name = dir.name
                if (name.startsWith(".") && name.length > 1) {
                    return@onEnter false
                }
                if (SKIP_DIRECTORIES.contains(name.lowercase())) {
                    return@onEnter false
                }
                true
            }
            .forEach { file ->
                if (file.isDirectory) {
                    if (INCLUDE_DIR_NAMES.contains(file.name.lowercase())) {
                        includeDirs += file.absolutePath
                    }
                    return@forEach
                }
                if (!file.isFile) return@forEach
                val ext = file.extension.lowercase()
                when {
                    SOURCE_EXTENSIONS.contains(ext) -> sources += file.absolutePath
                    HEADER_EXTENSIONS.contains(ext) -> file.parentFile?.absolutePath?.let { includeDirs += it }
                }
            }

        includeDirs += projectPath
        val hasCpp = sources.any { path ->
            val ext = path.substringAfterLast('.', "").lowercase()
            CxxFileSupport.isCxxSourceExtension(ext) || ext in CxxFileSupport.objcxxSourceExtensions
        }

        return ScanResult(
            sourceFiles = sources.toList(),
            includeDirs = includeDirs.filter { it.isNotEmpty() }.distinct(),
            hasCppSources = hasCpp
        )
    }

    private fun scanProjectShallow(projectDir: File, primaryFile: File?): ScanResult {
        val sources = linkedSetOf<String>()
        val includeDirs = linkedSetOf<String>()

        primaryFile
            ?.takeIf { it.isFile }
            ?.takeIf { it.extension.lowercase() in SOURCE_EXTENSIONS }
            ?.absolutePath
            ?.let { sources += it }

        includeDirs += projectDir.absolutePath
        primaryFile?.parentFile?.takeIf { it.isDirectory }?.absolutePath?.let { includeDirs += it }

        fun shouldEnter(dir: File): Boolean {
            val name = dir.name
            if (name.startsWith(".") && name.length > 1) return false
            if (SKIP_DIRECTORIES.contains(name.lowercase())) return false
            return true
        }

        // 深度 1：根目录下的常见 include 目录
        val level1Dirs = projectDir.listFiles()?.filter { it.isDirectory && shouldEnter(it) }.orEmpty()
        for (dir in level1Dirs) {
            if (INCLUDE_DIR_NAMES.contains(dir.name.lowercase())) {
                includeDirs += dir.absolutePath
            }
        }

        // 深度 2：例如 src/include 或 lib/foo/include
        for (dir in level1Dirs) {
            val children = dir.listFiles()?.filter { it.isDirectory && shouldEnter(it) }.orEmpty()
            for (child in children) {
                if (INCLUDE_DIR_NAMES.contains(child.name.lowercase())) {
                    includeDirs += child.absolutePath
                }
            }
        }

        val hasCpp = sources.any { path ->
            val ext = path.substringAfterLast('.', "").lowercase()
            CxxFileSupport.isCxxSourceExtension(ext) || ext in CxxFileSupport.objcxxSourceExtensions
        }

        return ScanResult(
            sourceFiles = sources.toList(),
            includeDirs = includeDirs.filter { it.isNotEmpty() }.distinct(),
            hasCppSources = hasCpp,
        )
    }

    fun collectSourceFiles(projectPath: String): List<String> = scanProject(projectPath).sourceFiles

    fun collectIncludeDirs(projectPath: String): List<String> = scanProject(projectPath).includeDirs

    fun hasCppSources(sourceFiles: List<String>): Boolean = sourceFiles.any { file ->
        val ext = file.substringAfterLast('.', "")
        CxxFileSupport.isCxxSourceExtension(ext) || ext.equals("mm", true)
    }
}
