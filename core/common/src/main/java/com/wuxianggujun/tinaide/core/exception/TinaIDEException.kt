package com.wuxianggujun.tinaide.core.exception

import android.content.Context
import com.wuxianggujun.tinaide.core.compile.BuildDiagnostic
import com.wuxianggujun.tinaide.core.compile.CompilerType

/**
 * TinaIDE 领域特定异常基类
 *
 * **设计目的**:
 * - 提供清晰的异常层次结构
 * - 包含用户友好的错误消息
 * - 支持诊断信息和恢复建议
 * - 便于统一的错误处理
 *
 * **使用示例**:
 * ```kotlin
 * try {
 *     compiler.compile(...)
 * } catch (e: TinaIDEException.CompileException) {
 *     showError("编译失败: ${e.userMessage}")
 *     e.diagnostics.forEach { diagnostic ->
 *         showDiagnostic(diagnostic)
 *     }
 *     if (e.recoverySuggestion != null) {
 *         showSuggestion(e.recoverySuggestion)
 *     }
 * }
 * ```
 *
 * @param message 技术性错误消息（用于日志）
 * @param userMessage 用户友好的错误消息（用于 UI 显示）
 * @param recoverySuggestion 恢复建议（可选）
 * @param cause 原始异常（可选）
 */
sealed class TinaIDEException(
    message: String,
    val userMessage: String = message,
    val recoverySuggestion: String? = null,
    cause: Throwable? = null
) : Exception(message, cause) {

    /**
     * 编译异常
     *
     * **使用场景**:
     * - 源代码编译失败
     * - 语法错误、类型错误等
     *
     * @param message 错误消息
     * @param diagnostics 编译诊断信息列表（包含文件、行号、错误类型等）
     * @param userMessage 用户友好的错误消息
     * @param recoverySuggestion 修复建议
     */
    class CompileException(
        message: String,
        val diagnostics: List<BuildDiagnostic> = emptyList(),
        userMessage: String,
        recoverySuggestion: String? = null
    ) : TinaIDEException(message, userMessage, recoverySuggestion) {

        companion object {
            /**
             * 创建带国际化消息的编译异常
             */
            fun create(context: Context, message: String, diagnostics: List<BuildDiagnostic> = emptyList()): CompileException = CompileException(
                message = message,
                diagnostics = diagnostics,
                userMessage = ExceptionMessages.buildCompileUserMessage(context, message, diagnostics),
                recoverySuggestion = ExceptionMessages.buildCompileRecoverySuggestion(context, diagnostics)
            )

            fun create(message: String, diagnostics: List<BuildDiagnostic> = emptyList()): CompileException = CompileException(
                message = message,
                diagnostics = diagnostics,
                userMessage = ExceptionMessages.buildCompileUserMessage(message, diagnostics),
                recoverySuggestion = ExceptionMessages.buildCompileRecoverySuggestion(diagnostics)
            )
        }
    }

    /**
     * 链接异常
     *
     * **使用场景**:
     * - 目标文件链接失败
     * - 找不到符号定义
     * - 库文件缺失
     */
    class LinkException(
        message: String,
        userMessage: String,
        recoverySuggestion: String? = null
    ) : TinaIDEException(message, userMessage, recoverySuggestion) {

        companion object {
            fun create(context: Context, message: String): LinkException = LinkException(
                message = message,
                userMessage = ExceptionMessages.linkFailed(context, message),
                recoverySuggestion = ExceptionMessages.linkSuggestion(context)
            )

            fun create(message: String): LinkException = LinkException(
                message = message,
                userMessage = ExceptionMessages.linkFailed(message),
                recoverySuggestion = ExceptionMessages.linkSuggestion()
            )
        }
    }

    /**
     * PRoot 环境异常
     *
     * **使用场景**:
     * - PRoot 环境未安装
     * - PRoot 进程启动失败
     * - Rootfs 损坏
     */
    class PRootEnvironmentException(
        message: String,
        userMessage: String,
        recoverySuggestion: String? = null,
        cause: Throwable? = null
    ) : TinaIDEException(message, userMessage, recoverySuggestion, cause) {

        companion object {
            /**
             * 创建"环境未安装"异常
             */
            fun notInstalled(context: Context): PRootEnvironmentException = PRootEnvironmentException(
                message = "PRoot environment not installed",
                userMessage = ExceptionMessages.prootNotInstalled(context),
                recoverySuggestion = ExceptionMessages.prootNotInstalledSuggestion(context)
            )

            fun notInstalled(): PRootEnvironmentException = PRootEnvironmentException(
                message = "PRoot environment not installed",
                userMessage = ExceptionMessages.prootNotInstalled(),
                recoverySuggestion = ExceptionMessages.prootNotInstalledSuggestion()
            )

            /**
             * 创建"环境正在安装"异常
             */
            fun installing(context: Context): PRootEnvironmentException = PRootEnvironmentException(
                message = "PRoot environment is being installed",
                userMessage = ExceptionMessages.prootInstalling(context),
                recoverySuggestion = ExceptionMessages.prootInstallingSuggestion(context)
            )

            fun installing(): PRootEnvironmentException = PRootEnvironmentException(
                message = "PRoot environment is being installed",
                userMessage = ExceptionMessages.prootInstalling(),
                recoverySuggestion = ExceptionMessages.prootInstallingSuggestion()
            )

            /**
             * 创建"环境需要更新"异常
             */
            fun needsUpdate(context: Context): PRootEnvironmentException = PRootEnvironmentException(
                message = "PRoot environment needs update",
                userMessage = ExceptionMessages.prootNeedsUpdate(context),
                recoverySuggestion = ExceptionMessages.prootNeedsUpdateSuggestion(context)
            )

            fun needsUpdate(): PRootEnvironmentException = PRootEnvironmentException(
                message = "PRoot environment needs update",
                userMessage = ExceptionMessages.prootNeedsUpdate(),
                recoverySuggestion = ExceptionMessages.prootNeedsUpdateSuggestion()
            )
        }
    }

    /**
     * 工具链未安装异常
     *
     * **使用场景**:
     * - 编译器未安装
     * - 调试器未安装
     * - 必需的开发工具缺失
     */
    class ToolchainNotInstalledException(
        val compilerType: CompilerType,
        val toolName: String,
        userMessage: String,
        recoverySuggestion: String? = null
    ) : TinaIDEException(
        message = "Toolchain not installed: $toolName",
        userMessage = userMessage,
        recoverySuggestion = recoverySuggestion
    ) {

        companion object {
            fun create(context: Context, compilerType: CompilerType): ToolchainNotInstalledException = ToolchainNotInstalledException(
                compilerType = compilerType,
                toolName = compilerType.getDisplayName(context),
                userMessage = ExceptionMessages.toolchainNotInstalled(context, compilerType),
                recoverySuggestion = ExceptionMessages.toolchainSuggestion(context, compilerType)
            )
        }
    }

    /**
     * 调试器异常
     *
     * **使用场景**:
     * - LLDB 未安装
     * - Python 绑定缺失
     * - 调试会话启动失败
     */
    class DebuggerException(
        message: String,
        userMessage: String,
        recoverySuggestion: String? = null,
        cause: Throwable? = null
    ) : TinaIDEException(message, userMessage, recoverySuggestion, cause) {

        companion object {
            /**
             * 创建"LLDB 未安装"异常
             */
            fun notInstalled(context: Context): DebuggerException = DebuggerException(
                message = "LLDB not installed",
                userMessage = ExceptionMessages.lldbNotInstalled(context),
                recoverySuggestion = ExceptionMessages.lldbSuggestion(context)
            )

            fun notInstalled(): DebuggerException = DebuggerException(
                message = "LLDB not installed",
                userMessage = ExceptionMessages.lldbNotInstalled(),
                recoverySuggestion = ExceptionMessages.lldbSuggestion()
            )

            /**
             * 创建"Python 绑定缺失"异常
             */
            fun pythonBindingMissing(context: Context): DebuggerException = DebuggerException(
                message = "LLDB Python binding missing",
                userMessage = ExceptionMessages.lldbPythonMissing(context),
                recoverySuggestion = ExceptionMessages.lldbPythonSuggestion(context)
            )

            fun pythonBindingMissing(): DebuggerException = DebuggerException(
                message = "LLDB Python binding missing",
                userMessage = ExceptionMessages.lldbPythonMissing(),
                recoverySuggestion = ExceptionMessages.lldbPythonSuggestion()
            )
        }
    }

    /**
     * 文件操作异常
     *
     * **使用场景**:
     * - 文件读写失败
     * - 权限不足
     * - 路径不合法
     */
    class FileOperationException(
        message: String,
        val filePath: String,
        userMessage: String,
        recoverySuggestion: String? = null,
        cause: Throwable? = null
    ) : TinaIDEException(message, userMessage, recoverySuggestion, cause) {

        companion object {
            fun create(context: Context, message: String, filePath: String, cause: Throwable? = null): FileOperationException = FileOperationException(
                message = message,
                filePath = filePath,
                userMessage = ExceptionMessages.fileOperationFailed(context, message),
                cause = cause
            )

            fun create(message: String, filePath: String, cause: Throwable? = null): FileOperationException = FileOperationException(
                message = message,
                filePath = filePath,
                userMessage = ExceptionMessages.fileOperationFailed(message),
                cause = cause
            )
        }
    }

    /**
     * 路径验证异常
     *
     * **使用场景**:
     * - 路径不在允许的范围内
     * - 路径格式不正确
     * - 路径包含危险字符
     */
    class PathValidationException(
        val path: String,
        message: String,
        userMessage: String,
        recoverySuggestion: String? = null
    ) : TinaIDEException(message, userMessage, recoverySuggestion) {

        companion object {
            fun create(context: Context, path: String): PathValidationException = PathValidationException(
                path = path,
                message = "Path $path is not in allowed range",
                userMessage = ExceptionMessages.pathNotAllowed(context, path),
                recoverySuggestion = ExceptionMessages.pathSuggestion(context)
            )

            fun create(path: String): PathValidationException = PathValidationException(
                path = path,
                message = "Path $path is not in allowed range",
                userMessage = ExceptionMessages.pathNotAllowed(path),
                recoverySuggestion = ExceptionMessages.pathSuggestion()
            )
        }
    }

    /**
     * 项目配置异常
     *
     * **使用场景**:
     * - CMakeLists.txt 格式错误
     * - 运行配置无效
     * - 构建系统检测失败
     */
    class ProjectConfigException(
        message: String,
        userMessage: String,
        recoverySuggestion: String? = null
    ) : TinaIDEException(message, userMessage, recoverySuggestion) {

        companion object {
            fun create(context: Context, message: String): ProjectConfigException = ProjectConfigException(
                message = message,
                userMessage = ExceptionMessages.projectConfigError(context, message)
            )

            fun create(message: String): ProjectConfigException = ProjectConfigException(
                message = message,
                userMessage = ExceptionMessages.projectConfigError(message)
            )
        }
    }

    /**
     * 网络异常
     *
     * **使用场景**:
     * - 工具链下载失败
     * - apk 更新失败
     * - DNS 解析失败
     */
    class NetworkException(
        message: String,
        userMessage: String,
        recoverySuggestion: String? = null,
        cause: Throwable? = null
    ) : TinaIDEException(message, userMessage, recoverySuggestion, cause) {

        companion object {
            /**
             * 创建"DNS 劫持"异常
             */
            fun dnsHijacked(context: Context, details: String): NetworkException = NetworkException(
                message = "DNS hijacked: $details",
                userMessage = ExceptionMessages.dnsHijacked(context),
                recoverySuggestion = ExceptionMessages.dnsSuggestion(context)
            )

            fun dnsHijacked(details: String): NetworkException = NetworkException(
                message = "DNS hijacked: $details",
                userMessage = ExceptionMessages.dnsHijacked(),
                recoverySuggestion = ExceptionMessages.dnsSuggestion()
            )

            /**
             * 创建"连接超时"异常
             */
            fun timeout(context: Context, operation: String): NetworkException = NetworkException(
                message = "Network timeout: $operation",
                userMessage = ExceptionMessages.networkTimeout(context),
                recoverySuggestion = ExceptionMessages.timeoutSuggestion(context)
            )

            fun timeout(operation: String): NetworkException = NetworkException(
                message = "Network timeout: $operation",
                userMessage = ExceptionMessages.networkTimeout(),
                recoverySuggestion = ExceptionMessages.timeoutSuggestion()
            )
        }
    }

    /**
     * 进程执行异常
     *
     * **使用场景**:
     * - 进程启动失败
     * - 进程执行超时
     * - 进程被终止
     */
    class ProcessExecutionException(
        message: String,
        val exitCode: Int? = null,
        userMessage: String,
        recoverySuggestion: String? = null,
        cause: Throwable? = null
    ) : TinaIDEException(message, userMessage, recoverySuggestion, cause) {

        companion object {
            fun create(context: Context, message: String, exitCode: Int? = null, cause: Throwable? = null): ProcessExecutionException = ProcessExecutionException(
                message = message,
                exitCode = exitCode,
                userMessage = ExceptionMessages.processFailed(context, message),
                cause = cause
            )

            fun create(message: String, exitCode: Int? = null, cause: Throwable? = null): ProcessExecutionException = ProcessExecutionException(
                message = message,
                exitCode = exitCode,
                userMessage = ExceptionMessages.processFailed(message),
                cause = cause
            )
        }
    }
}
