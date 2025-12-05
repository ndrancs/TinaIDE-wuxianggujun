package com.wuxianggujun.tinaide.core.lsp

import android.util.Log
import java.io.File

/**
 * 负责生成 clangd 需要的 compile_commands.json。
 * 脱离旧的 LspEditorManager，便于 Native LSP 与工具链复用。
 */
object CompileCommandsGenerator {

    private const val TAG = "CompileCommandsGen"
    private const val DEFAULT_TARGET = "aarch64-linux-android28"

    enum class BuildVariant(val dirName: String) {
        Debug("debug"),
        Release("release")
    }

    fun getCompileCommandsFile(
        projectPath: String,
        variant: BuildVariant = BuildVariant.Debug
    ): File {
        val buildDir = File(File(projectPath, "build"), variant.dirName)
        if (!buildDir.exists()) {
            buildDir.mkdirs()
        }
        return File(buildDir, "compile_commands.json")
    }

    fun generate(
        projectPath: String,
        sysrootDir: File?,
        sourceFiles: List<String>,
        includeDirs: List<String>,
        defines: List<String> = emptyList(),
        isCxx: Boolean = true,
        target: String = DEFAULT_TARGET,
        variant: BuildVariant = BuildVariant.Debug
    ): File {
        val compileCommandsFile = getCompileCommandsFile(projectPath, variant)
        val compileDir = compileCommandsFile.parentFile
            ?: throw IllegalStateException("compile_commands.json must reside inside a directory")
        if (!compileDir.exists()) {
            compileDir.mkdirs()
        }
        val objDir = File(compileDir, "obj")
        if (!objDir.exists()) {
            objDir.mkdirs()
        }

        val sysrootPath = sysrootDir?.absolutePath
        val resourceDir = sysrootDir?.let { findClangResourceDir(it) }
        val resourceIncludeDir = resourceDir?.let { File(it, "include") }?.takeIf { it.exists() }
        if (sysrootDir != null && resourceDir == null) {
            Log.w(TAG, "Clang resource dir missing under ${sysrootDir.absolutePath}/lib/clang")
        }
        val tripleBase = deriveTripleBase(target)
        val apiLevel = deriveApiLevel(target)
        val resolvedIncludeDirs = (includeDirs + projectPath).distinct()

        val commands = sourceFiles.map { sourceFile ->
            val args = mutableListOf<String>().apply {
                add(if (isCxx) "clang++" else "clang")
                add("-target")
                add(target)
                sysrootPath?.let { sysroot ->
                    add("--sysroot=$sysroot")
                }
                resourceDir?.let { dir ->
                    add("-resource-dir")
                    add(dir.absolutePath)
                }
                // 关键：C++ 标准库头文件必须在所有其他系统头文件之前
                // 这样 libc++ 的 <ctype.h> 包装器才能被正确找到
                if (isCxx) {
                    sysrootPath?.let { sysroot ->
                        add("-isystem")
                        add("$sysroot/usr/include/c++/v1")
                    }
                }
                // clang 内置头文件（stdarg.h 等）
                resourceIncludeDir?.let { include ->
                    add("-isystem")
                    add(include.absolutePath)
                }
                if (isCxx) {
                    add("-std=c++17")
                }
                add("-c")
                add(sourceFile)
                add("-DANDROID")
                add("-D__ANDROID__")
                sysrootPath?.let { sysroot ->
                    add("-D__ANDROID_API__=$apiLevel")
                    // C 标准库和架构特定头文件放在最后
                    add("-isystem")
                    add("$sysroot/usr/include")
                    if (tripleBase.isNotEmpty()) {
                        add("-isystem")
                        add("$sysroot/usr/include/$tripleBase")
                    }
                }
                resolvedIncludeDirs.forEach { dir ->
                    add("-I$dir")
                }
                defines.forEach { define ->
                    add("-D$define")
                }
                val objFile = File(objDir, File(sourceFile).nameWithoutExtension + ".o")
                add("-o")
                add(objFile.absolutePath)
            }
            mapOf(
                "directory" to projectPath,
                "file" to sourceFile,
                "arguments" to args
            )
        }

        val json = buildString {
            append("[\n")
            commands.forEachIndexed { index, cmd ->
                append("  {\n")
                append("    \"directory\": \"${cmd["directory"]}\",\n")
                append("    \"file\": \"${cmd["file"]}\",\n")
                append("    \"arguments\": [\n")
                @Suppress("UNCHECKED_CAST")
                val args = cmd["arguments"] as List<String>
                args.forEachIndexed { argIndex, arg ->
                    append("      \"${escape(arg)}\"")
                    if (argIndex < args.size - 1) append(",")
                    append("\n")
                }
                append("    ]\n")
                append("  }")
                if (index < commands.size - 1) append(",")
                append("\n")
            }
            append("]\n")
        }

        compileCommandsFile.writeText(json)
        Log.i(TAG, "Generated compile_commands.json at: ${compileCommandsFile.absolutePath}")
        return compileCommandsFile
    }

    private fun deriveTripleBase(target: String): String {
        if (target.isEmpty()) return ""
        return target.trimEnd { it.isDigit() }
    }

    private fun deriveApiLevel(target: String): String {
        val digits = target.takeLastWhile { it.isDigit() }
        return digits.ifEmpty { "24" }
    }

    private fun findClangResourceDir(sysrootDir: File): File? {
        val clangRoot = File(sysrootDir, "lib/clang")
        if (!clangRoot.isDirectory) return null
        val versionDirs = clangRoot.listFiles { file ->
            file.isDirectory && file.name.firstOrNull()?.isDigit() == true
        } ?: return null
        return versionDirs.maxWithOrNull { left, right ->
            compareVersionNames(left.name, right.name)
        }
    }

    private fun compareVersionNames(a: String, b: String): Int {
        val aTokens = a.split('.')
        val bTokens = b.split('.')
        val maxSize = maxOf(aTokens.size, bTokens.size)
        for (i in 0 until maxSize) {
            val ai = aTokens.getOrNull(i)?.toIntOrNull() ?: -1
            val bi = bTokens.getOrNull(i)?.toIntOrNull() ?: -1
            if (ai != bi) {
                return ai.compareTo(bi)
            }
        }
        return 0
    }

    private fun escape(value: String): String =
        value.replace("\\", "\\\\").replace("\"", "\\\"")
}
