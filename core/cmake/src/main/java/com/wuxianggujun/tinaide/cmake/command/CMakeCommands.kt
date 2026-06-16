/*
 * CMake Command Definitions for TinaIDE
 * Copyright (C) 2024 wuxianggujun
 *
 * CMake 命令定义 - 支持的所有 CMake 命令枚举
 * CMake 版本: v3.26
 * 参考: https://cmake.org/cmake/help/v3.26/manual/cmake-commands.7.html
 */

package com.wuxianggujun.tinaide.cmake.command

import com.wuxianggujun.tinaide.cmake.parser.Token

/**
 * 命令作用域
 */
enum class CommandScope {
    SCRIPTING, // 脚本命令 - 始终可用
    PROJECT, // 项目命令 - 仅在 CMake 项目中可用
    CTEST, // CTest 命令
    DEPRECATED // 已弃用命令
}

/**
 * CMake 命令基类
 */
sealed class CMakeCommand {
    abstract val name: String
    abstract val scope: CommandScope
    abstract val rawArguments: List<Token>
}

// =====================================================
// 脚本命令 (Scripting Commands)
// =====================================================

/**
 * set() - 设置变量
 */
data class SetCommand(
    val variable: String,
    val values: List<String>,
    val isParentScope: Boolean = false,
    val cacheType: CacheType? = null,
    val docstring: String? = null,
    val isForce: Boolean = false,
    val isEnv: Boolean = false,
    override val rawArguments: List<Token> = emptyList()
) : CMakeCommand() {
    override val name = "set"
    override val scope = CommandScope.SCRIPTING

    enum class CacheType { BOOL, FILEPATH, PATH, STRING, INTERNAL }
}

/**
 * unset() - 取消设置变量
 */
data class UnsetCommand(
    val variable: String,
    val isCache: Boolean = false,
    val isParentScope: Boolean = false,
    override val rawArguments: List<Token> = emptyList()
) : CMakeCommand() {
    override val name = "unset"
    override val scope = CommandScope.SCRIPTING
}

/**
 * message() - 输出消息
 */
data class MessageCommand(
    val mode: MessageMode?,
    val messages: List<String>,
    override val rawArguments: List<Token> = emptyList()
) : CMakeCommand() {
    override val name = "message"
    override val scope = CommandScope.SCRIPTING

    enum class MessageMode {
        FATAL_ERROR,
        SEND_ERROR,
        WARNING,
        AUTHOR_WARNING,
        DEPRECATION,
        NOTICE,
        STATUS,
        VERBOSE,
        DEBUG,
        TRACE,
        CHECK_START,
        CHECK_PASS,
        CHECK_FAIL
    }
}

/**
 * if/elseif/else/endif - 条件语句
 */
data class IfCommand(
    val condition: List<Token>,
    override val rawArguments: List<Token> = emptyList()
) : CMakeCommand() {
    override val name = "if"
    override val scope = CommandScope.SCRIPTING
}

data class ElseIfCommand(
    val condition: List<Token>,
    override val rawArguments: List<Token> = emptyList()
) : CMakeCommand() {
    override val name = "elseif"
    override val scope = CommandScope.SCRIPTING
}

data class ElseCommand(
    override val rawArguments: List<Token> = emptyList()
) : CMakeCommand() {
    override val name = "else"
    override val scope = CommandScope.SCRIPTING
}

data class EndIfCommand(
    override val rawArguments: List<Token> = emptyList()
) : CMakeCommand() {
    override val name = "endif"
    override val scope = CommandScope.SCRIPTING
}

/**
 * foreach/endforeach - 循环
 */
data class ForEachCommand(
    val loopVar: String,
    val items: List<Token>,
    val mode: ForEachMode = ForEachMode.ITEMS,
    override val rawArguments: List<Token> = emptyList()
) : CMakeCommand() {
    override val name = "foreach"
    override val scope = CommandScope.SCRIPTING

    enum class ForEachMode { ITEMS, RANGE, IN_LISTS, IN_ITEMS, ZIP_LISTS }
}

data class EndForEachCommand(
    override val rawArguments: List<Token> = emptyList()
) : CMakeCommand() {
    override val name = "endforeach"
    override val scope = CommandScope.SCRIPTING
}

/**
 * while/endwhile - 循环
 */
data class WhileCommand(
    val condition: List<Token>,
    override val rawArguments: List<Token> = emptyList()
) : CMakeCommand() {
    override val name = "while"
    override val scope = CommandScope.SCRIPTING
}

data class EndWhileCommand(
    override val rawArguments: List<Token> = emptyList()
) : CMakeCommand() {
    override val name = "endwhile"
    override val scope = CommandScope.SCRIPTING
}

/**
 * function/endfunction - 函数定义
 */
data class FunctionCommand(
    val functionName: String,
    val arguments: List<String>,
    override val rawArguments: List<Token> = emptyList()
) : CMakeCommand() {
    override val name = "function"
    override val scope = CommandScope.SCRIPTING
}

data class EndFunctionCommand(
    val functionName: String? = null,
    override val rawArguments: List<Token> = emptyList()
) : CMakeCommand() {
    override val name = "endfunction"
    override val scope = CommandScope.SCRIPTING
}

/**
 * macro/endmacro - 宏定义
 */
data class MacroCommand(
    val macroName: String,
    val arguments: List<String>,
    override val rawArguments: List<Token> = emptyList()
) : CMakeCommand() {
    override val name = "macro"
    override val scope = CommandScope.SCRIPTING
}

data class EndMacroCommand(
    val macroName: String? = null,
    override val rawArguments: List<Token> = emptyList()
) : CMakeCommand() {
    override val name = "endmacro"
    override val scope = CommandScope.SCRIPTING
}

/**
 * block/endblock - 块作用域 (CMake 3.25+)
 */
data class BlockCommand(
    val scopeFor: List<String> = emptyList(),
    val propagate: List<String> = emptyList(),
    override val rawArguments: List<Token> = emptyList()
) : CMakeCommand() {
    override val name = "block"
    override val scope = CommandScope.SCRIPTING
}

data class EndBlockCommand(
    override val rawArguments: List<Token> = emptyList()
) : CMakeCommand() {
    override val name = "endblock"
    override val scope = CommandScope.SCRIPTING
}

/**
 * break/continue/return - 流程控制
 */
data class BreakCommand(
    override val rawArguments: List<Token> = emptyList()
) : CMakeCommand() {
    override val name = "break"
    override val scope = CommandScope.SCRIPTING
}

data class ContinueCommand(
    override val rawArguments: List<Token> = emptyList()
) : CMakeCommand() {
    override val name = "continue"
    override val scope = CommandScope.SCRIPTING
}

data class ReturnCommand(
    val propagate: List<String> = emptyList(),
    override val rawArguments: List<Token> = emptyList()
) : CMakeCommand() {
    override val name = "return"
    override val scope = CommandScope.SCRIPTING
}

/**
 * include() - 包含其他 CMake 文件
 */
data class IncludeCommand(
    val file: String,
    val isOptional: Boolean = false,
    val resultVariable: String? = null,
    val noPolicy: Boolean = false,
    override val rawArguments: List<Token> = emptyList()
) : CMakeCommand() {
    override val name = "include"
    override val scope = CommandScope.SCRIPTING
}

/**
 * cmake_minimum_required() - 设置最低 CMake 版本
 */
data class CMakeMinimumRequiredCommand(
    val version: String,
    val fatalError: Boolean = false,
    override val rawArguments: List<Token> = emptyList()
) : CMakeCommand() {
    override val name = "cmake_minimum_required"
    override val scope = CommandScope.SCRIPTING
}

/**
 * cmake_policy() - 管理 CMake 策略
 */
data class CMakePolicyCommand(
    val operation: PolicyOperation,
    override val rawArguments: List<Token> = emptyList()
) : CMakeCommand() {
    override val name = "cmake_policy"
    override val scope = CommandScope.SCRIPTING

    sealed class PolicyOperation {
        data class Set(val policy: String, val value: String) : PolicyOperation()
        data class Get(val policy: String, val variable: String) : PolicyOperation()
        data class Version(val version: String) : PolicyOperation()
        object Push : PolicyOperation()
        object Pop : PolicyOperation()
    }
}

/**
 * option() - 定义选项
 */
data class OptionCommand(
    val variable: String,
    val helpString: String,
    val initialValue: String? = null,
    override val rawArguments: List<Token> = emptyList()
) : CMakeCommand() {
    override val name = "option"
    override val scope = CommandScope.SCRIPTING
}

/**
 * find_package() - 查找包
 */
data class FindPackageCommand(
    val packageName: String,
    val version: String? = null,
    val isExact: Boolean = false,
    val isQuiet: Boolean = false,
    val isRequired: Boolean = false,
    val components: List<String> = emptyList(),
    val optionalComponents: List<String> = emptyList(),
    val isConfig: Boolean = false,
    val isModule: Boolean = false,
    override val rawArguments: List<Token> = emptyList()
) : CMakeCommand() {
    override val name = "find_package"
    override val scope = CommandScope.SCRIPTING
}

/**
 * find_library/find_path/find_file/find_program - 查找命令
 */
data class FindLibraryCommand(
    val variable: String,
    val names: List<String>,
    val hints: List<String> = emptyList(),
    val paths: List<String> = emptyList(),
    val isRequired: Boolean = false,
    override val rawArguments: List<Token> = emptyList()
) : CMakeCommand() {
    override val name = "find_library"
    override val scope = CommandScope.SCRIPTING
}

data class FindPathCommand(
    val variable: String,
    val names: List<String>,
    val hints: List<String> = emptyList(),
    val paths: List<String> = emptyList(),
    override val rawArguments: List<Token> = emptyList()
) : CMakeCommand() {
    override val name = "find_path"
    override val scope = CommandScope.SCRIPTING
}

data class FindFileCommand(
    val variable: String,
    val names: List<String>,
    val hints: List<String> = emptyList(),
    val paths: List<String> = emptyList(),
    override val rawArguments: List<Token> = emptyList()
) : CMakeCommand() {
    override val name = "find_file"
    override val scope = CommandScope.SCRIPTING
}

data class FindProgramCommand(
    val variable: String,
    val names: List<String>,
    val hints: List<String> = emptyList(),
    val paths: List<String> = emptyList(),
    override val rawArguments: List<Token> = emptyList()
) : CMakeCommand() {
    override val name = "find_program"
    override val scope = CommandScope.SCRIPTING
}

/**
 * list() - 列表操作
 */
data class ListCommand(
    val operation: ListOperation,
    val listName: String,
    val operationArgs: List<Token>,
    override val rawArguments: List<Token> = emptyList()
) : CMakeCommand() {
    override val name = "list"
    override val scope = CommandScope.SCRIPTING

    enum class ListOperation {
        LENGTH,
        GET,
        JOIN,
        SUBLIST,
        FIND,
        APPEND,
        FILTER,
        INSERT,
        POP_BACK,
        POP_FRONT,
        PREPEND,
        REMOVE_ITEM,
        REMOVE_AT,
        REMOVE_DUPLICATES,
        REVERSE,
        SORT,
        TRANSFORM
    }
}

/**
 * string() - 字符串操作
 */
data class StringCommand(
    val operation: StringOperation,
    val operationArgs: List<Token>,
    override val rawArguments: List<Token> = emptyList()
) : CMakeCommand() {
    override val name = "string"
    override val scope = CommandScope.SCRIPTING

    enum class StringOperation {
        FIND,
        REPLACE,
        REGEX,
        APPEND,
        PREPEND,
        CONCAT,
        JOIN,
        TOLOWER,
        TOUPPER,
        LENGTH,
        SUBSTRING,
        STRIP,
        GENEX_STRIP,
        REPEAT,
        COMPARE,
        MD5,
        SHA1,
        SHA224,
        SHA256,
        SHA384,
        SHA512,
        ASCII,
        HEX,
        CONFIGURE,
        MAKE_C_IDENTIFIER,
        RANDOM,
        TIMESTAMP,
        UUID,
        JSON
    }
}

/**
 * file() - 文件操作
 */
data class FileCommand(
    val operation: FileOperation,
    val operationArgs: List<Token>,
    override val rawArguments: List<Token> = emptyList()
) : CMakeCommand() {
    override val name = "file"
    override val scope = CommandScope.SCRIPTING

    enum class FileOperation {
        READ,
        STRINGS,
        MD5,
        SHA1,
        SHA224,
        SHA256,
        SHA384,
        SHA512,
        WRITE,
        APPEND,
        TOUCH,
        TOUCH_NOCREATE,
        GENERATE,
        CONFIGURE,
        GLOB,
        GLOB_RECURSE,
        MAKE_DIRECTORY,
        REMOVE,
        REMOVE_RECURSE,
        RENAME,
        COPY_FILE,
        COPY,
        SIZE,
        READ_SYMLINK,
        CREATE_LINK,
        CHMOD,
        CHMOD_RECURSE,
        REAL_PATH,
        RELATIVE_PATH,
        TO_CMAKE_PATH,
        TO_NATIVE_PATH,
        DOWNLOAD,
        UPLOAD,
        LOCK,
        ARCHIVE_CREATE,
        ARCHIVE_EXTRACT,
        GET_RUNTIME_DEPENDENCIES
    }
}

/**
 * math() - 数学表达式
 */
data class MathCommand(
    val expr: String,
    val outputVariable: String,
    val outputFormat: OutputFormat? = null,
    override val rawArguments: List<Token> = emptyList()
) : CMakeCommand() {
    override val name = "math"
    override val scope = CommandScope.SCRIPTING

    enum class OutputFormat { DECIMAL, HEXADECIMAL }
}

/**
 * execute_process() - 执行进程
 */
data class ExecuteProcessCommand(
    val commands: List<List<String>>,
    val workingDirectory: String? = null,
    val timeout: Int? = null,
    val resultVariable: String? = null,
    val resultsVariable: String? = null,
    val outputVariable: String? = null,
    val errorVariable: String? = null,
    override val rawArguments: List<Token> = emptyList()
) : CMakeCommand() {
    override val name = "execute_process"
    override val scope = CommandScope.SCRIPTING
}

/**
 * configure_file() - 配置文件
 */
data class ConfigureFileCommand(
    val input: String,
    val output: String,
    val isCopyOnly: Boolean = false,
    val isEscapeQuotes: Boolean = false,
    val isAtOnly: Boolean = false,
    val newlineStyle: NewlineStyle? = null,
    override val rawArguments: List<Token> = emptyList()
) : CMakeCommand() {
    override val name = "configure_file"
    override val scope = CommandScope.SCRIPTING

    enum class NewlineStyle { UNIX, DOS, WIN32, LF, CRLF }
}

// =====================================================
// 项目命令 (Project Commands)
// =====================================================

/**
 * project() - 定义项目
 */
data class ProjectCommand(
    val projectName: String,
    val version: String? = null,
    val description: String? = null,
    val homepage: String? = null,
    val languages: List<String> = emptyList(),
    override val rawArguments: List<Token> = emptyList()
) : CMakeCommand() {
    override val name = "project"
    override val scope = CommandScope.PROJECT
}

/**
 * add_executable() - 添加可执行目标
 */
data class AddExecutableCommand(
    val targetName: String,
    val sources: List<String> = emptyList(),
    val isWin32: Boolean = false,
    val isMacOSXBundle: Boolean = false,
    val isExcludeFromAll: Boolean = false,
    val isImported: Boolean = false,
    val isAlias: Boolean = false,
    val aliasTarget: String? = null,
    override val rawArguments: List<Token> = emptyList()
) : CMakeCommand() {
    override val name = "add_executable"
    override val scope = CommandScope.PROJECT
}

/**
 * add_library() - 添加库目标
 */
data class AddLibraryCommand(
    val targetName: String,
    val libraryType: LibraryType? = null,
    val sources: List<String> = emptyList(),
    val isExcludeFromAll: Boolean = false,
    val isImported: Boolean = false,
    val isAlias: Boolean = false,
    val aliasTarget: String? = null,
    override val rawArguments: List<Token> = emptyList()
) : CMakeCommand() {
    override val name = "add_library"
    override val scope = CommandScope.PROJECT

    enum class LibraryType { STATIC, SHARED, MODULE, OBJECT, INTERFACE }
}

/**
 * target_link_libraries() - 链接库
 */
data class TargetLinkLibrariesCommand(
    val target: String,
    val libraries: List<LibraryLink>,
    override val rawArguments: List<Token> = emptyList()
) : CMakeCommand() {
    override val name = "target_link_libraries"
    override val scope = CommandScope.PROJECT

    data class LibraryLink(val name: String, val visibility: Visibility = Visibility.PUBLIC)
    enum class Visibility { PUBLIC, PRIVATE, INTERFACE }
}

/**
 * target_include_directories() - 添加包含目录
 */
data class TargetIncludeDirectoriesCommand(
    val target: String,
    val directories: List<DirectoryEntry>,
    val isSystem: Boolean = false,
    val isBefore: Boolean = false,
    override val rawArguments: List<Token> = emptyList()
) : CMakeCommand() {
    override val name = "target_include_directories"
    override val scope = CommandScope.PROJECT

    data class DirectoryEntry(val path: String, val visibility: Visibility = Visibility.PUBLIC)
    enum class Visibility { PUBLIC, PRIVATE, INTERFACE }
}

/**
 * target_compile_definitions() - 添加编译定义
 */
data class TargetCompileDefinitionsCommand(
    val target: String,
    val definitions: List<DefinitionEntry>,
    override val rawArguments: List<Token> = emptyList()
) : CMakeCommand() {
    override val name = "target_compile_definitions"
    override val scope = CommandScope.PROJECT

    data class DefinitionEntry(val definition: String, val visibility: Visibility = Visibility.PUBLIC)
    enum class Visibility { PUBLIC, PRIVATE, INTERFACE }
}

/**
 * target_compile_options() - 添加编译选项
 */
data class TargetCompileOptionsCommand(
    val target: String,
    val options: List<OptionEntry>,
    val isBefore: Boolean = false,
    override val rawArguments: List<Token> = emptyList()
) : CMakeCommand() {
    override val name = "target_compile_options"
    override val scope = CommandScope.PROJECT

    data class OptionEntry(val option: String, val visibility: Visibility = Visibility.PUBLIC)
    enum class Visibility { PUBLIC, PRIVATE, INTERFACE }
}

/**
 * target_compile_features() - 添加编译特性
 */
data class TargetCompileFeaturesCommand(
    val target: String,
    val features: List<FeatureEntry>,
    override val rawArguments: List<Token> = emptyList()
) : CMakeCommand() {
    override val name = "target_compile_features"
    override val scope = CommandScope.PROJECT

    data class FeatureEntry(val feature: String, val visibility: Visibility = Visibility.PUBLIC)
    enum class Visibility { PUBLIC, PRIVATE, INTERFACE }
}

/**
 * target_sources() - 添加源文件
 */
data class TargetSourcesCommand(
    val target: String,
    val sources: List<SourceEntry>,
    override val rawArguments: List<Token> = emptyList()
) : CMakeCommand() {
    override val name = "target_sources"
    override val scope = CommandScope.PROJECT

    data class SourceEntry(val source: String, val visibility: Visibility = Visibility.PUBLIC)
    enum class Visibility { PUBLIC, PRIVATE, INTERFACE }
}

/**
 * target_link_directories() - 添加链接目录
 */
data class TargetLinkDirectoriesCommand(
    val target: String,
    val directories: List<DirectoryEntry>,
    val isBefore: Boolean = false,
    override val rawArguments: List<Token> = emptyList()
) : CMakeCommand() {
    override val name = "target_link_directories"
    override val scope = CommandScope.PROJECT

    data class DirectoryEntry(val path: String, val visibility: Visibility = Visibility.PUBLIC)
    enum class Visibility { PUBLIC, PRIVATE, INTERFACE }
}

/**
 * target_link_options() - 添加链接选项
 */
data class TargetLinkOptionsCommand(
    val target: String,
    val options: List<OptionEntry>,
    val isBefore: Boolean = false,
    override val rawArguments: List<Token> = emptyList()
) : CMakeCommand() {
    override val name = "target_link_options"
    override val scope = CommandScope.PROJECT

    data class OptionEntry(val option: String, val visibility: Visibility = Visibility.PUBLIC)
    enum class Visibility { PUBLIC, PRIVATE, INTERFACE }
}

/**
 * add_subdirectory() - 添加子目录
 */
data class AddSubdirectoryCommand(
    val sourceDir: String,
    val binaryDir: String? = null,
    val isExcludeFromAll: Boolean = false,
    val isSystem: Boolean = false,
    override val rawArguments: List<Token> = emptyList()
) : CMakeCommand() {
    override val name = "add_subdirectory"
    override val scope = CommandScope.PROJECT
}

/**
 * add_dependencies() - 添加依赖
 */
data class AddDependenciesCommand(
    val target: String,
    val dependencies: List<String>,
    override val rawArguments: List<Token> = emptyList()
) : CMakeCommand() {
    override val name = "add_dependencies"
    override val scope = CommandScope.PROJECT
}

/**
 * add_custom_command() - 添加自定义命令
 */
data class AddCustomCommandCommand(
    val mode: Mode,
    override val rawArguments: List<Token> = emptyList()
) : CMakeCommand() {
    override val name = "add_custom_command"
    override val scope = CommandScope.PROJECT

    sealed class Mode {
        data class Output(
            val outputs: List<String>,
            val commands: List<List<String>>,
            val depends: List<String> = emptyList(),
            val workingDirectory: String? = null,
            val comment: String? = null
        ) : Mode()

        data class Target(
            val target: String,
            val timing: Timing,
            val commands: List<List<String>>,
            val workingDirectory: String? = null,
            val comment: String? = null
        ) : Mode()

        enum class Timing { PRE_BUILD, PRE_LINK, POST_BUILD }
    }
}

/**
 * add_custom_target() - 添加自定义目标
 */
data class AddCustomTargetCommand(
    val targetName: String,
    val commands: List<List<String>> = emptyList(),
    val depends: List<String> = emptyList(),
    val isAll: Boolean = false,
    val workingDirectory: String? = null,
    val comment: String? = null,
    override val rawArguments: List<Token> = emptyList()
) : CMakeCommand() {
    override val name = "add_custom_target"
    override val scope = CommandScope.PROJECT
}

/**
 * add_test() - 添加测试
 */
data class AddTestCommand(
    val testName: String,
    val command: List<String>,
    val workingDirectory: String? = null,
    override val rawArguments: List<Token> = emptyList()
) : CMakeCommand() {
    override val name = "add_test"
    override val scope = CommandScope.PROJECT
}

/**
 * enable_testing() - 启用测试
 */
data class EnableTestingCommand(
    override val rawArguments: List<Token> = emptyList()
) : CMakeCommand() {
    override val name = "enable_testing"
    override val scope = CommandScope.PROJECT
}

/**
 * install() - 安装规则
 */
data class InstallCommand(
    val installType: InstallType,
    override val rawArguments: List<Token> = emptyList()
) : CMakeCommand() {
    override val name = "install"
    override val scope = CommandScope.PROJECT

    sealed class InstallType {
        data class Targets(
            val targets: List<String>,
            val destination: String? = null,
            val component: String? = null
        ) : InstallType()

        data class Files(
            val files: List<String>,
            val destination: String,
            val component: String? = null
        ) : InstallType()

        data class Directory(
            val directories: List<String>,
            val destination: String,
            val component: String? = null
        ) : InstallType()

        data class Script(val script: String) : InstallType()
        data class Code(val code: String) : InstallType()
        data class Export(val exportName: String, val destination: String) : InstallType()
    }
}

/**
 * include_directories() - 全局包含目录
 */
data class IncludeDirectoriesCommand(
    val directories: List<String>,
    val isAfter: Boolean = false,
    val isBefore: Boolean = false,
    val isSystem: Boolean = false,
    override val rawArguments: List<Token> = emptyList()
) : CMakeCommand() {
    override val name = "include_directories"
    override val scope = CommandScope.PROJECT
}

/**
 * link_directories() - 全局链接目录
 */
data class LinkDirectoriesCommand(
    val directories: List<String>,
    val isAfter: Boolean = false,
    val isBefore: Boolean = false,
    override val rawArguments: List<Token> = emptyList()
) : CMakeCommand() {
    override val name = "link_directories"
    override val scope = CommandScope.PROJECT
}

/**
 * link_libraries() - 全局链接库
 */
data class LinkLibrariesCommand(
    val libraries: List<String>,
    override val rawArguments: List<Token> = emptyList()
) : CMakeCommand() {
    override val name = "link_libraries"
    override val scope = CommandScope.PROJECT
}

/**
 * add_compile_definitions() - 全局编译定义
 */
data class AddCompileDefinitionsCommand(
    val definitions: List<String>,
    override val rawArguments: List<Token> = emptyList()
) : CMakeCommand() {
    override val name = "add_compile_definitions"
    override val scope = CommandScope.PROJECT
}

/**
 * add_compile_options() - 全局编译选项
 */
data class AddCompileOptionsCommand(
    val options: List<String>,
    override val rawArguments: List<Token> = emptyList()
) : CMakeCommand() {
    override val name = "add_compile_options"
    override val scope = CommandScope.PROJECT
}

/**
 * add_link_options() - 全局链接选项
 */
data class AddLinkOptionsCommand(
    val options: List<String>,
    override val rawArguments: List<Token> = emptyList()
) : CMakeCommand() {
    override val name = "add_link_options"
    override val scope = CommandScope.PROJECT
}

/**
 * set_property() - 设置属性
 */
data class SetPropertyCommand(
    val scopeType: PropertyScope,
    val scopeNames: List<String>,
    val property: String,
    val values: List<String>,
    val isAppend: Boolean = false,
    val isAppendString: Boolean = false,
    override val rawArguments: List<Token> = emptyList()
) : CMakeCommand() {
    override val name = "set_property"
    override val scope = CommandScope.SCRIPTING

    enum class PropertyScope {
        GLOBAL,
        DIRECTORY,
        TARGET,
        SOURCE,
        INSTALL,
        TEST,
        CACHE
    }
}

/**
 * get_property() - 获取属性
 */
data class GetPropertyCommand(
    val variable: String,
    val scopeType: PropertyScope,
    val scopeName: String?,
    val property: String,
    val isDefined: Boolean = false,
    val isSet: Boolean = false,
    val isBrief: Boolean = false,
    override val rawArguments: List<Token> = emptyList()
) : CMakeCommand() {
    override val name = "get_property"
    override val scope = CommandScope.SCRIPTING

    enum class PropertyScope {
        GLOBAL,
        DIRECTORY,
        TARGET,
        SOURCE,
        INSTALL,
        TEST,
        CACHE,
        VARIABLE
    }
}

/**
 * set_target_properties() - 设置目标属性
 */
data class SetTargetPropertiesCommand(
    val targets: List<String>,
    val properties: Map<String, String>,
    override val rawArguments: List<Token> = emptyList()
) : CMakeCommand() {
    override val name = "set_target_properties"
    override val scope = CommandScope.PROJECT
}

/**
 * get_target_property() - 获取目标属性
 */
data class GetTargetPropertyCommand(
    val variable: String,
    val target: String,
    val property: String,
    override val rawArguments: List<Token> = emptyList()
) : CMakeCommand() {
    override val name = "get_target_property"
    override val scope = CommandScope.PROJECT
}

/**
 * export() - 导出目标
 */
data class ExportCommand(
    val exportType: ExportType,
    override val rawArguments: List<Token> = emptyList()
) : CMakeCommand() {
    override val name = "export"
    override val scope = CommandScope.PROJECT

    sealed class ExportType {
        data class Targets(
            val targets: List<String>,
            val file: String,
            val namespace: String? = null
        ) : ExportType()

        data class Export(
            val exportName: String,
            val file: String,
            val namespace: String? = null
        ) : ExportType()

        data class Package(val packageName: String) : ExportType()
    }
}

/**
 * try_compile() - 尝试编译
 *
 * 语法:
 * try_compile(<resultVar> <bindir> <srcdir>
 *             <projectName> [<targetName>] [CMAKE_FLAGS <flags>...]
 *             [OUTPUT_VARIABLE <var>])
 *
 * try_compile(<resultVar> <bindir> SOURCES <srcfile>...
 *             [CMAKE_FLAGS <flags>...]
 *             [COMPILE_DEFINITIONS <defs>...]
 *             [LINK_OPTIONS <options>...]
 *             [LINK_LIBRARIES <libs>...]
 *             [OUTPUT_VARIABLE <var>]
 *             [COPY_FILE <fileName> [COPY_FILE_ERROR <var>]]
 *             [<LANG>_STANDARD <std>]
 *             [<LANG>_STANDARD_REQUIRED <bool>]
 *             [<LANG>_EXTENSIONS <bool>])
 */
data class TryCompileCommand(
    val resultVariable: String,
    val binDir: String? = null,
    val srcDir: String? = null,
    val projectName: String? = null,
    val targetName: String? = null,
    val sources: List<String> = emptyList(),
    val cmakeFlags: List<String> = emptyList(),
    val compileDefinitions: List<String> = emptyList(),
    val linkOptions: List<String> = emptyList(),
    val linkLibraries: List<String> = emptyList(),
    val outputVariable: String? = null,
    val copyFile: String? = null,
    val copyFileError: String? = null,
    override val rawArguments: List<Token> = emptyList()
) : CMakeCommand() {
    override val name = "try_compile"
    override val scope = CommandScope.PROJECT
}

/**
 * try_run() - 尝试编译并运行
 *
 * 语法:
 * try_run(<runResultVar> <compileResultVar>
 *         <bindir> <srcfile> [CMAKE_FLAGS <flags>...]
 *         [COMPILE_DEFINITIONS <defs>...]
 *         [LINK_OPTIONS <options>...]
 *         [LINK_LIBRARIES <libs>...]
 *         [COMPILE_OUTPUT_VARIABLE <var>]
 *         [RUN_OUTPUT_VARIABLE <var>]
 *         [OUTPUT_VARIABLE <var>]
 *         [ARGS <args>...])
 */
data class TryRunCommand(
    val runResultVariable: String,
    val compileResultVariable: String,
    val binDir: String? = null,
    val srcFile: String? = null,
    val sources: List<String> = emptyList(),
    val cmakeFlags: List<String> = emptyList(),
    val compileDefinitions: List<String> = emptyList(),
    val linkOptions: List<String> = emptyList(),
    val linkLibraries: List<String> = emptyList(),
    val compileOutputVariable: String? = null,
    val runOutputVariable: String? = null,
    val outputVariable: String? = null,
    val runArgs: List<String> = emptyList(),
    override val rawArguments: List<Token> = emptyList()
) : CMakeCommand() {
    override val name = "try_run"
    override val scope = CommandScope.PROJECT
}

/**
 * mark_as_advanced() - 标记缓存变量为高级
 *
 * 语法:
 * mark_as_advanced([CLEAR|FORCE] <var1> ...)
 *
 * 将缓存变量标记为高级变量，这些变量在 CMake GUI 中默认不显示
 */
data class MarkAsAdvancedCommand(
    val variables: List<String>,
    val isClear: Boolean = false,
    val isForce: Boolean = false,
    override val rawArguments: List<Token> = emptyList()
) : CMakeCommand() {
    override val name = "mark_as_advanced"
    override val scope = CommandScope.SCRIPTING
}

/**
 * get_filename_component() - 获取文件名组件
 *
 * 语法:
 * get_filename_component(<var> <FileName> <mode> [CACHE])
 * get_filename_component(<var> <FileName> PROGRAM [PROGRAM_ARGS <arg_var>] [CACHE])
 *
 * 模式:
 * - DIRECTORY: 目录路径（不含文件名）
 * - NAME: 文件名（不含目录）
 * - EXT: 文件扩展名（最长匹配，如 .tar.gz）
 * - NAME_WE: 文件名（不含扩展名）
 * - LAST_EXT: 最后一个扩展名（如 .gz）
 * - NAME_WLE: 文件名（不含最后一个扩展名）
 * - PATH: 目录路径（已废弃，使用 DIRECTORY）
 * - ABSOLUTE: 绝对路径
 * - REALPATH: 解析符号链接后的绝对路径
 * - PROGRAM: 程序路径
 */
data class GetFilenameComponentCommand(
    val variable: String,
    val fileName: String,
    val mode: FilenameMode,
    val isCache: Boolean = false,
    val programArgs: String? = null,
    override val rawArguments: List<Token> = emptyList()
) : CMakeCommand() {
    override val name = "get_filename_component"
    override val scope = CommandScope.SCRIPTING

    enum class FilenameMode {
        DIRECTORY,
        NAME,
        EXT,
        NAME_WE,
        LAST_EXT,
        NAME_WLE,
        PATH,
        ABSOLUTE,
        REALPATH,
        PROGRAM
    }
}

/**
 * cmake_parse_arguments() - 解析函数/宏参数
 *
 * 语法:
 * cmake_parse_arguments(<prefix> <options> <one_value_keywords>
 *                       <multi_value_keywords> <args>...)
 *
 * cmake_parse_arguments(PARSE_ARGV <N> <prefix> <options>
 *                       <one_value_keywords> <multi_value_keywords>)
 *
 * 这是 CMake 函数库中最常用的命令之一，用于解析函数/宏的参数。
 *
 * 参数说明:
 * - prefix: 生成变量的前缀
 * - options: 布尔选项列表（存在则为 TRUE）
 * - one_value_keywords: 单值关键字列表
 * - multi_value_keywords: 多值关键字列表
 * - args: 要解析的参数
 *
 * 生成的变量:
 * - <prefix>_<option>: 每个选项的布尔值
 * - <prefix>_<keyword>: 每个关键字的值
 * - <prefix>_UNPARSED_ARGUMENTS: 未解析的参数
 * - <prefix>_KEYWORDS_MISSING_VALUES: 缺少值的关键字
 */
data class CMakeParseArgumentsCommand(
    val prefix: String,
    val options: List<String>,
    val oneValueKeywords: List<String>,
    val multiValueKeywords: List<String>,
    val args: List<String> = emptyList(),
    val isParseArgv: Boolean = false,
    val argvStart: Int? = null,
    override val rawArguments: List<Token> = emptyList()
) : CMakeCommand() {
    override val name = "cmake_parse_arguments"
    override val scope = CommandScope.SCRIPTING
}

/**
 * 未知命令 - 用于不支持的命令
 */
data class UnknownCommand(
    override val name: String,
    override val rawArguments: List<Token>
) : CMakeCommand() {
    override val scope = CommandScope.SCRIPTING
}
