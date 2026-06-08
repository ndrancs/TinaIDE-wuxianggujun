package com.wuxianggujun.tinaide.core.exception

import android.content.Context
import androidx.annotation.StringRes
import com.wuxianggujun.tinaide.core.compile.BuildDiagnostic
import com.wuxianggujun.tinaide.core.compile.CompilerType
import com.wuxianggujun.tinaide.core.i18n.AppStrings
import com.wuxianggujun.tinaide.core.i18n.Strings

/**
 * 异常消息国际化辅助类
 *
 * 提供统一的异常消息生成方法，支持多语言
 */
object ExceptionMessages {

    private fun s(context: Context?, @StringRes resId: Int, vararg formatArgs: Any): String = AppStrings.getOr(context, resId, *formatArgs)

    /**
     * 构建编译失败的用户消息
     */
    fun buildCompileUserMessage(context: Context, message: String, diagnostics: List<BuildDiagnostic>): String = buildCompileUserMessageInternal(context = context, message = message, diagnostics = diagnostics)

    fun buildCompileUserMessage(message: String, diagnostics: List<BuildDiagnostic>): String = buildCompileUserMessageInternal(context = null, message = message, diagnostics = diagnostics)

    private fun buildCompileUserMessageInternal(context: Context?, message: String, diagnostics: List<BuildDiagnostic>): String {
        val firstError = diagnostics.firstOrNull { it.severity == BuildDiagnostic.Severity.ERROR }
        return if (firstError != null) {
            s(
                context,
                Strings.exception_compile_failed_at,
                firstError.file,
                firstError.line,
                firstError.message
            )
        } else {
            s(context, Strings.exception_compile_failed, message)
        }
    }

    /**
     * 构建编译恢复建议
     */
    fun buildCompileRecoverySuggestion(context: Context, diagnostics: List<BuildDiagnostic>): String? = buildCompileRecoverySuggestionInternal(context = context, diagnostics = diagnostics)

    fun buildCompileRecoverySuggestion(diagnostics: List<BuildDiagnostic>): String? = buildCompileRecoverySuggestionInternal(context = null, diagnostics = diagnostics)

    private fun buildCompileRecoverySuggestionInternal(context: Context?, diagnostics: List<BuildDiagnostic>): String? {
        if (diagnostics.isEmpty()) return null

        val errorCount = diagnostics.count { it.severity == BuildDiagnostic.Severity.ERROR }
        val warningCount = diagnostics.count { it.severity == BuildDiagnostic.Severity.WARNING }

        return buildString {
            append(s(context, Strings.exception_found_errors, errorCount))
            if (warningCount > 0) {
                append(s(context, Strings.exception_and_warnings, warningCount))
            }
            append(s(context, Strings.exception_check_and_fix))
        }
    }

    /**
     * 链接失败消息
     */
    fun linkFailed(context: Context, message: String): String = s(context, Strings.exception_link_failed, message)

    fun linkFailed(message: String): String = s(null, Strings.exception_link_failed, message)

    fun linkSuggestion(context: Context): String = s(context, Strings.exception_link_suggestion)

    fun linkSuggestion(): String = s(null, Strings.exception_link_suggestion)

    /**
     * PRoot 环境消息
     */
    fun prootError(context: Context, message: String): String = s(context, Strings.exception_proot_error, message)

    fun prootError(message: String): String = s(null, Strings.exception_proot_error, message)

    fun prootNotInstalled(context: Context): String = s(context, Strings.exception_proot_not_installed)

    fun prootNotInstalled(): String = s(null, Strings.exception_proot_not_installed)

    fun prootNotInstalledSuggestion(context: Context): String = s(context, Strings.exception_proot_not_installed_suggestion)

    fun prootNotInstalledSuggestion(): String = s(null, Strings.exception_proot_not_installed_suggestion)

    fun prootInstalling(context: Context): String = s(context, Strings.exception_proot_installing)

    fun prootInstalling(): String = s(null, Strings.exception_proot_installing)

    fun prootInstallingSuggestion(context: Context): String = s(context, Strings.exception_proot_installing_suggestion)

    fun prootInstallingSuggestion(): String = s(null, Strings.exception_proot_installing_suggestion)

    fun prootNeedsUpdate(context: Context): String = s(context, Strings.exception_proot_needs_update)

    fun prootNeedsUpdate(): String = s(null, Strings.exception_proot_needs_update)

    fun prootNeedsUpdateSuggestion(context: Context): String = s(context, Strings.exception_proot_needs_update_suggestion)

    fun prootNeedsUpdateSuggestion(): String = s(null, Strings.exception_proot_needs_update_suggestion)

    /**
     * 工具链消息
     */
    fun toolchainNotInstalled(context: Context, compilerType: CompilerType): String = s(context, Strings.exception_toolchain_not_installed, compilerType.getDisplayName(context))

    fun toolchainSuggestion(context: Context, compilerType: CompilerType): String = s(context, Strings.exception_toolchain_suggestion, compilerType.getDisplayName(context))

    /**
     * 调试器消息
     */
    fun debuggerError(context: Context, message: String): String = s(context, Strings.exception_debugger_error, message)

    fun debuggerError(message: String): String = s(null, Strings.exception_debugger_error, message)

    fun lldbNotInstalled(context: Context): String = s(context, Strings.exception_lldb_not_installed)

    fun lldbNotInstalled(): String = s(null, Strings.exception_lldb_not_installed)

    fun lldbSuggestion(context: Context): String = s(context, Strings.exception_lldb_suggestion)

    fun lldbSuggestion(): String = s(null, Strings.exception_lldb_suggestion)

    fun lldbPythonMissing(context: Context): String = s(context, Strings.exception_lldb_python_missing)

    fun lldbPythonMissing(): String = s(null, Strings.exception_lldb_python_missing)

    fun lldbPythonSuggestion(context: Context): String = s(context, Strings.exception_lldb_python_suggestion)

    fun lldbPythonSuggestion(): String = s(null, Strings.exception_lldb_python_suggestion)

    /**
     * 文件操作消息
     */
    fun fileOperationFailed(context: Context, message: String): String = s(context, Strings.exception_file_operation_failed, message)

    fun fileOperationFailed(message: String): String = s(null, Strings.exception_file_operation_failed, message)

    /**
     * 路径验证消息
     */
    fun pathNotAllowed(context: Context, path: String): String = s(context, Strings.exception_path_not_allowed, path)

    fun pathNotAllowed(path: String): String = s(null, Strings.exception_path_not_allowed, path)

    fun pathSuggestion(context: Context): String = s(context, Strings.exception_path_suggestion)

    fun pathSuggestion(): String = s(null, Strings.exception_path_suggestion)

    /**
     * 项目配置消息
     */
    fun projectConfigError(context: Context, message: String): String = s(context, Strings.exception_project_config_error, message)

    fun projectConfigError(message: String): String = s(null, Strings.exception_project_config_error, message)

    /**
     * 网络消息
     */
    fun networkError(context: Context, message: String): String = s(context, Strings.exception_network_error, message)

    fun networkError(message: String): String = s(null, Strings.exception_network_error, message)

    fun networkSuggestion(context: Context): String = s(context, Strings.exception_network_suggestion)

    fun networkSuggestion(): String = s(null, Strings.exception_network_suggestion)

    fun dnsHijacked(context: Context): String = s(context, Strings.exception_dns_hijacked)

    fun dnsHijacked(): String = s(null, Strings.exception_dns_hijacked)

    fun dnsSuggestion(context: Context): String = s(context, Strings.exception_dns_suggestion)

    fun dnsSuggestion(): String = s(null, Strings.exception_dns_suggestion)

    fun networkTimeout(context: Context): String = s(context, Strings.exception_network_timeout)

    fun networkTimeout(): String = s(null, Strings.exception_network_timeout)

    fun timeoutSuggestion(context: Context): String = s(context, Strings.exception_timeout_suggestion)

    fun timeoutSuggestion(): String = s(null, Strings.exception_timeout_suggestion)

    /**
     * 进程执行消息
     */
    fun processFailed(context: Context, message: String): String = s(context, Strings.exception_process_failed, message)

    fun processFailed(message: String): String = s(null, Strings.exception_process_failed, message)
}
