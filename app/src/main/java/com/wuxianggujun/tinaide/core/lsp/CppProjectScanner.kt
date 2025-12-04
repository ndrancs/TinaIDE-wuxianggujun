package com.wuxianggujun.tinaide.core.lsp

import java.io.File

/**
 * 扫描 C/C++ 项目的辅助工具。
 * 负责收集源文件与常见的 include 目录，供生成 compile_commands.json 等场景复用。
 */
object CppProjectScanner {

    private val SOURCE_EXTENSIONS = setOf("c", "cpp", "cc", "cxx", "m", "mm")
    private val HEADER_EXTENSIONS = setOf("h", "hpp", "hh", "hxx", "inl")
    private val INCLUDE_DIR_NAMES = setOf("include", "includes", "inc", "headers")
    private val SKIP_DIRECTORIES = setOf("build", ".git", ".idea", ".gradle", "out", "external", "obj")

    data class ScanResult(
        val sourceFiles: List<String>,
        val includeDirs: List<String>,
        val hasCppSources: Boolean
    )

    fun scanProject(projectPath: String): ScanResult {
        val projectDir = File(projectPath)
        if (!projectDir.exists() || !projectDir.isDirectory) {
            return ScanResult(emptyList(), emptyList(), false)
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
            ext == "cpp" || ext == "cc" || ext == "cxx"
        }

        return ScanResult(
            sourceFiles = sources.toList(),
            includeDirs = includeDirs.filter { it.isNotEmpty() }.distinct(),
            hasCppSources = hasCpp
        )
    }

    fun collectSourceFiles(projectPath: String): List<String> =
        scanProject(projectPath).sourceFiles

    fun collectIncludeDirs(projectPath: String): List<String> =
        scanProject(projectPath).includeDirs

    fun hasCppSources(sourceFiles: List<String>): Boolean {
        return sourceFiles.any { file ->
            val ext = file.substringAfterLast('.', "")
            ext.equals("cpp", true) || ext.equals("cc", true) || ext.equals("cxx", true)
        }
    }
}
