package com.wuxianggujun.tinaide.core.lsp

import java.io.File

/**
 * 扫描 C/C++ 项目的辅助工具。
 * 负责收集源文件与常见的 include 目录，供生成 compile_commands.json 等场景复用。
 */
object CppProjectScanner {
    private val SOURCE_EXTENSIONS = setOf("c", "cpp", "cc", "cxx")
    private val INCLUDE_DIR_NAMES = setOf("include", "includes", "inc", "src", "headers")

    fun collectSourceFiles(projectPath: String): List<String> {
        val projectDir = File(projectPath)
        if (!projectDir.exists() || !projectDir.isDirectory) {
            return emptyList()
        }
        return projectDir.walkTopDown()
            .filter { it.isFile && it.extension.lowercase() in SOURCE_EXTENSIONS }
            .map { it.absolutePath }
            .toList()
    }

    fun collectIncludeDirs(projectPath: String): List<String> {
        val includeDirs = mutableListOf<String>()
        val projectDir = File(projectPath)
        if (!projectDir.exists() || !projectDir.isDirectory) {
            return includeDirs
        }

        projectDir.walkTopDown()
            .filter { it.isDirectory && it.name.lowercase() in INCLUDE_DIR_NAMES }
            .forEach { includeDirs.add(it.absolutePath) }

        includeDirs.add(projectPath)
        return includeDirs.distinct()
    }

    fun hasCppSources(sourceFiles: List<String>): Boolean {
        return sourceFiles.any { file ->
            val ext = file.substringAfterLast('.', "")
            ext.equals("cpp", true) || ext.equals("cc", true) || ext.equals("cxx", true)
        }
    }
}
