package com.wuxianggujun.tinaide.core.compile.artifact

import java.io.File

/**
 * 收集参与增量判定的输入文件。
 *
 * 这里不区分“源码”还是“构建脚本”：
 * 只要文件变化会影响最终产物，就应进入 tracked inputs。
 */
internal object TrackedInputCollector {

    private val EXCLUDED_DIR_NAMES = setOf(
        ".git",
        ".gradle",
        ".idea",
        ".cxx",
        "build",
        "out",
    )

    fun collectMakeInputs(projectRoot: File, sourceExtensions: Set<String>): List<File> {
        val makefiles = listOf("Makefile", "makefile", "GNUmakefile")
            .map { File(projectRoot, it) }
        val sourceFiles = walkProjectFiles(projectRoot) { file ->
            file.extension.lowercase() in sourceExtensions
        }
        return mergeUniqueFiles(makefiles + sourceFiles)
    }

    fun collectCMakeInputs(projectRoot: File, targetSources: List<File>): List<File> {
        val cmakeScripts = walkProjectFiles(projectRoot) { file ->
            file.name == "CMakeLists.txt" || file.extension.lowercase() == "cmake"
        }
        return mergeUniqueFiles(targetSources + cmakeScripts)
    }

    private fun walkProjectFiles(
        projectRoot: File,
        predicate: (File) -> Boolean,
    ): List<File> = projectRoot.walkTopDown()
        .onEnter { dir ->
            dir == projectRoot || dir.name !in EXCLUDED_DIR_NAMES
        }
        .filter { it.isFile && predicate(it) }
        .toList()

    private fun mergeUniqueFiles(files: List<File>): List<File> {
        val unique = linkedMapOf<String, File>()
        files.asSequence()
            .filter { it.isFile }
            .map { it.absoluteFile }
            .sortedBy { normalizePath(it) }
            .forEach { file ->
                unique.putIfAbsent(normalizePath(file), file)
            }
        return unique.values.toList()
    }

    private fun normalizePath(file: File): String = file.absolutePath.replace(File.separatorChar, '/')
}
