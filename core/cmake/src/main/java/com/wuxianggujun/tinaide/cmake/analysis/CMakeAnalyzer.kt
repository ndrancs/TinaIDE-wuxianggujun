/*
 * CMake Semantic Analyzer for TinaIDE
 * Copyright (C) 2024 wuxianggujun
 *
 * 语义分析器 - 提供变量追踪、作用域管理、类型推断等功能
 */

package com.wuxianggujun.tinaide.cmake.analysis

import com.wuxianggujun.tinaide.cmake.CMakeDoc
import com.wuxianggujun.tinaide.cmake.command.*
import com.wuxianggujun.tinaide.cmake.i18n.CMakeI18n
import com.wuxianggujun.tinaide.cmake.parser.CommandInvocation
import com.wuxianggujun.tinaide.core.i18n.Strings

/**
 * 变量信息
 */
data class VariableInfo(
    val name: String,
    val values: List<String>,
    val scope: VariableScope,
    val definedAt: Int, // 定义位置（命令索引）
    val isCache: Boolean = false,
    val cacheType: String? = null,
    val docstring: String? = null
)

/**
 * 变量作用域
 */
enum class VariableScope {
    GLOBAL, // 全局作用域
    DIRECTORY, // 目录作用域
    FUNCTION, // 函数作用域
    CACHE // 缓存变量
}

/**
 * 目标信息（增强版）
 */
data class TargetAnalysis(
    val name: String,
    val type: CMakeDoc.TargetType,
    val sources: MutableList<String> = mutableListOf(),
    val linkLibraries: MutableList<LinkEntry> = mutableListOf(),
    val includeDirectories: MutableList<DirectoryEntry> = mutableListOf(),
    val compileDefinitions: MutableList<String> = mutableListOf(),
    val compileOptions: MutableList<String> = mutableListOf(),
    val compileFeatures: MutableList<String> = mutableListOf(),
    val properties: MutableMap<String, String> = mutableMapOf(),
    val dependencies: MutableList<String> = mutableListOf()
) {
    data class LinkEntry(val library: String, val visibility: Visibility)
    data class DirectoryEntry(val path: String, val visibility: Visibility, val isSystem: Boolean = false)

    enum class Visibility { PUBLIC, PRIVATE, INTERFACE }
}

/**
 * 诊断信息
 */
data class Diagnostic(
    val level: Level,
    val message: String,
    val commandIndex: Int,
    val suggestion: String? = null
) {
    enum class Level { ERROR, WARNING, INFO, HINT }
}

/**
 * CMake 语义分析器
 */
class CMakeAnalyzer(private val doc: CMakeDoc) {

    // 变量表
    private val variables = mutableMapOf<String, VariableInfo>()

    // 目标表
    private val targets = mutableMapOf<String, TargetAnalysis>()

    // 函数/宏定义表
    private val functions = mutableMapOf<String, FunctionDefinition>()
    private val macros = mutableMapOf<String, MacroDefinition>()

    // 诊断信息
    private val diagnostics = mutableListOf<Diagnostic>()

    // 当前作用域栈
    private val scopeStack = mutableListOf<ScopeFrame>()

    data class FunctionDefinition(
        val name: String,
        val arguments: List<String>,
        val definedAt: Int
    )

    data class MacroDefinition(
        val name: String,
        val arguments: List<String>,
        val definedAt: Int
    )

    data class ScopeFrame(
        val type: ScopeType,
        val name: String? = null,
        val localVariables: MutableSet<String> = mutableSetOf()
    )

    enum class ScopeType {
        GLOBAL,
        FUNCTION,
        MACRO,
        BLOCK,
        FOREACH,
        WHILE
    }

    init {
        // 初始化全局作用域
        scopeStack.add(ScopeFrame(ScopeType.GLOBAL))
    }

    /**
     * 执行完整分析
     */
    fun analyze(): AnalysisResult {
        doc.rawCommands.forEachIndexed { index, cmd ->
            analyzeCommand(cmd, index)
        }

        return AnalysisResult(
            variables = variables.toMap(),
            targets = targets.toMap(),
            functions = functions.toMap(),
            macros = macros.toMap(),
            diagnostics = diagnostics.toList()
        )
    }

    private fun analyzeCommand(cmd: CommandInvocation, index: Int) {
        when (cmd.normalizedIdentifier()) {
            // 变量操作
            "set" -> analyzeSet(cmd, index)
            "unset" -> analyzeUnset(cmd, index)
            "option" -> analyzeOption(cmd, index)

            // 目标定义
            "add_executable" -> analyzeAddExecutable(cmd, index)
            "add_library" -> analyzeAddLibrary(cmd, index)
            "add_custom_target" -> analyzeAddCustomTarget(cmd, index)

            // 目标属性
            "target_link_libraries" -> analyzeTargetLinkLibraries(cmd, index)
            "target_include_directories" -> analyzeTargetIncludeDirectories(cmd, index)
            "target_compile_definitions" -> analyzeTargetCompileDefinitions(cmd, index)
            "target_compile_options" -> analyzeTargetCompileOptions(cmd, index)
            "target_compile_features" -> analyzeTargetCompileFeatures(cmd, index)
            "target_sources" -> analyzeTargetSources(cmd, index)
            "set_target_properties" -> analyzeSetTargetProperties(cmd, index)
            "add_dependencies" -> analyzeAddDependencies(cmd, index)

            // 作用域控制
            "function" -> enterFunction(cmd, index)
            "endfunction" -> exitFunction()
            "macro" -> enterMacro(cmd, index)
            "endmacro" -> exitMacro()
            "block" -> enterBlock(cmd, index)
            "endblock" -> exitBlock()
            "foreach" -> enterForEach(cmd, index)
            "endforeach" -> exitForEach()
            "while" -> enterWhile(cmd, index)
            "endwhile" -> exitWhile()
        }
    }

    // ========== 变量分析 ==========

    private fun analyzeSet(cmd: CommandInvocation, index: Int) {
        val args = cmd.arguments
        if (args.isEmpty()) {
            diagnostics.add(
                Diagnostic(
                    Diagnostic.Level.ERROR,
                    CMakeI18n.strOrFallback(
                        Strings.cmake_analyzer_error_set_missing_var_name,
                        "set() command is missing a variable name"
                    ),
                    index
                )
            )
            return
        }

        val varName = args[0].text
        val remaining = args.drop(1)

        // 检查是否是环境变量
        if (varName.startsWith("ENV{") && varName.endsWith("}")) {
            // 环境变量，不追踪
            return
        }

        // 检查 CACHE
        val cacheIndex = remaining.indexOfFirst { it.text == "CACHE" }
        val isCache = cacheIndex >= 0
        val isParentScope = remaining.any { it.text == "PARENT_SCOPE" }

        val values = if (isCache) {
            remaining.take(cacheIndex).map { it.text }
        } else {
            remaining.filter { it.text != "PARENT_SCOPE" }.map { it.text }
        }

        val cacheType = if (isCache) {
            remaining.getOrNull(cacheIndex + 1)?.text
        } else {
            null
        }

        val docstring = if (isCache && cacheIndex + 2 < remaining.size) {
            remaining.getOrNull(cacheIndex + 2)?.text
        } else {
            null
        }

        val scope = when {
            isCache -> VariableScope.CACHE
            isParentScope -> determineParentScope()
            scopeStack.last().type == ScopeType.FUNCTION -> VariableScope.FUNCTION
            else -> VariableScope.DIRECTORY
        }

        val varInfo = VariableInfo(
            name = varName,
            values = values,
            scope = scope,
            definedAt = index,
            isCache = isCache,
            cacheType = cacheType,
            docstring = docstring
        )

        variables[varName] = varInfo

        // 记录到当前作用域
        if (!isParentScope && !isCache) {
            scopeStack.last().localVariables.add(varName)
        }
    }

    private fun determineParentScope(): VariableScope {
        // 查找父作用域
        return if (scopeStack.size > 1) {
            when (scopeStack[scopeStack.size - 2].type) {
                ScopeType.FUNCTION -> VariableScope.FUNCTION
                else -> VariableScope.DIRECTORY
            }
        } else {
            VariableScope.GLOBAL
        }
    }

    private fun analyzeUnset(cmd: CommandInvocation, index: Int) {
        val varName = cmd.arguments.getOrNull(0)?.text ?: return

        if (varName.startsWith("ENV{")) return

        val isCache = cmd.arguments.any { it.text == "CACHE" }

        if (isCache) {
            // 从缓存中移除
            variables.remove(varName)
        } else {
            // 标记为未定义
            variables[varName] = VariableInfo(
                name = varName,
                values = emptyList(),
                scope = VariableScope.DIRECTORY,
                definedAt = index
            )
        }
    }

    private fun analyzeOption(cmd: CommandInvocation, index: Int) {
        val varName = cmd.arguments.getOrNull(0)?.text ?: return
        val helpString = cmd.arguments.getOrNull(1)?.text ?: ""
        val initialValue = cmd.arguments.getOrNull(2)?.text ?: "OFF"

        variables[varName] = VariableInfo(
            name = varName,
            values = listOf(initialValue),
            scope = VariableScope.CACHE,
            definedAt = index,
            isCache = true,
            cacheType = "BOOL",
            docstring = helpString
        )
    }

    // ========== 目标分析 ==========

    private fun analyzeAddExecutable(cmd: CommandInvocation, index: Int) {
        val targetName = cmd.arguments.getOrNull(0)?.text ?: return

        val isImported = cmd.arguments.any { it.text == "IMPORTED" }
        val isAlias = cmd.arguments.any { it.text == "ALIAS" }

        if (isImported || isAlias) return

        val sources = cmd.arguments.drop(1)
            .filter { it.text !in listOf("WIN32", "MACOSX_BUNDLE", "EXCLUDE_FROM_ALL") }
            .map { it.text }

        targets[targetName] = TargetAnalysis(
            name = targetName,
            type = CMakeDoc.TargetType.EXECUTABLE,
            sources = sources.toMutableList()
        )
    }

    private fun analyzeAddLibrary(cmd: CommandInvocation, index: Int) {
        val targetName = cmd.arguments.getOrNull(0)?.text ?: return

        val isImported = cmd.arguments.any { it.text == "IMPORTED" }
        val isAlias = cmd.arguments.any { it.text == "ALIAS" }

        if (isImported || isAlias) return

        val type = when {
            cmd.arguments.any { it.text == "STATIC" } -> CMakeDoc.TargetType.STATIC_LIBRARY
            cmd.arguments.any { it.text == "SHARED" } -> CMakeDoc.TargetType.SHARED_LIBRARY
            cmd.arguments.any { it.text == "MODULE" } -> CMakeDoc.TargetType.MODULE_LIBRARY
            cmd.arguments.any { it.text == "OBJECT" } -> CMakeDoc.TargetType.OBJECT_LIBRARY
            cmd.arguments.any { it.text == "INTERFACE" } -> CMakeDoc.TargetType.INTERFACE_LIBRARY
            else -> CMakeDoc.TargetType.STATIC_LIBRARY
        }

        val sources = cmd.arguments.drop(1)
            .filter { it.text !in listOf("STATIC", "SHARED", "MODULE", "OBJECT", "INTERFACE", "EXCLUDE_FROM_ALL") }
            .map { it.text }

        targets[targetName] = TargetAnalysis(
            name = targetName,
            type = type,
            sources = sources.toMutableList()
        )
    }

    private fun analyzeAddCustomTarget(cmd: CommandInvocation, index: Int) {
        val targetName = cmd.arguments.getOrNull(0)?.text ?: return

        targets[targetName] = TargetAnalysis(
            name = targetName,
            type = CMakeDoc.TargetType.CUSTOM_TARGET
        )
    }

    private fun analyzeTargetLinkLibraries(cmd: CommandInvocation, index: Int) {
        val targetName = cmd.arguments.getOrNull(0)?.text ?: return
        val target = targets[targetName]

        if (target == null) {
            diagnostics.add(
                Diagnostic(
                    Diagnostic.Level.WARNING,
                    CMakeI18n.strOrFallback(
                        Strings.cmake_analyzer_error_target_not_defined,
                        "target_link_libraries: target '$targetName' is not defined",
                        targetName
                    ),
                    index,
                    CMakeI18n.strOrFallback(
                        Strings.cmake_analyzer_error_define_target_before_calling,
                        "Make sure the target is defined before calling this command"
                    )
                )
            )
            return
        }

        var visibility = TargetAnalysis.Visibility.PUBLIC
        for (i in 1 until cmd.arguments.size) {
            when (cmd.arguments[i].text) {
                "PUBLIC" -> visibility = TargetAnalysis.Visibility.PUBLIC
                "PRIVATE" -> visibility = TargetAnalysis.Visibility.PRIVATE
                "INTERFACE" -> visibility = TargetAnalysis.Visibility.INTERFACE
                else -> target.linkLibraries.add(
                    TargetAnalysis.LinkEntry(cmd.arguments[i].text, visibility)
                )
            }
        }
    }

    private fun analyzeTargetIncludeDirectories(cmd: CommandInvocation, index: Int) {
        val targetName = cmd.arguments.getOrNull(0)?.text ?: return
        val target = targets[targetName] ?: return

        var visibility = TargetAnalysis.Visibility.PUBLIC
        var isSystem = false

        for (i in 1 until cmd.arguments.size) {
            when (cmd.arguments[i].text) {
                "SYSTEM" -> isSystem = true
                "BEFORE", "AFTER" -> { /* 忽略 */ }
                "PUBLIC" -> visibility = TargetAnalysis.Visibility.PUBLIC
                "PRIVATE" -> visibility = TargetAnalysis.Visibility.PRIVATE
                "INTERFACE" -> visibility = TargetAnalysis.Visibility.INTERFACE
                else -> target.includeDirectories.add(
                    TargetAnalysis.DirectoryEntry(cmd.arguments[i].text, visibility, isSystem)
                )
            }
        }
    }

    private fun analyzeTargetCompileDefinitions(cmd: CommandInvocation, index: Int) {
        val targetName = cmd.arguments.getOrNull(0)?.text ?: return
        val target = targets[targetName] ?: return

        for (i in 1 until cmd.arguments.size) {
            when (cmd.arguments[i].text) {
                "PUBLIC", "PRIVATE", "INTERFACE" -> { /* 忽略可见性 */ }
                else -> target.compileDefinitions.add(cmd.arguments[i].text)
            }
        }
    }

    private fun analyzeTargetCompileOptions(cmd: CommandInvocation, index: Int) {
        val targetName = cmd.arguments.getOrNull(0)?.text ?: return
        val target = targets[targetName] ?: return

        for (i in 1 until cmd.arguments.size) {
            when (cmd.arguments[i].text) {
                "PUBLIC", "PRIVATE", "INTERFACE", "BEFORE" -> { /* 忽略 */ }
                else -> target.compileOptions.add(cmd.arguments[i].text)
            }
        }
    }

    private fun analyzeTargetCompileFeatures(cmd: CommandInvocation, index: Int) {
        val targetName = cmd.arguments.getOrNull(0)?.text ?: return
        val target = targets[targetName] ?: return

        for (i in 1 until cmd.arguments.size) {
            when (cmd.arguments[i].text) {
                "PUBLIC", "PRIVATE", "INTERFACE" -> { /* 忽略 */ }
                else -> target.compileFeatures.add(cmd.arguments[i].text)
            }
        }
    }

    private fun analyzeTargetSources(cmd: CommandInvocation, index: Int) {
        val targetName = cmd.arguments.getOrNull(0)?.text ?: return
        val target = targets[targetName] ?: return

        for (i in 1 until cmd.arguments.size) {
            when (cmd.arguments[i].text) {
                "PUBLIC", "PRIVATE", "INTERFACE" -> { /* 忽略 */ }
                else -> target.sources.add(cmd.arguments[i].text)
            }
        }
    }

    private fun analyzeSetTargetProperties(cmd: CommandInvocation, index: Int) {
        val propertiesIndex = cmd.arguments.indexOfFirst { it.text == "PROPERTIES" }
        if (propertiesIndex < 0) return

        val targetNames = cmd.arguments.take(propertiesIndex).map { it.text }
        val properties = cmd.arguments.drop(propertiesIndex + 1)

        var i = 0
        while (i < properties.size - 1) {
            val propName = properties[i].text
            val propValue = properties[i + 1].text

            targetNames.forEach { targetName ->
                targets[targetName]?.properties?.set(propName, propValue)
            }

            i += 2
        }
    }

    private fun analyzeAddDependencies(cmd: CommandInvocation, index: Int) {
        val targetName = cmd.arguments.getOrNull(0)?.text ?: return
        val target = targets[targetName] ?: return

        cmd.arguments.drop(1).forEach { dep ->
            target.dependencies.add(dep.text)
        }
    }

    // ========== 作用域管理 ==========

    private fun enterFunction(cmd: CommandInvocation, index: Int) {
        val funcName = cmd.arguments.getOrNull(0)?.text ?: return
        val funcArgs = cmd.arguments.drop(1).map { it.text }

        functions[funcName] = FunctionDefinition(funcName, funcArgs, index)
        scopeStack.add(ScopeFrame(ScopeType.FUNCTION, funcName))
    }

    private fun exitFunction() {
        if (scopeStack.last().type == ScopeType.FUNCTION) {
            val frame = scopeStack.removeAt(scopeStack.lastIndex)
            // 清理函数局部变量
            frame.localVariables.forEach { varName ->
                variables.remove(varName)
            }
        }
    }

    private fun enterMacro(cmd: CommandInvocation, index: Int) {
        val macroName = cmd.arguments.getOrNull(0)?.text ?: return
        val macroArgs = cmd.arguments.drop(1).map { it.text }

        macros[macroName] = MacroDefinition(macroName, macroArgs, index)
        scopeStack.add(ScopeFrame(ScopeType.MACRO, macroName))
    }

    private fun exitMacro() {
        if (scopeStack.last().type == ScopeType.MACRO) {
            scopeStack.removeAt(scopeStack.lastIndex)
            // 宏不创建新作用域，变量保留
        }
    }

    private fun enterBlock(cmd: CommandInvocation, index: Int) {
        scopeStack.add(ScopeFrame(ScopeType.BLOCK))
    }

    private fun exitBlock() {
        if (scopeStack.last().type == ScopeType.BLOCK) {
            val frame = scopeStack.removeAt(scopeStack.lastIndex)
            // 清理 block 局部变量（除非 PROPAGATE）
            frame.localVariables.forEach { varName ->
                variables.remove(varName)
            }
        }
    }

    private fun enterForEach(cmd: CommandInvocation, index: Int) {
        val loopVar = cmd.arguments.getOrNull(0)?.text ?: return
        scopeStack.add(ScopeFrame(ScopeType.FOREACH, loopVar))
    }

    private fun exitForEach() {
        if (scopeStack.last().type == ScopeType.FOREACH) {
            scopeStack.removeAt(scopeStack.lastIndex)
        }
    }

    private fun enterWhile(cmd: CommandInvocation, index: Int) {
        scopeStack.add(ScopeFrame(ScopeType.WHILE))
    }

    private fun exitWhile() {
        if (scopeStack.last().type == ScopeType.WHILE) {
            scopeStack.removeAt(scopeStack.lastIndex)
        }
    }

    // ========== 查询 API ==========

    /**
     * 获取变量值
     */
    fun getVariable(name: String): VariableInfo? = variables[name]

    /**
     * 获取目标信息
     */
    fun getTarget(name: String): TargetAnalysis? = targets[name]

    /**
     * 获取所有变量
     */
    fun getAllVariables(): Map<String, VariableInfo> = variables.toMap()

    /**
     * 获取所有目标
     */
    fun getAllTargets(): Map<String, TargetAnalysis> = targets.toMap()

    /**
     * 获取诊断信息
     */
    fun getDiagnostics(): List<Diagnostic> = diagnostics.toList()

    // ========== 变量展开 ==========

    /**
     * 展开字符串中的变量引用
     *
     * 支持的格式:
     * - ${VAR}: 普通变量引用
     * - $ENV{VAR}: 环境变量引用
     * - $CACHE{VAR}: 缓存变量引用
     *
     * @param input 包含变量引用的字符串
     * @param recursive 是否递归展开（默认 true）
     * @param maxDepth 最大递归深度（防止无限循环，默认 10）
     * @return 展开后的字符串
     */
    fun expandVariables(input: String, recursive: Boolean = true, maxDepth: Int = 10): String = expandVariablesInternal(input, recursive, maxDepth, 0)

    private fun expandVariablesInternal(input: String, recursive: Boolean, maxDepth: Int, currentDepth: Int): String {
        if (currentDepth >= maxDepth) {
            return input // 达到最大深度，停止展开
        }

        var result = input
        var changed = false

        // 处理 ${VAR} 格式
        result = expandNormalVariables(result) { changed = true }

        // 处理 $ENV{VAR} 格式
        result = expandEnvVariables(result) { changed = true }

        // 处理 $CACHE{VAR} 格式
        result = expandCacheVariables(result) { changed = true }

        // 递归展开
        if (recursive && changed) {
            return expandVariablesInternal(result, true, maxDepth, currentDepth + 1)
        }

        return result
    }

    /**
     * 展开普通变量 ${VAR}
     */
    private fun expandNormalVariables(input: String, onChanged: () -> Unit): String {
        val pattern = Regex("""\$\{([^}]+)\}""")
        return pattern.replace(input) { match ->
            val varName = match.groupValues[1]
            val value = resolveVariable(varName)
            if (value != null) {
                onChanged()
                value
            } else {
                match.value // 保持原样
            }
        }
    }

    /**
     * 展开环境变量 $ENV{VAR}
     */
    private fun expandEnvVariables(input: String, onChanged: () -> Unit): String {
        val pattern = Regex("""\${'$'}ENV\{([^}]+)\}""")
        return pattern.replace(input) { match ->
            val varName = match.groupValues[1]
            val value = System.getenv(varName)
            if (value != null) {
                onChanged()
                value
            } else {
                match.value
            }
        }
    }

    /**
     * 展开缓存变量 $CACHE{VAR}
     */
    private fun expandCacheVariables(input: String, onChanged: () -> Unit): String {
        val pattern = Regex("""\${'$'}CACHE\{([^}]+)\}""")
        return pattern.replace(input) { match ->
            val varName = match.groupValues[1]
            val varInfo = variables[varName]
            if (varInfo != null && varInfo.isCache && varInfo.values.isNotEmpty()) {
                onChanged()
                varInfo.values.joinToString(";")
            } else {
                match.value
            }
        }
    }

    /**
     * 解析变量值
     *
     * @param name 变量名
     * @return 变量值（如果存在）
     */
    private fun resolveVariable(name: String): String? {
        val varInfo = variables[name]
        return if (varInfo != null && varInfo.values.isNotEmpty()) {
            varInfo.values.joinToString(";")
        } else {
            null
        }
    }

    /**
     * 检查变量是否已定义
     */
    fun isVariableDefined(name: String): Boolean = variables[name]?.values?.isNotEmpty() == true

    /**
     * 获取变量的字符串值（展开后）
     */
    fun getVariableValue(name: String): String? = variables[name]?.values?.let { values ->
        if (values.isNotEmpty()) {
            expandVariables(values.joinToString(";"))
        } else {
            null
        }
    }

    /**
     * 获取变量的列表值（展开后）
     */
    fun getVariableListValue(name: String): List<String>? = variables[name]?.values?.let { values ->
        if (values.isNotEmpty()) {
            values.flatMap { value ->
                expandVariables(value).split(";").filter { it.isNotEmpty() }
            }
        } else {
            null
        }
    }

    // ========== 生成器表达式 ==========

    /**
     * 解析生成器表达式
     *
     * 支持的表达式:
     * - $<TARGET_FILE:target>: 目标文件路径
     * - $<TARGET_FILE_NAME:target>: 目标文件名
     * - $<TARGET_FILE_DIR:target>: 目标文件目录
     * - $<TARGET_PROPERTY:target,property>: 目标属性
     * - $<BOOL:value>: 布尔值
     * - $<IF:condition,true_value,false_value>: 条件表达式
     * - $<STREQUAL:a,b>: 字符串比较
     * - $<AND:expr1,expr2,...>: 逻辑与
     * - $<OR:expr1,expr2,...>: 逻辑或
     * - $<NOT:expr>: 逻辑非
     *
     * 注意：完整的生成器表达式需要在构建时求值，这里只提供静态分析支持
     *
     * @param input 包含生成器表达式的字符串
     * @return 解析后的表达式信息
     */
    fun parseGeneratorExpression(input: String): GeneratorExpressionInfo {
        val expressions = mutableListOf<GeneratorExpression>()
        val pattern = Regex("""\$<([^>]+)>""")

        pattern.findAll(input).forEach { match ->
            val content = match.groupValues[1]
            val expr = parseGenexContent(content, match.range.first)
            expressions.add(expr)
        }

        return GeneratorExpressionInfo(
            original = input,
            expressions = expressions,
            canEvaluateStatically = expressions.all { it.canEvaluateStatically }
        )
    }

    private fun parseGenexContent(content: String, offset: Int): GeneratorExpression {
        // 解析格式: NAME:args 或 NAME
        val colonIndex = content.indexOf(':')
        val name = if (colonIndex >= 0) content.substring(0, colonIndex) else content
        val args = if (colonIndex >= 0) content.substring(colonIndex + 1) else ""

        return when (name.uppercase()) {
            // 目标相关
            "TARGET_FILE" -> GeneratorExpression.TargetFile(args, offset)
            "TARGET_FILE_NAME" -> GeneratorExpression.TargetFileName(args, offset)
            "TARGET_FILE_DIR" -> GeneratorExpression.TargetFileDir(args, offset)
            "TARGET_PROPERTY" -> {
                val parts = args.split(",", limit = 2)
                GeneratorExpression.TargetProperty(
                    target = parts.getOrNull(0) ?: "",
                    property = parts.getOrNull(1) ?: "",
                    offset = offset
                )
            }

            // 布尔与条件
            "BOOL" -> GeneratorExpression.Bool(args, offset)
            "IF" -> {
                val parts = args.split(",", limit = 3)
                GeneratorExpression.If(
                    condition = parts.getOrNull(0) ?: "",
                    trueValue = parts.getOrNull(1) ?: "",
                    falseValue = parts.getOrNull(2) ?: "",
                    offset = offset
                )
            }

            // 比较
            "STREQUAL" -> {
                val parts = args.split(",", limit = 2)
                GeneratorExpression.StrEqual(
                    left = parts.getOrNull(0) ?: "",
                    right = parts.getOrNull(1) ?: "",
                    offset = offset
                )
            }

            // 逻辑运算
            "AND" -> GeneratorExpression.And(args.split(","), offset)
            "OR" -> GeneratorExpression.Or(args.split(","), offset)
            "NOT" -> GeneratorExpression.Not(args, offset)

            // 配置相关
            "CONFIG" -> GeneratorExpression.Config(args, offset)
            "PLATFORM_ID" -> GeneratorExpression.PlatformId(args, offset)

            // 未知表达式
            else -> GeneratorExpression.Unknown(name, args, offset)
        }
    }

    /**
     * 尝试静态求值生成器表达式（部分支持）
     *
     * @param input 包含生成器表达式的字符串
     * @param config 构建配置（如 Debug, Release）
     * @return 求值结果（如果可以静态求值）
     */
    fun tryEvaluateGeneratorExpression(input: String, config: String? = null): String {
        var result = input

        // 处理 $<BOOL:value>
        result = Regex("""\$<BOOL:([^>]*)>""").replace(result) { match ->
            val value = match.groupValues[1].trim()
            if (isTrueValue(value)) "1" else "0"
        }

        // 处理 $<AND:...>
        result = Regex("""\$<AND:([^>]*)>""").replace(result) { match ->
            val values = match.groupValues[1].split(",").map { it.trim() }
            if (values.all { isTrueValue(it) }) "1" else "0"
        }

        // 处理 $<OR:...>
        result = Regex("""\$<OR:([^>]*)>""").replace(result) { match ->
            val values = match.groupValues[1].split(",").map { it.trim() }
            if (values.any { isTrueValue(it) }) "1" else "0"
        }

        // 处理 $<NOT:...>
        result = Regex("""\$<NOT:([^>]*)>""").replace(result) { match ->
            val value = match.groupValues[1].trim()
            if (isTrueValue(value)) "0" else "1"
        }

        // 处理 $<STREQUAL:a,b>
        result = Regex("""\$<STREQUAL:([^,>]*),([^>]*)>""").replace(result) { match ->
            if (match.groupValues[1].trim() == match.groupValues[2].trim()) "1" else "0"
        }

        // 处理 $<IF:cond,true,false>
        result = Regex("""\$<IF:([^,>]*),([^,>]*),([^>]*)>""").replace(result) { match ->
            val condition = match.groupValues[1].trim()
            val trueVal = match.groupValues[2]
            val falseVal = match.groupValues[3]
            if (isTrueValue(condition)) trueVal else falseVal
        }

        // 处理 $<CONFIG:cfg> (需要 config 参数)
        if (config != null) {
            result = Regex("""\$<CONFIG:([^>]*)>""").replace(result) { match ->
                if (match.groupValues[1].trim().equals(config, ignoreCase = true)) "1" else "0"
            }
        }

        return result
    }

    private fun isTrueValue(value: String): Boolean = when (value.uppercase()) {
        "1", "ON", "YES", "TRUE", "Y" -> true
        "0", "OFF", "NO", "FALSE", "N", "", "NOTFOUND" -> false
        else -> value.isNotEmpty() && !value.endsWith("-NOTFOUND")
    }

    // ========== 条件求值 ==========

    /**
     * 求值 if() 条件表达式
     *
     * 支持的条件:
     * - DEFINED <var>: 变量是否定义
     * - NOT <condition>: 取反
     * - <var>: 变量值是否为真
     * - <string1> STREQUAL <string2>: 字符串相等
     * - <number1> EQUAL <number2>: 数值相等
     * - <number1> LESS <number2>: 数值小于
     * - <number1> GREATER <number2>: 数值大于
     * - EXISTS <path>: 路径是否存在（静态分析时返回 null）
     * - TARGET <name>: 目标是否存在
     *
     * @param condition 条件表达式的 token 列表
     * @return 求值结果：true/false/null（无法静态求值）
     */
    fun evaluateCondition(condition: List<String>): Boolean? {
        if (condition.isEmpty()) return false

        return when {
            // DEFINED <var>
            condition.size >= 2 && condition[0].uppercase() == "DEFINED" -> {
                val varName = condition[1]
                isVariableDefined(varName)
            }

            // NOT <condition>
            condition[0].uppercase() == "NOT" -> {
                val subResult = evaluateCondition(condition.drop(1))
                subResult?.let { !it }
            }

            // <cond1> AND <cond2>
            condition.contains("AND") -> {
                val andIndex = condition.indexOf("AND")
                val left = evaluateCondition(condition.take(andIndex))
                val right = evaluateCondition(condition.drop(andIndex + 1))
                if (left != null && right != null) left && right else null
            }

            // <cond1> OR <cond2>
            condition.contains("OR") -> {
                val orIndex = condition.indexOf("OR")
                val left = evaluateCondition(condition.take(orIndex))
                val right = evaluateCondition(condition.drop(orIndex + 1))
                if (left != null && right != null) left || right else null
            }

            // <string1> STREQUAL <string2>
            condition.size >= 3 && condition[1].uppercase() == "STREQUAL" -> {
                val left = expandVariables(condition[0])
                val right = expandVariables(condition[2])
                left == right
            }

            // <num1> EQUAL <num2>
            condition.size >= 3 && condition[1].uppercase() == "EQUAL" -> {
                val left = expandVariables(condition[0]).toDoubleOrNull()
                val right = expandVariables(condition[2]).toDoubleOrNull()
                if (left != null && right != null) left == right else null
            }

            // <num1> LESS <num2>
            condition.size >= 3 && condition[1].uppercase() == "LESS" -> {
                val left = expandVariables(condition[0]).toDoubleOrNull()
                val right = expandVariables(condition[2]).toDoubleOrNull()
                if (left != null && right != null) left < right else null
            }

            // <num1> GREATER <num2>
            condition.size >= 3 && condition[1].uppercase() == "GREATER" -> {
                val left = expandVariables(condition[0]).toDoubleOrNull()
                val right = expandVariables(condition[2]).toDoubleOrNull()
                if (left != null && right != null) left > right else null
            }

            // TARGET <name>
            condition.size >= 2 && condition[0].uppercase() == "TARGET" -> {
                targets.containsKey(condition[1])
            }

            // EXISTS <path> - 无法静态求值
            condition.size >= 2 && condition[0].uppercase() == "EXISTS" -> null

            // 单个变量或值
            condition.size == 1 -> {
                val value = expandVariables(condition[0])
                isTrueValue(value)
            }

            else -> null
        }
    }
}

/**
 * 分析结果
 */
data class AnalysisResult(
    val variables: Map<String, VariableInfo>,
    val targets: Map<String, TargetAnalysis>,
    val functions: Map<String, CMakeAnalyzer.FunctionDefinition>,
    val macros: Map<String, CMakeAnalyzer.MacroDefinition>,
    val diagnostics: List<Diagnostic>
) {
    /**
     * 是否有错误
     */
    val hasErrors: Boolean
        get() = diagnostics.any { it.level == Diagnostic.Level.ERROR }

    /**
     * 是否有警告
     */
    val hasWarnings: Boolean
        get() = diagnostics.any { it.level == Diagnostic.Level.WARNING }

    override fun toString(): String = buildString {
        appendLine("AnalysisResult {")
        appendLine("  variables: ${variables.size}")
        appendLine("  targets: ${targets.size}")
        appendLine("  functions: ${functions.size}")
        appendLine("  macros: ${macros.size}")
        appendLine("  diagnostics: ${diagnostics.size} (errors: ${diagnostics.count { it.level == Diagnostic.Level.ERROR }})")
        appendLine("}")
    }
}

/**
 * 生成器表达式信息
 */
data class GeneratorExpressionInfo(
    val original: String,
    val expressions: List<GeneratorExpression>,
    val canEvaluateStatically: Boolean
)

/**
 * 生成器表达式
 */
sealed class GeneratorExpression {
    abstract val offset: Int
    abstract val canEvaluateStatically: Boolean

    // 目标相关表达式
    data class TargetFile(val target: String, override val offset: Int) : GeneratorExpression() {
        override val canEvaluateStatically = false
    }

    data class TargetFileName(val target: String, override val offset: Int) : GeneratorExpression() {
        override val canEvaluateStatically = false
    }

    data class TargetFileDir(val target: String, override val offset: Int) : GeneratorExpression() {
        override val canEvaluateStatically = false
    }

    data class TargetProperty(val target: String, val property: String, override val offset: Int) : GeneratorExpression() {
        override val canEvaluateStatically = false
    }

    // 布尔与条件
    data class Bool(val value: String, override val offset: Int) : GeneratorExpression() {
        override val canEvaluateStatically = true
    }

    data class If(
        val condition: String,
        val trueValue: String,
        val falseValue: String,
        override val offset: Int
    ) : GeneratorExpression() {
        override val canEvaluateStatically = true
    }

    // 比较
    data class StrEqual(val left: String, val right: String, override val offset: Int) : GeneratorExpression() {
        override val canEvaluateStatically = true
    }

    // 逻辑运算
    data class And(val values: List<String>, override val offset: Int) : GeneratorExpression() {
        override val canEvaluateStatically = true
    }

    data class Or(val values: List<String>, override val offset: Int) : GeneratorExpression() {
        override val canEvaluateStatically = true
    }

    data class Not(val value: String, override val offset: Int) : GeneratorExpression() {
        override val canEvaluateStatically = true
    }

    // 配置相关
    data class Config(val config: String, override val offset: Int) : GeneratorExpression() {
        override val canEvaluateStatically = false // 需要运行时配置
    }

    data class PlatformId(val platform: String, override val offset: Int) : GeneratorExpression() {
        override val canEvaluateStatically = false
    }

    // 未知表达式
    data class Unknown(val name: String, val args: String, override val offset: Int) : GeneratorExpression() {
        override val canEvaluateStatically = false
    }
}
