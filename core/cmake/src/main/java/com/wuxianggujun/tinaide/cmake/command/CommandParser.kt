/*
 * CMake Command Parser for TinaIDE
 * Copyright (C) 2024 wuxianggujun
 *
 * 命令解析器 - 将 CommandInvocation 转换为具体的 CMakeCommand 对象
 */

package com.wuxianggujun.tinaide.cmake.command

import com.wuxianggujun.tinaide.cmake.parser.CommandInvocation
import com.wuxianggujun.tinaide.cmake.parser.Token

/**
 * CMake 命令解析器
 * 将原始的命令调用转换为结构化的命令对象
 */
object CommandParser {

    /**
     * 解析命令调用
     */
    fun parse(invocation: CommandInvocation): CMakeCommand {
        val args = invocation.arguments
        val name = invocation.normalizedIdentifier()

        return when (name) {
            // 脚本命令
            "set" -> parseSet(args)
            "unset" -> parseUnset(args)
            "message" -> parseMessage(args)
            "if" -> IfCommand(args, args)
            "elseif" -> ElseIfCommand(args, args)
            "else" -> ElseCommand(args)
            "endif" -> EndIfCommand(args)
            "foreach" -> parseForEach(args)
            "endforeach" -> EndForEachCommand(args)
            "while" -> WhileCommand(args, args)
            "endwhile" -> EndWhileCommand(args)
            "function" -> parseFunction(args)
            "endfunction" -> parseEndFunction(args)
            "macro" -> parseMacro(args)
            "endmacro" -> parseEndMacro(args)
            "block" -> parseBlock(args)
            "endblock" -> EndBlockCommand(args)
            "break" -> BreakCommand(args)
            "continue" -> ContinueCommand(args)
            "return" -> parseReturn(args)
            "include" -> parseInclude(args)
            "cmake_minimum_required" -> parseCMakeMinimumRequired(args)
            "cmake_policy" -> parseCMakePolicy(args)
            "option" -> parseOption(args)
            "find_package" -> parseFindPackage(args)
            "find_library" -> parseFindLibrary(args)
            "find_path" -> parseFindPath(args)
            "find_file" -> parseFindFile(args)
            "find_program" -> parseFindProgram(args)
            "list" -> parseList(args)
            "string" -> parseString(args)
            "file" -> parseFile(args)
            "math" -> parseMath(args)
            "execute_process" -> parseExecuteProcess(args)
            "configure_file" -> parseConfigureFile(args)

            // 高级脚本命令
            "cmake_parse_arguments" -> parseCMakeParseArguments(args)
            "mark_as_advanced" -> parseMarkAsAdvanced(args)
            "get_filename_component" -> parseGetFilenameComponent(args)

            // 项目命令
            "project" -> parseProject(args)
            "add_executable" -> parseAddExecutable(args)
            "add_library" -> parseAddLibrary(args)
            "target_link_libraries" -> parseTargetLinkLibraries(args)
            "target_include_directories" -> parseTargetIncludeDirectories(args)
            "target_compile_definitions" -> parseTargetCompileDefinitions(args)
            "target_compile_options" -> parseTargetCompileOptions(args)
            "target_compile_features" -> parseTargetCompileFeatures(args)
            "target_sources" -> parseTargetSources(args)
            "target_link_directories" -> parseTargetLinkDirectories(args)
            "target_link_options" -> parseTargetLinkOptions(args)
            "add_subdirectory" -> parseAddSubdirectory(args)
            "add_dependencies" -> parseAddDependencies(args)
            "add_custom_command" -> parseAddCustomCommand(args)
            "add_custom_target" -> parseAddCustomTarget(args)
            "add_test" -> parseAddTest(args)
            "enable_testing" -> EnableTestingCommand(args)
            "try_compile" -> parseTryCompile(args)
            "try_run" -> parseTryRun(args)
            "install" -> parseInstall(args)
            "include_directories" -> parseIncludeDirectories(args)
            "link_directories" -> parseLinkDirectories(args)
            "link_libraries" -> parseLinkLibraries(args)
            "add_compile_definitions" -> parseAddCompileDefinitions(args)
            "add_compile_options" -> parseAddCompileOptions(args)
            "add_link_options" -> parseAddLinkOptions(args)
            "set_property" -> parseSetProperty(args)
            "get_property" -> parseGetProperty(args)
            "set_target_properties" -> parseSetTargetProperties(args)
            "get_target_property" -> parseGetTargetProperty(args)
            "export" -> parseExport(args)

            else -> UnknownCommand(name, args)
        }
    }

    // ========== 脚本命令解析 ==========

    private fun parseSet(args: List<Token>): SetCommand {
        if (args.isEmpty()) {
            return SetCommand("", emptyList(), rawArguments = args)
        }

        val variable = args[0].text
        val remaining = args.drop(1)

        // 检查是否是环境变量
        val isEnv = variable.startsWith("ENV{") && variable.endsWith("}")

        // 检查 CACHE
        val cacheIndex = remaining.indexOfFirst { it.text == "CACHE" }
        if (cacheIndex >= 0) {
            val values = remaining.take(cacheIndex).map { it.text }
            val afterCache = remaining.drop(cacheIndex + 1)
            val cacheType = afterCache.getOrNull(0)?.text?.let { type ->
                SetCommand.CacheType.entries.find { it.name == type }
            }
            val docstring = afterCache.getOrNull(1)?.text
            val isForce = afterCache.any { it.text == "FORCE" }

            return SetCommand(
                variable = variable,
                values = values,
                cacheType = cacheType,
                docstring = docstring,
                isForce = isForce,
                isEnv = isEnv,
                rawArguments = args
            )
        }

        // 检查 PARENT_SCOPE
        val isParentScope = remaining.any { it.text == "PARENT_SCOPE" }
        val values = remaining.filter { it.text != "PARENT_SCOPE" }.map { it.text }

        return SetCommand(
            variable = variable,
            values = values,
            isParentScope = isParentScope,
            isEnv = isEnv,
            rawArguments = args
        )
    }

    private fun parseUnset(args: List<Token>): UnsetCommand {
        val variable = args.getOrNull(0)?.text ?: ""
        val isCache = args.any { it.text == "CACHE" }
        val isParentScope = args.any { it.text == "PARENT_SCOPE" }

        return UnsetCommand(variable, isCache, isParentScope, args)
    }

    private fun parseMessage(args: List<Token>): MessageCommand {
        if (args.isEmpty()) {
            return MessageCommand(null, emptyList(), args)
        }

        val firstArg = args[0].text
        val mode = MessageCommand.MessageMode.entries.find { it.name == firstArg }

        return if (mode != null) {
            MessageCommand(mode, args.drop(1).map { it.text }, args)
        } else {
            MessageCommand(null, args.map { it.text }, args)
        }
    }

    private fun parseForEach(args: List<Token>): ForEachCommand {
        val loopVar = args.getOrNull(0)?.text ?: ""
        val items = args.drop(1)

        val mode = when {
            items.any { it.text == "RANGE" } -> ForEachCommand.ForEachMode.RANGE
            items.any { it.text == "IN" && items.getOrNull(items.indexOf(it) + 1)?.text == "LISTS" } ->
                ForEachCommand.ForEachMode.IN_LISTS
            items.any { it.text == "IN" && items.getOrNull(items.indexOf(it) + 1)?.text == "ITEMS" } ->
                ForEachCommand.ForEachMode.IN_ITEMS
            items.any { it.text == "ZIP_LISTS" } -> ForEachCommand.ForEachMode.ZIP_LISTS
            else -> ForEachCommand.ForEachMode.ITEMS
        }

        return ForEachCommand(loopVar, items, mode, args)
    }

    private fun parseFunction(args: List<Token>): FunctionCommand {
        val name = args.getOrNull(0)?.text ?: ""
        val funcArgs = args.drop(1).map { it.text }
        return FunctionCommand(name, funcArgs, args)
    }

    private fun parseEndFunction(args: List<Token>): EndFunctionCommand {
        val name = args.getOrNull(0)?.text
        return EndFunctionCommand(name, args)
    }

    private fun parseMacro(args: List<Token>): MacroCommand {
        val name = args.getOrNull(0)?.text ?: ""
        val macroArgs = args.drop(1).map { it.text }
        return MacroCommand(name, macroArgs, args)
    }

    private fun parseEndMacro(args: List<Token>): EndMacroCommand {
        val name = args.getOrNull(0)?.text
        return EndMacroCommand(name, args)
    }

    private fun parseBlock(args: List<Token>): BlockCommand {
        val scopeFor = mutableListOf<String>()
        val propagate = mutableListOf<String>()

        var i = 0
        while (i < args.size) {
            when (args[i].text) {
                "SCOPE_FOR" -> {
                    i++
                    while (i < args.size && args[i].text !in listOf("PROPAGATE")) {
                        scopeFor.add(args[i].text)
                        i++
                    }
                }
                "PROPAGATE" -> {
                    i++
                    while (i < args.size && args[i].text !in listOf("SCOPE_FOR")) {
                        propagate.add(args[i].text)
                        i++
                    }
                }
                else -> i++
            }
        }

        return BlockCommand(scopeFor, propagate, args)
    }

    private fun parseReturn(args: List<Token>): ReturnCommand {
        val propagateIndex = args.indexOfFirst { it.text == "PROPAGATE" }
        val propagate = if (propagateIndex >= 0) {
            args.drop(propagateIndex + 1).map { it.text }
        } else {
            emptyList()
        }
        return ReturnCommand(propagate, args)
    }

    private fun parseInclude(args: List<Token>): IncludeCommand {
        val file = args.getOrNull(0)?.text ?: ""
        val isOptional = args.any { it.text == "OPTIONAL" }
        val noPolicy = args.any { it.text == "NO_POLICY_SCOPE" }
        val resultVarIndex = args.indexOfFirst { it.text == "RESULT_VARIABLE" }
        val resultVariable = if (resultVarIndex >= 0) args.getOrNull(resultVarIndex + 1)?.text else null

        return IncludeCommand(file, isOptional, resultVariable, noPolicy, args)
    }

    private fun parseCMakeMinimumRequired(args: List<Token>): CMakeMinimumRequiredCommand {
        val versionIndex = args.indexOfFirst { it.text == "VERSION" }
        val version = if (versionIndex >= 0) args.getOrNull(versionIndex + 1)?.text ?: "" else ""
        val fatalError = args.any { it.text == "FATAL_ERROR" }

        return CMakeMinimumRequiredCommand(version, fatalError, args)
    }

    private fun parseCMakePolicy(args: List<Token>): CMakePolicyCommand {
        val operation = when (args.getOrNull(0)?.text) {
            "SET" -> {
                val policy = args.getOrNull(1)?.text ?: ""
                val value = args.getOrNull(2)?.text ?: ""
                CMakePolicyCommand.PolicyOperation.Set(policy, value)
            }
            "GET" -> {
                val policy = args.getOrNull(1)?.text ?: ""
                val variable = args.getOrNull(2)?.text ?: ""
                CMakePolicyCommand.PolicyOperation.Get(policy, variable)
            }
            "VERSION" -> {
                val version = args.getOrNull(1)?.text ?: ""
                CMakePolicyCommand.PolicyOperation.Version(version)
            }
            "PUSH" -> CMakePolicyCommand.PolicyOperation.Push
            "POP" -> CMakePolicyCommand.PolicyOperation.Pop
            else -> CMakePolicyCommand.PolicyOperation.Push
        }

        return CMakePolicyCommand(operation, args)
    }

    private fun parseOption(args: List<Token>): OptionCommand {
        val variable = args.getOrNull(0)?.text ?: ""
        val helpString = args.getOrNull(1)?.text ?: ""
        val initialValue = args.getOrNull(2)?.text

        return OptionCommand(variable, helpString, initialValue, args)
    }

    private fun parseFindPackage(args: List<Token>): FindPackageCommand {
        val packageName = args.getOrNull(0)?.text ?: ""

        var version: String? = null
        var isExact = false
        var isQuiet = false
        var isRequired = false
        val components = mutableListOf<String>()
        val optionalComponents = mutableListOf<String>()
        var isConfig = false
        var isModule = false

        var i = 1
        while (i < args.size) {
            when (args[i].text) {
                "EXACT" -> isExact = true
                "QUIET" -> isQuiet = true
                "REQUIRED" -> isRequired = true
                "CONFIG", "NO_MODULE" -> isConfig = true
                "MODULE" -> isModule = true
                "COMPONENTS" -> {
                    i++
                    while (i < args.size && args[i].text !in listOf("OPTIONAL_COMPONENTS", "CONFIG", "MODULE", "REQUIRED", "QUIET")) {
                        components.add(args[i].text)
                        i++
                    }
                    continue
                }
                "OPTIONAL_COMPONENTS" -> {
                    i++
                    while (i < args.size && args[i].text !in listOf("COMPONENTS", "CONFIG", "MODULE", "REQUIRED", "QUIET")) {
                        optionalComponents.add(args[i].text)
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

        return FindPackageCommand(
            packageName, version, isExact, isQuiet, isRequired,
            components, optionalComponents, isConfig, isModule, args
        )
    }

    private fun parseFindLibrary(args: List<Token>): FindLibraryCommand {
        val variable = args.getOrNull(0)?.text ?: ""
        val names = mutableListOf<String>()
        val hints = mutableListOf<String>()
        val paths = mutableListOf<String>()
        var isRequired = false

        var i = 1
        var currentSection = "NAMES"
        while (i < args.size) {
            when (args[i].text) {
                "NAMES" -> {
                    currentSection = "NAMES"
                    i++
                }
                "HINTS" -> {
                    currentSection = "HINTS"
                    i++
                }
                "PATHS" -> {
                    currentSection = "PATHS"
                    i++
                }
                "REQUIRED" -> {
                    isRequired = true
                    i++
                }
                else -> {
                    when (currentSection) {
                        "NAMES" -> names.add(args[i].text)
                        "HINTS" -> hints.add(args[i].text)
                        "PATHS" -> paths.add(args[i].text)
                    }
                    i++
                }
            }
        }

        // 如果没有 NAMES 关键字，第一个参数后的都是 names
        if (names.isEmpty() && args.size > 1) {
            names.addAll(args.drop(1).map { it.text })
        }

        return FindLibraryCommand(variable, names, hints, paths, isRequired, args)
    }

    private fun parseFindPath(args: List<Token>): FindPathCommand {
        val variable = args.getOrNull(0)?.text ?: ""
        val names = mutableListOf<String>()
        val hints = mutableListOf<String>()
        val paths = mutableListOf<String>()

        parseFind(args.drop(1), names, hints, paths)

        return FindPathCommand(variable, names, hints, paths, args)
    }

    private fun parseFindFile(args: List<Token>): FindFileCommand {
        val variable = args.getOrNull(0)?.text ?: ""
        val names = mutableListOf<String>()
        val hints = mutableListOf<String>()
        val paths = mutableListOf<String>()

        parseFind(args.drop(1), names, hints, paths)

        return FindFileCommand(variable, names, hints, paths, args)
    }

    private fun parseFindProgram(args: List<Token>): FindProgramCommand {
        val variable = args.getOrNull(0)?.text ?: ""
        val names = mutableListOf<String>()
        val hints = mutableListOf<String>()
        val paths = mutableListOf<String>()

        parseFind(args.drop(1), names, hints, paths)

        return FindProgramCommand(variable, names, hints, paths, args)
    }

    private fun parseFind(
        args: List<Token>,
        names: MutableList<String>,
        hints: MutableList<String>,
        paths: MutableList<String>
    ) {
        var i = 0
        var currentSection = "NAMES"
        while (i < args.size) {
            when (args[i].text) {
                "NAMES" -> {
                    currentSection = "NAMES"
                    i++
                }
                "HINTS" -> {
                    currentSection = "HINTS"
                    i++
                }
                "PATHS" -> {
                    currentSection = "PATHS"
                    i++
                }
                else -> {
                    when (currentSection) {
                        "NAMES" -> names.add(args[i].text)
                        "HINTS" -> hints.add(args[i].text)
                        "PATHS" -> paths.add(args[i].text)
                    }
                    i++
                }
            }
        }

        if (names.isEmpty() && args.isNotEmpty()) {
            names.addAll(args.map { it.text })
        }
    }

    private fun parseList(args: List<Token>): ListCommand {
        val operation = args.getOrNull(0)?.text?.let { op ->
            ListCommand.ListOperation.entries.find { it.name == op }
        } ?: ListCommand.ListOperation.LENGTH

        val listName = args.getOrNull(1)?.text ?: ""
        val operationArgs = args.drop(2)

        return ListCommand(operation, listName, operationArgs, args)
    }

    private fun parseString(args: List<Token>): StringCommand {
        val operation = args.getOrNull(0)?.text?.let { op ->
            StringCommand.StringOperation.entries.find { it.name == op }
        } ?: StringCommand.StringOperation.LENGTH

        val operationArgs = args.drop(1)

        return StringCommand(operation, operationArgs, args)
    }

    private fun parseFile(args: List<Token>): FileCommand {
        val operation = args.getOrNull(0)?.text?.let { op ->
            FileCommand.FileOperation.entries.find { it.name == op }
        } ?: FileCommand.FileOperation.READ

        val operationArgs = args.drop(1)

        return FileCommand(operation, operationArgs, args)
    }

    private fun parseMath(args: List<Token>): MathCommand {
        val exprIndex = args.indexOfFirst { it.text == "EXPR" }
        val outputVariable = args.getOrNull(exprIndex + 1)?.text ?: ""
        val expr = args.getOrNull(exprIndex + 2)?.text ?: ""

        val formatIndex = args.indexOfFirst { it.text == "OUTPUT_FORMAT" }
        val outputFormat = if (formatIndex >= 0) {
            args.getOrNull(formatIndex + 1)?.text?.let { fmt ->
                MathCommand.OutputFormat.entries.find { it.name == fmt }
            }
        } else {
            null
        }

        return MathCommand(expr, outputVariable, outputFormat, args)
    }

    private fun parseExecuteProcess(args: List<Token>): ExecuteProcessCommand {
        val commands = mutableListOf<List<String>>()
        var workingDirectory: String? = null
        var timeout: Int? = null
        var resultVariable: String? = null
        var outputVariable: String? = null
        var errorVariable: String? = null

        var i = 0
        while (i < args.size) {
            when (args[i].text) {
                "COMMAND" -> {
                    val cmd = mutableListOf<String>()
                    i++
                    while (i < args.size && args[i].text !in listOf("COMMAND", "WORKING_DIRECTORY", "TIMEOUT", "RESULT_VARIABLE", "OUTPUT_VARIABLE", "ERROR_VARIABLE")) {
                        cmd.add(args[i].text)
                        i++
                    }
                    commands.add(cmd)
                }
                "WORKING_DIRECTORY" -> {
                    workingDirectory = args.getOrNull(++i)?.text
                    i++
                }
                "TIMEOUT" -> {
                    timeout = args.getOrNull(++i)?.text?.toIntOrNull()
                    i++
                }
                "RESULT_VARIABLE" -> {
                    resultVariable = args.getOrNull(++i)?.text
                    i++
                }
                "OUTPUT_VARIABLE" -> {
                    outputVariable = args.getOrNull(++i)?.text
                    i++
                }
                "ERROR_VARIABLE" -> {
                    errorVariable = args.getOrNull(++i)?.text
                    i++
                }
                else -> i++
            }
        }

        return ExecuteProcessCommand(commands, workingDirectory, timeout, resultVariable, null, outputVariable, errorVariable, args)
    }

    private fun parseConfigureFile(args: List<Token>): ConfigureFileCommand {
        val input = args.getOrNull(0)?.text ?: ""
        val output = args.getOrNull(1)?.text ?: ""

        val isCopyOnly = args.any { it.text == "COPYONLY" }
        val isEscapeQuotes = args.any { it.text == "ESCAPE_QUOTES" }
        val isAtOnly = args.any { it.text == "@ONLY" }

        val newlineStyleIndex = args.indexOfFirst { it.text == "NEWLINE_STYLE" }
        val newlineStyle = if (newlineStyleIndex >= 0) {
            args.getOrNull(newlineStyleIndex + 1)?.text?.let { style ->
                ConfigureFileCommand.NewlineStyle.entries.find { it.name == style }
            }
        } else {
            null
        }

        return ConfigureFileCommand(input, output, isCopyOnly, isEscapeQuotes, isAtOnly, newlineStyle, args)
    }

    // ========== 项目命令解析 ==========

    private fun parseProject(args: List<Token>): ProjectCommand {
        val projectName = args.getOrNull(0)?.text ?: ""

        var version: String? = null
        var description: String? = null
        var homepage: String? = null
        val languages = mutableListOf<String>()

        var i = 1
        while (i < args.size) {
            when (args[i].text) {
                "VERSION" -> {
                    version = args.getOrNull(++i)?.text
                    i++
                }
                "DESCRIPTION" -> {
                    description = args.getOrNull(++i)?.text
                    i++
                }
                "HOMEPAGE_URL" -> {
                    homepage = args.getOrNull(++i)?.text
                    i++
                }
                "LANGUAGES" -> {
                    i++
                    while (i < args.size && args[i].text !in listOf("VERSION", "DESCRIPTION", "HOMEPAGE_URL")) {
                        languages.add(args[i].text)
                        i++
                    }
                }
                else -> {
                    // 没有 LANGUAGES 关键字时，后续参数是语言
                    if (args[i].text !in listOf("VERSION", "DESCRIPTION", "HOMEPAGE_URL")) {
                        languages.add(args[i].text)
                    }
                    i++
                }
            }
        }

        return ProjectCommand(projectName, version, description, homepage, languages, args)
    }

    private fun parseAddExecutable(args: List<Token>): AddExecutableCommand {
        val targetName = args.getOrNull(0)?.text ?: ""

        val isWin32 = args.any { it.text == "WIN32" }
        val isMacOSXBundle = args.any { it.text == "MACOSX_BUNDLE" }
        val isExcludeFromAll = args.any { it.text == "EXCLUDE_FROM_ALL" }
        val isImported = args.any { it.text == "IMPORTED" }

        val aliasIndex = args.indexOfFirst { it.text == "ALIAS" }
        val isAlias = aliasIndex >= 0
        val aliasTarget = if (isAlias) args.getOrNull(aliasIndex + 1)?.text else null

        val sources = args.drop(1)
            .filter { it.text !in listOf("WIN32", "MACOSX_BUNDLE", "EXCLUDE_FROM_ALL", "IMPORTED", "ALIAS", "GLOBAL") }
            .filter { it.text != aliasTarget }
            .map { it.text }

        return AddExecutableCommand(
            targetName, sources, isWin32, isMacOSXBundle, isExcludeFromAll,
            isImported, isAlias, aliasTarget, args
        )
    }

    private fun parseAddLibrary(args: List<Token>): AddLibraryCommand {
        val targetName = args.getOrNull(0)?.text ?: ""

        val libraryType = args.drop(1).firstOrNull { it.text in listOf("STATIC", "SHARED", "MODULE", "OBJECT", "INTERFACE") }
            ?.text?.let { AddLibraryCommand.LibraryType.valueOf(it) }

        val isExcludeFromAll = args.any { it.text == "EXCLUDE_FROM_ALL" }
        val isImported = args.any { it.text == "IMPORTED" }

        val aliasIndex = args.indexOfFirst { it.text == "ALIAS" }
        val isAlias = aliasIndex >= 0
        val aliasTarget = if (isAlias) args.getOrNull(aliasIndex + 1)?.text else null

        val sources = args.drop(1)
            .filter { it.text !in listOf("STATIC", "SHARED", "MODULE", "OBJECT", "INTERFACE", "EXCLUDE_FROM_ALL", "IMPORTED", "ALIAS", "GLOBAL") }
            .filter { it.text != aliasTarget }
            .map { it.text }

        return AddLibraryCommand(
            targetName,
            libraryType,
            sources,
            isExcludeFromAll,
            isImported,
            isAlias,
            aliasTarget,
            args
        )
    }

    private fun parseTargetLinkLibraries(args: List<Token>): TargetLinkLibrariesCommand {
        val target = args.getOrNull(0)?.text ?: ""
        val libraries = mutableListOf<TargetLinkLibrariesCommand.LibraryLink>()

        var currentVisibility = TargetLinkLibrariesCommand.Visibility.PUBLIC
        for (i in 1 until args.size) {
            when (args[i].text) {
                "PUBLIC" -> currentVisibility = TargetLinkLibrariesCommand.Visibility.PUBLIC
                "PRIVATE" -> currentVisibility = TargetLinkLibrariesCommand.Visibility.PRIVATE
                "INTERFACE" -> currentVisibility = TargetLinkLibrariesCommand.Visibility.INTERFACE
                else -> libraries.add(TargetLinkLibrariesCommand.LibraryLink(args[i].text, currentVisibility))
            }
        }

        return TargetLinkLibrariesCommand(target, libraries, args)
    }

    private fun parseTargetIncludeDirectories(args: List<Token>): TargetIncludeDirectoriesCommand {
        val target = args.getOrNull(0)?.text ?: ""
        val directories = mutableListOf<TargetIncludeDirectoriesCommand.DirectoryEntry>()
        var isSystem = false
        var isBefore = false

        var currentVisibility = TargetIncludeDirectoriesCommand.Visibility.PUBLIC
        for (i in 1 until args.size) {
            when (args[i].text) {
                "SYSTEM" -> isSystem = true
                "BEFORE" -> isBefore = true
                "AFTER" -> isBefore = false
                "PUBLIC" -> currentVisibility = TargetIncludeDirectoriesCommand.Visibility.PUBLIC
                "PRIVATE" -> currentVisibility = TargetIncludeDirectoriesCommand.Visibility.PRIVATE
                "INTERFACE" -> currentVisibility = TargetIncludeDirectoriesCommand.Visibility.INTERFACE
                else -> directories.add(TargetIncludeDirectoriesCommand.DirectoryEntry(args[i].text, currentVisibility))
            }
        }

        return TargetIncludeDirectoriesCommand(target, directories, isSystem, isBefore, args)
    }

    private fun parseTargetCompileDefinitions(args: List<Token>): TargetCompileDefinitionsCommand {
        val target = args.getOrNull(0)?.text ?: ""
        val definitions = mutableListOf<TargetCompileDefinitionsCommand.DefinitionEntry>()

        var currentVisibility = TargetCompileDefinitionsCommand.Visibility.PUBLIC
        for (i in 1 until args.size) {
            when (args[i].text) {
                "PUBLIC" -> currentVisibility = TargetCompileDefinitionsCommand.Visibility.PUBLIC
                "PRIVATE" -> currentVisibility = TargetCompileDefinitionsCommand.Visibility.PRIVATE
                "INTERFACE" -> currentVisibility = TargetCompileDefinitionsCommand.Visibility.INTERFACE
                else -> definitions.add(TargetCompileDefinitionsCommand.DefinitionEntry(args[i].text, currentVisibility))
            }
        }

        return TargetCompileDefinitionsCommand(target, definitions, args)
    }

    private fun parseTargetCompileOptions(args: List<Token>): TargetCompileOptionsCommand {
        val target = args.getOrNull(0)?.text ?: ""
        val options = mutableListOf<TargetCompileOptionsCommand.OptionEntry>()
        var isBefore = false

        var currentVisibility = TargetCompileOptionsCommand.Visibility.PUBLIC
        for (i in 1 until args.size) {
            when (args[i].text) {
                "BEFORE" -> isBefore = true
                "PUBLIC" -> currentVisibility = TargetCompileOptionsCommand.Visibility.PUBLIC
                "PRIVATE" -> currentVisibility = TargetCompileOptionsCommand.Visibility.PRIVATE
                "INTERFACE" -> currentVisibility = TargetCompileOptionsCommand.Visibility.INTERFACE
                else -> options.add(TargetCompileOptionsCommand.OptionEntry(args[i].text, currentVisibility))
            }
        }

        return TargetCompileOptionsCommand(target, options, isBefore, args)
    }

    private fun parseTargetCompileFeatures(args: List<Token>): TargetCompileFeaturesCommand {
        val target = args.getOrNull(0)?.text ?: ""
        val features = mutableListOf<TargetCompileFeaturesCommand.FeatureEntry>()

        var currentVisibility = TargetCompileFeaturesCommand.Visibility.PUBLIC
        for (i in 1 until args.size) {
            when (args[i].text) {
                "PUBLIC" -> currentVisibility = TargetCompileFeaturesCommand.Visibility.PUBLIC
                "PRIVATE" -> currentVisibility = TargetCompileFeaturesCommand.Visibility.PRIVATE
                "INTERFACE" -> currentVisibility = TargetCompileFeaturesCommand.Visibility.INTERFACE
                else -> features.add(TargetCompileFeaturesCommand.FeatureEntry(args[i].text, currentVisibility))
            }
        }

        return TargetCompileFeaturesCommand(target, features, args)
    }

    private fun parseTargetSources(args: List<Token>): TargetSourcesCommand {
        val target = args.getOrNull(0)?.text ?: ""
        val sources = mutableListOf<TargetSourcesCommand.SourceEntry>()

        var currentVisibility = TargetSourcesCommand.Visibility.PUBLIC
        for (i in 1 until args.size) {
            when (args[i].text) {
                "PUBLIC" -> currentVisibility = TargetSourcesCommand.Visibility.PUBLIC
                "PRIVATE" -> currentVisibility = TargetSourcesCommand.Visibility.PRIVATE
                "INTERFACE" -> currentVisibility = TargetSourcesCommand.Visibility.INTERFACE
                else -> sources.add(TargetSourcesCommand.SourceEntry(args[i].text, currentVisibility))
            }
        }

        return TargetSourcesCommand(target, sources, args)
    }

    private fun parseTargetLinkDirectories(args: List<Token>): TargetLinkDirectoriesCommand {
        val target = args.getOrNull(0)?.text ?: ""
        val directories = mutableListOf<TargetLinkDirectoriesCommand.DirectoryEntry>()
        var isBefore = false

        var currentVisibility = TargetLinkDirectoriesCommand.Visibility.PUBLIC
        for (i in 1 until args.size) {
            when (args[i].text) {
                "BEFORE" -> isBefore = true
                "PUBLIC" -> currentVisibility = TargetLinkDirectoriesCommand.Visibility.PUBLIC
                "PRIVATE" -> currentVisibility = TargetLinkDirectoriesCommand.Visibility.PRIVATE
                "INTERFACE" -> currentVisibility = TargetLinkDirectoriesCommand.Visibility.INTERFACE
                else -> directories.add(TargetLinkDirectoriesCommand.DirectoryEntry(args[i].text, currentVisibility))
            }
        }

        return TargetLinkDirectoriesCommand(target, directories, isBefore, args)
    }

    private fun parseTargetLinkOptions(args: List<Token>): TargetLinkOptionsCommand {
        val target = args.getOrNull(0)?.text ?: ""
        val options = mutableListOf<TargetLinkOptionsCommand.OptionEntry>()
        var isBefore = false

        var currentVisibility = TargetLinkOptionsCommand.Visibility.PUBLIC
        for (i in 1 until args.size) {
            when (args[i].text) {
                "BEFORE" -> isBefore = true
                "PUBLIC" -> currentVisibility = TargetLinkOptionsCommand.Visibility.PUBLIC
                "PRIVATE" -> currentVisibility = TargetLinkOptionsCommand.Visibility.PRIVATE
                "INTERFACE" -> currentVisibility = TargetLinkOptionsCommand.Visibility.INTERFACE
                else -> options.add(TargetLinkOptionsCommand.OptionEntry(args[i].text, currentVisibility))
            }
        }

        return TargetLinkOptionsCommand(target, options, isBefore, args)
    }

    private fun parseAddSubdirectory(args: List<Token>): AddSubdirectoryCommand {
        val sourceDir = args.getOrNull(0)?.text ?: ""
        var binaryDir: String? = null
        var isExcludeFromAll = false
        var isSystem = false

        for (i in 1 until args.size) {
            when (args[i].text) {
                "EXCLUDE_FROM_ALL" -> isExcludeFromAll = true
                "SYSTEM" -> isSystem = true
                else -> if (binaryDir == null) binaryDir = args[i].text
            }
        }

        return AddSubdirectoryCommand(sourceDir, binaryDir, isExcludeFromAll, isSystem, args)
    }

    private fun parseAddDependencies(args: List<Token>): AddDependenciesCommand {
        val target = args.getOrNull(0)?.text ?: ""
        val dependencies = args.drop(1).map { it.text }

        return AddDependenciesCommand(target, dependencies, args)
    }

    private fun parseAddCustomCommand(args: List<Token>): AddCustomCommandCommand {
        // 简化实现，根据是否有 TARGET 关键字判断模式
        val targetIndex = args.indexOfFirst { it.text == "TARGET" }

        if (targetIndex >= 0) {
            val target = args.getOrNull(targetIndex + 1)?.text ?: ""
            val timing = when {
                args.any { it.text == "PRE_BUILD" } -> AddCustomCommandCommand.Mode.Timing.PRE_BUILD
                args.any { it.text == "PRE_LINK" } -> AddCustomCommandCommand.Mode.Timing.PRE_LINK
                else -> AddCustomCommandCommand.Mode.Timing.POST_BUILD
            }
            return AddCustomCommandCommand(AddCustomCommandCommand.Mode.Target(target, timing, emptyList()), args)
        }

        val outputs = mutableListOf<String>()
        val outputIndex = args.indexOfFirst { it.text == "OUTPUT" }
        if (outputIndex >= 0) {
            var i = outputIndex + 1
            while (i < args.size && args[i].text !in listOf("COMMAND", "DEPENDS", "COMMENT")) {
                outputs.add(args[i].text)
                i++
            }
        }

        return AddCustomCommandCommand(AddCustomCommandCommand.Mode.Output(outputs, emptyList()), args)
    }

    private fun parseAddCustomTarget(args: List<Token>): AddCustomTargetCommand {
        val targetName = args.getOrNull(0)?.text ?: ""
        val isAll = args.any { it.text == "ALL" }

        return AddCustomTargetCommand(targetName, isAll = isAll, rawArguments = args)
    }

    private fun parseAddTest(args: List<Token>): AddTestCommand {
        val nameIndex = args.indexOfFirst { it.text == "NAME" }
        val testName = if (nameIndex >= 0) {
            args.getOrNull(nameIndex + 1)?.text ?: ""
        } else {
            args.getOrNull(0)?.text ?: ""
        }

        val commandIndex = args.indexOfFirst { it.text == "COMMAND" }
        val command = if (commandIndex >= 0) {
            args.drop(commandIndex + 1).takeWhile { it.text !in listOf("WORKING_DIRECTORY", "CONFIGURATIONS") }.map { it.text }
        } else {
            args.drop(1).map { it.text }
        }

        return AddTestCommand(testName, command, rawArguments = args)
    }

    private fun parseInstall(args: List<Token>): InstallCommand {
        val installType = when (args.getOrNull(0)?.text) {
            "TARGETS" -> {
                val targets = mutableListOf<String>()
                var i = 1
                while (i < args.size && args[i].text !in listOf("DESTINATION", "COMPONENT", "RUNTIME", "LIBRARY", "ARCHIVE")) {
                    targets.add(args[i].text)
                    i++
                }
                val destIndex = args.indexOfFirst { it.text == "DESTINATION" }
                val destination = if (destIndex >= 0) args.getOrNull(destIndex + 1)?.text else null
                InstallCommand.InstallType.Targets(targets, destination)
            }
            "FILES" -> {
                val files = mutableListOf<String>()
                var i = 1
                while (i < args.size && args[i].text !in listOf("DESTINATION", "COMPONENT")) {
                    files.add(args[i].text)
                    i++
                }
                val destIndex = args.indexOfFirst { it.text == "DESTINATION" }
                val destination = if (destIndex >= 0) args.getOrNull(destIndex + 1)?.text ?: "" else ""
                InstallCommand.InstallType.Files(files, destination)
            }
            "DIRECTORY" -> {
                val directories = mutableListOf<String>()
                var i = 1
                while (i < args.size && args[i].text !in listOf("DESTINATION", "COMPONENT")) {
                    directories.add(args[i].text)
                    i++
                }
                val destIndex = args.indexOfFirst { it.text == "DESTINATION" }
                val destination = if (destIndex >= 0) args.getOrNull(destIndex + 1)?.text ?: "" else ""
                InstallCommand.InstallType.Directory(directories, destination)
            }
            "SCRIPT" -> InstallCommand.InstallType.Script(args.getOrNull(1)?.text ?: "")
            "CODE" -> InstallCommand.InstallType.Code(args.getOrNull(1)?.text ?: "")
            "EXPORT" -> {
                val exportName = args.getOrNull(1)?.text ?: ""
                val destIndex = args.indexOfFirst { it.text == "DESTINATION" }
                val destination = if (destIndex >= 0) args.getOrNull(destIndex + 1)?.text ?: "" else ""
                InstallCommand.InstallType.Export(exportName, destination)
            }
            else -> InstallCommand.InstallType.Files(emptyList(), "")
        }

        return InstallCommand(installType, args)
    }

    private fun parseIncludeDirectories(args: List<Token>): IncludeDirectoriesCommand {
        val isAfter = args.any { it.text == "AFTER" }
        val isBefore = args.any { it.text == "BEFORE" }
        val isSystem = args.any { it.text == "SYSTEM" }

        val directories = args.filter { it.text !in listOf("AFTER", "BEFORE", "SYSTEM") }.map { it.text }

        return IncludeDirectoriesCommand(directories, isAfter, isBefore, isSystem, args)
    }

    private fun parseLinkDirectories(args: List<Token>): LinkDirectoriesCommand {
        val isAfter = args.any { it.text == "AFTER" }
        val isBefore = args.any { it.text == "BEFORE" }

        val directories = args.filter { it.text !in listOf("AFTER", "BEFORE") }.map { it.text }

        return LinkDirectoriesCommand(directories, isAfter, isBefore, args)
    }

    private fun parseLinkLibraries(args: List<Token>): LinkLibrariesCommand {
        val libraries = args.map { it.text }
        return LinkLibrariesCommand(libraries, args)
    }

    private fun parseAddCompileDefinitions(args: List<Token>): AddCompileDefinitionsCommand = AddCompileDefinitionsCommand(args.map { it.text }, args)

    private fun parseAddCompileOptions(args: List<Token>): AddCompileOptionsCommand = AddCompileOptionsCommand(args.map { it.text }, args)

    private fun parseAddLinkOptions(args: List<Token>): AddLinkOptionsCommand = AddLinkOptionsCommand(args.map { it.text }, args)

    private fun parseSetProperty(args: List<Token>): SetPropertyCommand {
        val scopeType = args.getOrNull(0)?.text?.let { type ->
            SetPropertyCommand.PropertyScope.entries.find { it.name == type }
        } ?: SetPropertyCommand.PropertyScope.GLOBAL

        val propertyIndex = args.indexOfFirst { it.text == "PROPERTY" }
        val scopeNames = args.drop(1).take((propertyIndex - 1).coerceAtLeast(0)).map { it.text }
        val property = args.getOrNull(propertyIndex + 1)?.text ?: ""
        val values = args.drop(propertyIndex + 2).map { it.text }

        val isAppend = args.any { it.text == "APPEND" }
        val isAppendString = args.any { it.text == "APPEND_STRING" }

        return SetPropertyCommand(scopeType, scopeNames, property, values, isAppend, isAppendString, args)
    }

    private fun parseGetProperty(args: List<Token>): GetPropertyCommand {
        val variable = args.getOrNull(0)?.text ?: ""
        val scopeType = args.getOrNull(1)?.text?.let { type ->
            GetPropertyCommand.PropertyScope.entries.find { it.name == type }
        } ?: GetPropertyCommand.PropertyScope.GLOBAL

        val propertyIndex = args.indexOfFirst { it.text == "PROPERTY" }
        val scopeName = if (propertyIndex > 2) args.getOrNull(2)?.text else null
        val property = args.getOrNull(propertyIndex + 1)?.text ?: ""

        val isDefined = args.any { it.text == "DEFINED" }
        val isSet = args.any { it.text == "SET" }
        val isBrief = args.any { it.text == "BRIEF_DOCS" }

        return GetPropertyCommand(variable, scopeType, scopeName, property, isDefined, isSet, isBrief, args)
    }

    private fun parseSetTargetProperties(args: List<Token>): SetTargetPropertiesCommand {
        val propertiesIndex = args.indexOfFirst { it.text == "PROPERTIES" }
        val targets = args.take(propertiesIndex.coerceAtLeast(0)).map { it.text }

        val properties = mutableMapOf<String, String>()
        var i = propertiesIndex + 1
        while (i < args.size - 1) {
            properties[args[i].text] = args[i + 1].text
            i += 2
        }

        return SetTargetPropertiesCommand(targets, properties, args)
    }

    private fun parseGetTargetProperty(args: List<Token>): GetTargetPropertyCommand {
        val variable = args.getOrNull(0)?.text ?: ""
        val target = args.getOrNull(1)?.text ?: ""
        val property = args.getOrNull(2)?.text ?: ""

        return GetTargetPropertyCommand(variable, target, property, args)
    }

    private fun parseExport(args: List<Token>): ExportCommand {
        val exportType = when {
            args.any { it.text == "TARGETS" } -> {
                val targetsIndex = args.indexOfFirst { it.text == "TARGETS" }
                val targets = mutableListOf<String>()
                var i = targetsIndex + 1
                while (i < args.size && args[i].text !in listOf("FILE", "NAMESPACE", "EXPORT")) {
                    targets.add(args[i].text)
                    i++
                }
                val fileIndex = args.indexOfFirst { it.text == "FILE" }
                val file = if (fileIndex >= 0) args.getOrNull(fileIndex + 1)?.text ?: "" else ""
                val namespaceIndex = args.indexOfFirst { it.text == "NAMESPACE" }
                val namespace = if (namespaceIndex >= 0) args.getOrNull(namespaceIndex + 1)?.text else null
                ExportCommand.ExportType.Targets(targets, file, namespace)
            }
            args.any { it.text == "EXPORT" } -> {
                val exportIndex = args.indexOfFirst { it.text == "EXPORT" }
                val exportName = args.getOrNull(exportIndex + 1)?.text ?: ""
                val fileIndex = args.indexOfFirst { it.text == "FILE" }
                val file = if (fileIndex >= 0) args.getOrNull(fileIndex + 1)?.text ?: "" else ""
                ExportCommand.ExportType.Export(exportName, file)
            }
            args.any { it.text == "PACKAGE" } -> {
                val packageIndex = args.indexOfFirst { it.text == "PACKAGE" }
                val packageName = args.getOrNull(packageIndex + 1)?.text ?: ""
                ExportCommand.ExportType.Package(packageName)
            }
            else -> ExportCommand.ExportType.Targets(emptyList(), "")
        }

        return ExportCommand(exportType, args)
    }

    // ========== 高级脚本命令解析 ==========

    /**
     * 解析 cmake_parse_arguments 命令
     *
     * 语法:
     * cmake_parse_arguments(<prefix> <options> <one_value_keywords> <multi_value_keywords> <args>...)
     * cmake_parse_arguments(PARSE_ARGV <N> <prefix> <options> <one_value_keywords> <multi_value_keywords>)
     */
    private fun parseCMakeParseArguments(args: List<Token>): CMakeParseArgumentsCommand {
        if (args.isEmpty()) {
            return CMakeParseArgumentsCommand("", emptyList(), emptyList(), emptyList(), rawArguments = args)
        }

        // 检查是否是 PARSE_ARGV 模式
        if (args[0].text == "PARSE_ARGV") {
            val argvStart = args.getOrNull(1)?.text?.toIntOrNull() ?: 0
            val prefix = args.getOrNull(2)?.text ?: ""
            val options = parseListArgument(args.getOrNull(3)?.text ?: "")
            val oneValueKeywords = parseListArgument(args.getOrNull(4)?.text ?: "")
            val multiValueKeywords = parseListArgument(args.getOrNull(5)?.text ?: "")

            return CMakeParseArgumentsCommand(
                prefix = prefix,
                options = options,
                oneValueKeywords = oneValueKeywords,
                multiValueKeywords = multiValueKeywords,
                isParseArgv = true,
                argvStart = argvStart,
                rawArguments = args
            )
        }

        // 标准模式
        val prefix = args[0].text
        val options = parseListArgument(args.getOrNull(1)?.text ?: "")
        val oneValueKeywords = parseListArgument(args.getOrNull(2)?.text ?: "")
        val multiValueKeywords = parseListArgument(args.getOrNull(3)?.text ?: "")
        val parseArgs = args.drop(4).map { it.text }

        return CMakeParseArgumentsCommand(
            prefix = prefix,
            options = options,
            oneValueKeywords = oneValueKeywords,
            multiValueKeywords = multiValueKeywords,
            args = parseArgs,
            rawArguments = args
        )
    }

    /**
     * 解析列表参数（处理 "item1;item2;item3" 格式）
     */
    private fun parseListArgument(arg: String): List<String> {
        if (arg.isEmpty()) return emptyList()
        return arg.split(";").filter { it.isNotEmpty() }
    }

    /**
     * 解析 mark_as_advanced 命令
     *
     * 语法: mark_as_advanced([CLEAR|FORCE] <var1> ...)
     */
    private fun parseMarkAsAdvanced(args: List<Token>): MarkAsAdvancedCommand {
        if (args.isEmpty()) {
            return MarkAsAdvancedCommand(emptyList(), rawArguments = args)
        }

        var isClear = false
        var isForce = false
        var startIndex = 0

        when (args[0].text) {
            "CLEAR" -> {
                isClear = true
                startIndex = 1
            }
            "FORCE" -> {
                isForce = true
                startIndex = 1
            }
        }

        val variables = args.drop(startIndex).map { it.text }

        return MarkAsAdvancedCommand(variables, isClear, isForce, args)
    }

    /**
     * 解析 get_filename_component 命令
     *
     * 语法:
     * get_filename_component(<var> <FileName> <mode> [CACHE])
     * get_filename_component(<var> <FileName> PROGRAM [PROGRAM_ARGS <arg_var>] [CACHE])
     */
    private fun parseGetFilenameComponent(args: List<Token>): GetFilenameComponentCommand {
        val variable = args.getOrNull(0)?.text ?: ""
        val fileName = args.getOrNull(1)?.text ?: ""
        val modeStr = args.getOrNull(2)?.text ?: "NAME"

        val mode = GetFilenameComponentCommand.FilenameMode.entries.find { it.name == modeStr }
            ?: GetFilenameComponentCommand.FilenameMode.NAME

        val isCache = args.any { it.text == "CACHE" }

        // 处理 PROGRAM 模式的 PROGRAM_ARGS
        var programArgs: String? = null
        if (mode == GetFilenameComponentCommand.FilenameMode.PROGRAM) {
            val programArgsIndex = args.indexOfFirst { it.text == "PROGRAM_ARGS" }
            if (programArgsIndex >= 0) {
                programArgs = args.getOrNull(programArgsIndex + 1)?.text
            }
        }

        return GetFilenameComponentCommand(variable, fileName, mode, isCache, programArgs, args)
    }

    /**
     * 解析 try_compile 命令
     *
     * 语法:
     * try_compile(<resultVar> <bindir> <srcdir> <projectName> [<targetName>] [CMAKE_FLAGS <flags>...] [OUTPUT_VARIABLE <var>])
     * try_compile(<resultVar> <bindir> SOURCES <srcfile>... [CMAKE_FLAGS <flags>...] [COMPILE_DEFINITIONS <defs>...] ...)
     */
    private fun parseTryCompile(args: List<Token>): TryCompileCommand {
        val resultVariable = args.getOrNull(0)?.text ?: ""
        val binDir = args.getOrNull(1)?.text

        // 检查是否是 SOURCES 模式
        val sourcesIndex = args.indexOfFirst { it.text == "SOURCES" }

        if (sourcesIndex >= 0) {
            // SOURCES 模式
            val sources = mutableListOf<String>()
            var i = sourcesIndex + 1
            while (i < args.size && args[i].text !in listOf("CMAKE_FLAGS", "COMPILE_DEFINITIONS", "LINK_OPTIONS", "LINK_LIBRARIES", "OUTPUT_VARIABLE", "COPY_FILE")) {
                sources.add(args[i].text)
                i++
            }

            val cmakeFlags = parseKeywordList(args, "CMAKE_FLAGS")
            val compileDefinitions = parseKeywordList(args, "COMPILE_DEFINITIONS")
            val linkOptions = parseKeywordList(args, "LINK_OPTIONS")
            val linkLibraries = parseKeywordList(args, "LINK_LIBRARIES")
            val outputVariable = parseKeywordValue(args, "OUTPUT_VARIABLE")
            val copyFile = parseKeywordValue(args, "COPY_FILE")
            val copyFileError = parseKeywordValue(args, "COPY_FILE_ERROR")

            return TryCompileCommand(
                resultVariable = resultVariable,
                binDir = binDir,
                sources = sources,
                cmakeFlags = cmakeFlags,
                compileDefinitions = compileDefinitions,
                linkOptions = linkOptions,
                linkLibraries = linkLibraries,
                outputVariable = outputVariable,
                copyFile = copyFile,
                copyFileError = copyFileError,
                rawArguments = args
            )
        } else {
            // 项目模式
            val srcDir = args.getOrNull(2)?.text
            val projectName = args.getOrNull(3)?.text
            val targetName = args.getOrNull(4)?.text?.takeIf {
                it !in listOf("CMAKE_FLAGS", "OUTPUT_VARIABLE")
            }

            val cmakeFlags = parseKeywordList(args, "CMAKE_FLAGS")
            val outputVariable = parseKeywordValue(args, "OUTPUT_VARIABLE")

            return TryCompileCommand(
                resultVariable = resultVariable,
                binDir = binDir,
                srcDir = srcDir,
                projectName = projectName,
                targetName = targetName,
                cmakeFlags = cmakeFlags,
                outputVariable = outputVariable,
                rawArguments = args
            )
        }
    }

    /**
     * 解析 try_run 命令
     *
     * 语法:
     * try_run(<runResultVar> <compileResultVar> <bindir> <srcfile> [CMAKE_FLAGS <flags>...] ...)
     */
    private fun parseTryRun(args: List<Token>): TryRunCommand {
        val runResultVariable = args.getOrNull(0)?.text ?: ""
        val compileResultVariable = args.getOrNull(1)?.text ?: ""
        val binDir = args.getOrNull(2)?.text
        val srcFile = args.getOrNull(3)?.text

        val cmakeFlags = parseKeywordList(args, "CMAKE_FLAGS")
        val compileDefinitions = parseKeywordList(args, "COMPILE_DEFINITIONS")
        val linkOptions = parseKeywordList(args, "LINK_OPTIONS")
        val linkLibraries = parseKeywordList(args, "LINK_LIBRARIES")
        val compileOutputVariable = parseKeywordValue(args, "COMPILE_OUTPUT_VARIABLE")
        val runOutputVariable = parseKeywordValue(args, "RUN_OUTPUT_VARIABLE")
        val outputVariable = parseKeywordValue(args, "OUTPUT_VARIABLE")
        val runArgs = parseKeywordList(args, "ARGS")

        return TryRunCommand(
            runResultVariable = runResultVariable,
            compileResultVariable = compileResultVariable,
            binDir = binDir,
            srcFile = srcFile,
            cmakeFlags = cmakeFlags,
            compileDefinitions = compileDefinitions,
            linkOptions = linkOptions,
            linkLibraries = linkLibraries,
            compileOutputVariable = compileOutputVariable,
            runOutputVariable = runOutputVariable,
            outputVariable = outputVariable,
            runArgs = runArgs,
            rawArguments = args
        )
    }

    // ========== 辅助解析方法 ==========

    /**
     * 解析关键字后的单个值
     */
    private fun parseKeywordValue(args: List<Token>, keyword: String): String? {
        val index = args.indexOfFirst { it.text == keyword }
        return if (index >= 0) args.getOrNull(index + 1)?.text else null
    }

    /**
     * 解析关键字后的值列表（直到下一个关键字）
     */
    private fun parseKeywordList(args: List<Token>, keyword: String): List<String> {
        val keywords = setOf(
            "CMAKE_FLAGS", "COMPILE_DEFINITIONS", "LINK_OPTIONS", "LINK_LIBRARIES",
            "OUTPUT_VARIABLE", "COMPILE_OUTPUT_VARIABLE", "RUN_OUTPUT_VARIABLE",
            "COPY_FILE", "COPY_FILE_ERROR", "ARGS", "SOURCES", "WORKING_DIRECTORY"
        )

        val index = args.indexOfFirst { it.text == keyword }
        if (index < 0) return emptyList()

        val result = mutableListOf<String>()
        var i = index + 1
        while (i < args.size && args[i].text !in keywords) {
            result.add(args[i].text)
            i++
        }
        return result
    }
}
