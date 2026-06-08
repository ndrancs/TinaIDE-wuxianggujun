package com.wuxianggujun.tinaide.core.lsp

import com.wuxianggujun.tinaide.core.util.NativeExecutableRunner
import com.wuxianggujun.tinaide.project.NativeBuildFlagTokenizer
import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * 为 clangd 准备可消费的 compile_commands。
 *
 * 重点修正两类 Android 原生环境特有问题：
 * 1. 构建时的 linker64 外层包装不能直接暴露给 clangd。
 * 2. clang builtin headers 需要显式的 resource-dir 才能稳定解析 stdarg.h 等头文件。
 */
object CompileCommandsNormalizer {

    private val json = Json

    data class ToolchainPaths(
        val clangPath: String? = null,
        val clangppPath: String? = null,
        val resourceDir: File? = null,
    )

    fun normalizeForClangd(
        sourceFile: File,
        targetFile: File,
        toolchainPaths: ToolchainPaths,
    ): Boolean {
        if (!sourceFile.isFile || sourceFile.length() <= 0L) return false

        val content = sourceFile.readText()
        val normalized = normalizeContent(content, toolchainPaths)

        targetFile.parentFile?.mkdirs()
        if (!targetFile.exists() || targetFile.readText() != normalized) {
            targetFile.writeText(normalized)
        }
        return targetFile.isFile && targetFile.length() > 0L
    }

    private fun normalizeContent(content: String, toolchainPaths: ToolchainPaths): String {
        val entries = json.parseToJsonElement(content).jsonArray
        val normalizedEntries = buildJsonArray {
            entries.forEach { element ->
                val entry = element.jsonObject
                add(normalizeEntry(entry, toolchainPaths))
            }
        }
        return normalizedEntries.toString()
    }

    private fun normalizeEntry(entry: JsonObject, toolchainPaths: ToolchainPaths): JsonObject {
        val filePath = entry["file"]?.jsonPrimitive?.contentOrNull.orEmpty()
        val arguments = normalizeArguments(extractArguments(entry), filePath, toolchainPaths)

        return buildJsonObject {
            entry.forEach { (key, value) ->
                if (key != "arguments" && key != "command") {
                    put(key, value)
                }
            }
            put("arguments", JsonArray(arguments.map(::JsonPrimitive)))
        }
    }

    private fun normalizeArguments(
        rawArgs: List<String>,
        filePath: String,
        toolchainPaths: ToolchainPaths,
    ): List<String> {
        val args = rawArgs.toMutableList()
        if (args.isEmpty()) return args

        unwrapCompilerLauncher(args)

        val fallbackCompiler = resolveFallbackCompiler(filePath, toolchainPaths)
        if (args.isEmpty()) {
            fallbackCompiler?.let(args::add)
        } else if (isCompilerLauncher(args.first())) {
            fallbackCompiler?.let { args[0] = it }
        } else if (looksLikeCompilerShim(args.first())) {
            fallbackCompiler?.let { args[0] = it }
        }

        toolchainPaths.resourceDir?.absolutePath
            ?.takeIf { it.isNotBlank() }
            ?.let { ensureResourceDir(args, filePath, it) }

        return args
    }

    private fun unwrapCompilerLauncher(args: MutableList<String>) {
        if (args.size >= 2 && isCompilerLauncher(args[0]) && looksLikeCompilerBinary(args[1])) {
            args.removeAt(0)
            return
        }
        if (args.size >= 2 && looksLikeSystemShell(args[0]) && looksLikeCompilerShim(args[1])) {
            args.removeAt(0)
        }
    }

    private fun ensureResourceDir(args: MutableList<String>, filePath: String, resourceDir: String) {
        val index = findResourceDirIndex(args)
        when {
            index >= 0 && index + 1 < args.size && args[index] == "-resource-dir" -> {
                args[index + 1] = resourceDir
                return
            }
            index >= 0 && args[index].startsWith("-resource-dir=") -> {
                args[index] = "-resource-dir=$resourceDir"
                return
            }
        }

        val insertionIndex = findResourceDirInsertionIndex(args, filePath)
        args.add(insertionIndex, resourceDir)
        args.add(insertionIndex, "-resource-dir")
    }

    private fun findResourceDirIndex(args: List<String>): Int = args.indexOfFirst { it == "-resource-dir" || it.startsWith("-resource-dir=") }

    private fun findResourceDirInsertionIndex(args: List<String>, filePath: String): Int {
        val separatorIndex = args.indexOf("--")
        if (separatorIndex >= 0) return separatorIndex

        val sourceIndex = args.indexOfLast { token ->
            token == filePath ||
                token.endsWith(".c") ||
                token.endsWith(".cc") ||
                token.endsWith(".cpp") ||
                token.endsWith(".cxx") ||
                token.endsWith(".m") ||
                token.endsWith(".mm")
        }
        return if (sourceIndex >= 0) sourceIndex else args.size
    }

    private fun extractArguments(entry: JsonObject): List<String> {
        val arguments = entry["arguments"]
            ?.jsonArray
            ?.mapNotNull { it.jsonPrimitive.contentOrNull }
            ?.takeIf { it.isNotEmpty() }
        if (arguments != null) return arguments

        val command = entry["command"]?.jsonPrimitive?.contentOrNull ?: return emptyList()
        return NativeBuildFlagTokenizer.tokenize(command)
    }

    private fun resolveFallbackCompiler(
        filePath: String,
        toolchainPaths: ToolchainPaths,
    ): String? {
        val extension = File(filePath).extension.lowercase()
        return when (extension) {
            "c", "m" -> toolchainPaths.clangPath ?: toolchainPaths.clangppPath
            else -> toolchainPaths.clangppPath ?: toolchainPaths.clangPath
        }
    }

    private fun looksLikeCompilerBinary(path: String): Boolean {
        val name = File(path).name.lowercase()
        return name in setOf("clang", "clang++", "cc", "c++") ||
            Regex("""^clang(\+\+)?(-\d+)?$""").matches(name)
    }

    private fun looksLikeCompilerShim(path: String): Boolean {
        val normalized = path.replace('\\', '/')
        return "/toolchain-shims/" in normalized && looksLikeCompilerBinary(path)
    }

    private fun looksLikeSystemShell(path: String): Boolean {
        val normalized = path.replace('\\', '/')
        return normalized == "/system/bin/sh" || normalized.endsWith("/bin/sh")
    }

    private fun isCompilerLauncher(path: String): Boolean = NativeExecutableRunner.isCompilerLauncher(path)
}
