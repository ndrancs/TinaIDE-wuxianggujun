/*
 * CMake Parser API for TinaIDE
 * Copyright (C) 2024 wuxianggujun
 *
 * 统一的解析器 API 入口
 */

package com.wuxianggujun.tinaide.cmake

import com.wuxianggujun.tinaide.cmake.command.CMakeCommand
import com.wuxianggujun.tinaide.cmake.command.CommandParser
import com.wuxianggujun.tinaide.cmake.parser.CMakeDocument
import com.wuxianggujun.tinaide.cmake.parser.CMakeParser
import com.wuxianggujun.tinaide.cmake.parser.CommandInvocation

/**
 * CMake 解析器 API
 *
 * 使用示例:
 * ```kotlin
 * val cmake = CMake.parse(source)
 * if (cmake.isSuccess) {
 *     val doc = cmake.getOrThrow()
 *
 *     // 获取所有命令
 *     doc.commands.forEach { cmd ->
 *         println("${cmd.name}: ${cmd.rawArguments}")
 *     }
 *
 *     // 获取项目信息
 *     doc.projectName?.let { println("Project: $it") }
 *     doc.projectVersion?.let { println("Version: $it") }
 *
 *     // 获取所有目标
 *     doc.targets.forEach { target ->
 *         println("Target: ${target.name} (${target.type})")
 *     }
 * }
 * ```
 */
object CMake {

    /**
     * 解析 CMake 源码
     *
     * @param source CMake 源码字符串
     * @return 解析结果，包含结构化的 CMake 文档
     */
    fun parse(source: String): Result<CMakeDoc> = CMakeParser.parse(source).map { doc ->
        CMakeDoc(doc)
    }

    /**
     * 解析 CMake 文件（从字节数组）
     */
    fun parse(source: ByteArray): Result<CMakeDoc> = parse(source.decodeToString())
}

/**
 * 高级 CMake 文档表示
 * 提供对解析结果的便捷访问
 */
class CMakeDoc internal constructor(private val rawDoc: CMakeDocument) {

    /**
     * 原始文档
     */
    val document: CMakeDocument get() = rawDoc

    /**
     * 源码
     */
    val source: String get() = rawDoc.source

    /**
     * 所有命令（解析为结构化对象）
     */
    val commands: List<CMakeCommand> by lazy {
        rawDoc.commands().map { CommandParser.parse(it) }
    }

    /**
     * 原始命令调用列表
     */
    val rawCommands: List<CommandInvocation> get() = rawDoc.commands()

    // ========== 项目信息 ==========

    /**
     * 项目名称（从 project() 命令提取）
     */
    val projectName: String? by lazy {
        rawDoc.commandsByName("project").firstOrNull()?.arguments?.getOrNull(0)?.text
    }

    /**
     * 项目版本
     */
    val projectVersion: String? by lazy {
        val projectCmd = rawDoc.commandsByName("project").firstOrNull() ?: return@lazy null
        val args = projectCmd.arguments
        val versionIndex = args.indexOfFirst { it.text == "VERSION" }
        if (versionIndex >= 0) args.getOrNull(versionIndex + 1)?.text else null
    }

    /**
     * 最低 CMake 版本要求
     */
    val minimumVersion: String? by lazy {
        val cmd = rawDoc.commandsByName("cmake_minimum_required").firstOrNull() ?: return@lazy null
        val args = cmd.arguments
        val versionIndex = args.indexOfFirst { it.text == "VERSION" }
        if (versionIndex >= 0) args.getOrNull(versionIndex + 1)?.text else null
    }

    /**
     * 项目语言列表
     */
    val languages: List<String> by lazy {
        val projectCmd = rawDoc.commandsByName("project").firstOrNull() ?: return@lazy emptyList()
        val args = projectCmd.arguments
        val langIndex = args.indexOfFirst { it.text == "LANGUAGES" }
        if (langIndex >= 0) {
            args.drop(langIndex + 1)
                .takeWhile { it.text !in listOf("VERSION", "DESCRIPTION", "HOMEPAGE_URL") }
                .map { it.text }
        } else {
            // 没有 LANGUAGES 关键字时，VERSION 之后的参数可能是语言
            args.drop(1)
                .filter { it.text !in listOf("VERSION", "DESCRIPTION", "HOMEPAGE_URL") }
                .filter { !it.text.matches(Regex("[0-9].*")) }
                .map { it.text }
        }
    }

    // ========== 目标信息 ==========

    /**
     * 目标信息
     */
    data class TargetInfo(
        val name: String,
        val type: TargetType,
        val sources: List<String>
    )

    enum class TargetType {
        EXECUTABLE,
        STATIC_LIBRARY,
        SHARED_LIBRARY,
        MODULE_LIBRARY,
        OBJECT_LIBRARY,
        INTERFACE_LIBRARY,
        CUSTOM_TARGET,
        UNKNOWN
    }

    /**
     * 所有目标列表
     */
    val targets: List<TargetInfo> by lazy {
        val result = mutableListOf<TargetInfo>()

        // add_executable
        rawDoc.commandsByName("add_executable").forEach { cmd ->
            val name = cmd.arguments.getOrNull(0)?.text ?: return@forEach
            val isImported = cmd.arguments.any { it.text == "IMPORTED" }
            val isAlias = cmd.arguments.any { it.text == "ALIAS" }

            if (!isImported && !isAlias) {
                val sources = cmd.arguments.drop(1)
                    .filter { it.text !in listOf("WIN32", "MACOSX_BUNDLE", "EXCLUDE_FROM_ALL") }
                    .map { it.text }
                result.add(TargetInfo(name, TargetType.EXECUTABLE, sources))
            }
        }

        // add_library
        rawDoc.commandsByName("add_library").forEach { cmd ->
            val name = cmd.arguments.getOrNull(0)?.text ?: return@forEach
            val isImported = cmd.arguments.any { it.text == "IMPORTED" }
            val isAlias = cmd.arguments.any { it.text == "ALIAS" }

            if (!isImported && !isAlias) {
                val type = when {
                    cmd.arguments.any { it.text == "STATIC" } -> TargetType.STATIC_LIBRARY
                    cmd.arguments.any { it.text == "SHARED" } -> TargetType.SHARED_LIBRARY
                    cmd.arguments.any { it.text == "MODULE" } -> TargetType.MODULE_LIBRARY
                    cmd.arguments.any { it.text == "OBJECT" } -> TargetType.OBJECT_LIBRARY
                    cmd.arguments.any { it.text == "INTERFACE" } -> TargetType.INTERFACE_LIBRARY
                    else -> TargetType.STATIC_LIBRARY // 默认
                }
                val sources = cmd.arguments.drop(1)
                    .filter { it.text !in listOf("STATIC", "SHARED", "MODULE", "OBJECT", "INTERFACE", "EXCLUDE_FROM_ALL") }
                    .map { it.text }
                result.add(TargetInfo(name, type, sources))
            }
        }

        // add_custom_target
        rawDoc.commandsByName("add_custom_target").forEach { cmd ->
            val name = cmd.arguments.getOrNull(0)?.text ?: return@forEach
            result.add(TargetInfo(name, TargetType.CUSTOM_TARGET, emptyList()))
        }

        result
    }

    // ========== 变量信息 ==========

    /**
     * 变量定义
     */
    data class VariableDefinition(
        val name: String,
        val values: List<String>,
        val isCache: Boolean,
        val isParentScope: Boolean
    )

    /**
     * 所有变量定义
     */
    val variables: List<VariableDefinition> by lazy {
        rawDoc.commandsByName("set").mapNotNull { cmd ->
            val args = cmd.arguments
            if (args.isEmpty()) return@mapNotNull null

            val name = args[0].text
            val remaining = args.drop(1)

            val cacheIndex = remaining.indexOfFirst { it.text == "CACHE" }
            val isCache = cacheIndex >= 0
            val isParentScope = remaining.any { it.text == "PARENT_SCOPE" }

            val values = if (isCache) {
                remaining.take(cacheIndex).map { it.text }
            } else {
                remaining.filter { it.text != "PARENT_SCOPE" }.map { it.text }
            }

            VariableDefinition(name, values, isCache, isParentScope)
        }
    }

    // ========== 子目录信息 ==========

    /**
     * 包含的子目录
     */
    val subdirectories: List<String> by lazy {
        rawDoc.commandsByName("add_subdirectory").mapNotNull { cmd ->
            cmd.arguments.getOrNull(0)?.text
        }
    }

    // ========== 依赖信息 ==========

    /**
     * find_package 调用
     */
    data class PackageRequirement(
        val name: String,
        val version: String?,
        val isRequired: Boolean,
        val components: List<String>
    )

    /**
     * 所有包依赖
     */
    val packages: List<PackageRequirement> by lazy {
        rawDoc.commandsByName("find_package").mapNotNull { cmd ->
            val name = cmd.arguments.getOrNull(0)?.text ?: return@mapNotNull null
            val args = cmd.arguments.drop(1)

            var version: String? = null
            var isRequired = false
            val components = mutableListOf<String>()

            var i = 0
            while (i < args.size) {
                when (args[i].text) {
                    "REQUIRED" -> isRequired = true
                    "COMPONENTS" -> {
                        i++
                        while (i < args.size && args[i].text !in listOf("OPTIONAL_COMPONENTS", "CONFIG", "MODULE", "REQUIRED", "QUIET")) {
                            components.add(args[i].text)
                            i++
                        }
                        continue
                    }
                    else -> {
                        if (version == null && args[i].text.matches(Regex("[0-9].*"))) {
                            version = args[i].text
                        }
                    }
                }
                i++
            }

            PackageRequirement(name, version, isRequired, components)
        }
    }

    // ========== 便捷查询方法 ==========

    /**
     * 按名称获取命令
     */
    fun commandsByName(name: String): List<CMakeCommand> = commands.filter { it.name.equals(name, ignoreCase = true) }

    /**
     * 获取目标的链接库
     */
    fun getTargetLibraries(targetName: String): List<String> = rawDoc.commandsByName("target_link_libraries")
        .filter { it.arguments.getOrNull(0)?.text == targetName }
        .flatMap { cmd ->
            cmd.arguments.drop(1)
                .filter { it.text !in listOf("PUBLIC", "PRIVATE", "INTERFACE") }
                .map { it.text }
        }

    /**
     * 获取目标的包含目录
     */
    fun getTargetIncludeDirectories(targetName: String): List<String> = rawDoc.commandsByName("target_include_directories")
        .filter { it.arguments.getOrNull(0)?.text == targetName }
        .flatMap { cmd ->
            cmd.arguments.drop(1)
                .filter { it.text !in listOf("PUBLIC", "PRIVATE", "INTERFACE", "SYSTEM", "BEFORE", "AFTER") }
                .map { it.text }
        }

    /**
     * 获取目标的编译定义
     */
    fun getTargetCompileDefinitions(targetName: String): List<String> = rawDoc.commandsByName("target_compile_definitions")
        .filter { it.arguments.getOrNull(0)?.text == targetName }
        .flatMap { cmd ->
            cmd.arguments.drop(1)
                .filter { it.text !in listOf("PUBLIC", "PRIVATE", "INTERFACE") }
                .map { it.text }
        }

    override fun toString(): String = buildString {
        appendLine("CMakeDoc {")
        projectName?.let { appendLine("  project: $it") }
        projectVersion?.let { appendLine("  version: $it") }
        minimumVersion?.let { appendLine("  cmake_minimum_required: $it") }
        if (languages.isNotEmpty()) appendLine("  languages: $languages")
        appendLine("  targets: ${targets.size}")
        appendLine("  variables: ${variables.size}")
        appendLine("  subdirectories: ${subdirectories.size}")
        appendLine("  packages: ${packages.size}")
        appendLine("}")
    }
}
