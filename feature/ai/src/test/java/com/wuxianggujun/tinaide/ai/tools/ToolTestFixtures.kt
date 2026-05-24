package com.wuxianggujun.tinaide.ai.tools

import android.content.Context
import com.google.common.truth.Truth.assertThat
import com.wuxianggujun.tinaide.ai.api.ToolCall
import com.wuxianggujun.tinaide.ai.api.ToolFunction
import com.wuxianggujun.tinaide.core.i18n.AppStrings
import com.wuxianggujun.tinaide.core.i18n.R
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CancellationException

internal fun toolCall(toolName: String, arguments: String = "{}"): ToolCall = ToolCall(
    id = "call-1",
    type = "function",
    function = ToolFunction(
        name = toolName,
        arguments = arguments
    )
)

internal fun ToolExecutionResult.success(): ToolExecutionResult.Success = this as ToolExecutionResult.Success

internal fun ToolExecutionResult.error(): ToolExecutionResult.Error = this as ToolExecutionResult.Error

internal suspend fun assertCancellationRethrown(block: suspend () -> Unit) {
    try {
        block()
        throw AssertionError("Expected CancellationException")
    } catch (e: CancellationException) {
        assertThat(e).hasMessageThat().isEqualTo("cancelled")
    }
}

internal fun installToolTestAppStrings() {
    resetToolTestAppStrings()
    val context = mockk<Context>()
    val outputStrings = toolOutputTestStrings()
    every { context.applicationContext } returns context
    every { context.getString(any<Int>()) } answers {
        val resId = firstArg<Int>()
        outputStrings[resId] ?: when (resId) {
            R.string.ai_tool_error_execution_callbacks_unavailable -> "Execution callbacks not available. Please ensure project context is initialized."
            R.string.ai_tool_error_editor_callbacks_unavailable -> "Editor callbacks not available"
            R.string.ai_tool_error_project_root_missing -> "Project root not set. Please initialize project context first."
            R.string.ai_tool_error_execution_id_required -> "Execution ID is required"
            R.string.ai_tool_error_default_run_callback_unavailable -> "Project execution is unavailable because no run callback is registered."
            R.string.ai_tool_error_default_test_callback_unavailable -> "Test execution is unavailable because no test callback is registered."
            R.string.ai_tool_error_default_build_callback_unavailable -> "Build execution is unavailable because no build callback is registered."
            R.string.ai_tool_error_code_analysis_callbacks_unavailable -> "Code analysis callbacks not available"
            R.string.ai_tool_error_search_query_required -> "Search query is required"
            R.string.ai_tool_error_symbol_name_required -> "Symbol name is required"
            R.string.ai_tool_error_no_current_file_open -> "No current file open"
            R.string.ai_tool_error_no_code_selected -> "No code selected"
            R.string.ai_tool_error_code_parameter_required -> "Code parameter is required"
            R.string.ai_tool_error_file_name_pattern_required -> "File name pattern is required"
            R.string.ai_tool_error_file_path_required -> "File path is required"
            R.string.ai_tool_error_diagnostics_callbacks_unavailable -> "Diagnostics callbacks not available"
            R.string.ai_tool_error_file_system_callbacks_unavailable -> "File system callbacks not available"
            R.string.ai_tool_error_directory_path_required -> "Directory path is required"
            R.string.ai_tool_error_source_destination_required -> "Source and destination paths are required"
            R.string.ai_tool_error_old_text_required -> "Old text is required"
            R.string.ai_tool_error_line_number_min_one -> "Line number must be >= 1"
            R.string.ai_tool_error_start_line_min_one -> "Start line must be >= 1"
            R.string.ai_tool_error_end_line_before_start -> "End line must be >= start line"
            R.string.ai_tool_error_editor_callbacks_context_unavailable -> "Editor callbacks not available. Please ensure editor context is initialized."
            R.string.ai_tool_error_failed_format_code_unsupported -> "Failed to format code. The file may not support formatting or clang-format is not available."
            R.string.ai_tool_error_method_name_code_required -> "Method name and code are required"
            R.string.ai_tool_error_code_description_required -> "Code and description are required"
            R.string.ai_tool_success_navigated_build_log -> "Navigated to build log panel. The build log tab is now visible and focused."
            R.string.ai_tool_success_navigated_run_output -> "Navigated to run output panel. The run output tab is now visible and focused."
            R.string.ai_tool_success_project_clean -> "✅ No diagnostics found - project is clean!"
            R.string.ai_tool_success_no_diagnostics_found -> "No diagnostics found"
            R.string.ai_tool_success_code_replaced -> "Code replaced successfully"
            R.string.ai_tool_success_code_inserted -> "Code inserted successfully"
            else -> "string-$resId"
        }
    }
    every { context.getString(any<Int>(), *anyVararg()) } answers {
        val resId = firstArg<Int>()
        outputStrings[resId]?.formatToolTestString(*toolTestFormatArgs(invocation.args.toList()))
            ?: "string-$resId-formatted"
    }
    every { context.getString(R.string.ai_tool_success_execution_stopped, "exec-42") } returns "Execution stopped successfully: exec-42"
    every { context.getString(R.string.ai_tool_success_diagnostics_cleared_for_file, "src/main.cpp") } returns "Diagnostics cleared for: src/main.cpp"
    every { context.getString(R.string.ai_tool_error_failed_stop_execution_by_id, "exec-42") } returns "Failed to stop execution: exec-42 (execution may not exist or already completed)"
    every { context.getString(R.string.ai_tool_error_execution_not_found, "exec-missing") } returns "Execution not found: exec-missing (may have been cleaned up or never existed)"
    every { context.getString(R.string.ai_tool_error_execution_output_not_found, "exec-missing") } returns "Execution output not found: exec-missing (execution may not exist or output not available yet)"
    every { context.getString(R.string.ai_tool_error_directory_not_found, "definitely-missing-ai-test-dir") } returns "Directory not found: definitely-missing-ai-test-dir"
    every { context.getString(R.string.ai_tool_error_failed_clear_diagnostics_for_file, "src/main.cpp") } returns "Failed to clear diagnostics for: src/main.cpp"
    every { context.getString(R.string.ai_tool_error_start_line_after_end_line, 9, 3) } returns "Start line (9) must be less than or equal to end line (3)"
    every { context.getString(R.string.ai_tool_error_unsupported_element_type, "module") } returns "Unsupported element type: module"
    AppStrings.initialize(context)
}

private fun toolOutputTestStrings(): Map<Int, String> = mapOf(
    R.string.ai_tool_output_project_execution_started to "Project execution started",
    R.string.ai_tool_output_configuration to "Configuration: %s",
    R.string.ai_tool_output_execution_id to "Execution ID: %s",
    R.string.ai_tool_output_project_compiling_then_run to "The project is being compiled and will run shortly.",
    R.string.ai_tool_output_use_get_execution_status to "Use get_execution_status to check progress.",
    R.string.ai_tool_output_execution_completed to "Execution completed successfully",
    R.string.ai_tool_output_exit_code to "Exit Code: %s",
    R.string.ai_tool_output_duration_ms to "Duration: %sms",
    R.string.ai_tool_output_output_label to "Output:",
    R.string.ai_tool_output_truncated to "... (output truncated)",
    R.string.ai_tool_output_execution_failed to "Execution failed",
    R.string.ai_tool_output_error_label to "Error:",
    R.string.ai_tool_output_execution_status to "Execution status: %s",
    R.string.ai_tool_output_build_started to "Build started",
    R.string.ai_tool_output_mode_clean_build to "Mode: Clean build",
    R.string.ai_tool_output_target to "Target: %s",
    R.string.ai_tool_output_project_compiling to "The project is being compiled.",
    R.string.ai_tool_output_build_completed to "Build completed successfully",
    R.string.ai_tool_output_build_output_label to "Build Output:",
    R.string.ai_tool_output_build_failed to "Build failed",
    R.string.ai_tool_output_build_errors_label to "Build Errors:",
    R.string.ai_tool_output_build_status to "Build status: %s",
    R.string.ai_tool_output_execution_status_with_icon to "%s Execution Status: %s",
    R.string.ai_tool_output_execution_queued to "The execution is queued and waiting to start.",
    R.string.ai_tool_output_execution_in_progress to "The execution is currently in progress.",
    R.string.ai_tool_output_execution_completed_status to "The execution completed successfully.",
    R.string.ai_tool_output_execution_failed_status to "The execution failed. Check the execution result for details.",
    R.string.ai_tool_output_execution_cancelled_status to "The execution was cancelled by user.",
    R.string.ai_tool_output_execution_timeout_status to "The execution timed out.",
    R.string.ai_tool_output_execution_output_title to "Execution Output",
    R.string.ai_tool_output_status to "Status: %s",
    R.string.ai_tool_output_standard_output_label to "Standard Output:",
    R.string.ai_tool_output_error_output_label to "Error Output:",
    R.string.ai_tool_output_truncated_first_chars to "... (output truncated, showing first %s characters)",
    R.string.ai_tool_output_error_truncated_first_chars to "... (error output truncated, showing first %s characters)",
    R.string.ai_tool_output_build_errors_found to "❌ Build Errors Found",
    R.string.ai_tool_output_build_warnings_found to "⚠️ Build Warnings Found",
    R.string.ai_tool_output_no_build_errors_or_warnings to "✅ No Build Errors or Warnings",
    R.string.ai_tool_output_summary_label to "Summary:",
    R.string.ai_tool_output_errors_count to "Errors: %s",
    R.string.ai_tool_output_warnings_count to "Warnings: %s",
    R.string.ai_tool_output_info_count to "Info: %s",
    R.string.ai_tool_output_hints_count to "Hints: %s",
    R.string.ai_tool_output_details_label to "Details:",
    R.string.ai_tool_output_more_errors_warnings to "... and %s more errors/warnings",
    R.string.ai_tool_output_test_execution_started to "Test execution started",
    R.string.ai_tool_output_test_target_preparing to "The test target is being prepared and will run shortly.",
    R.string.ai_tool_output_tests_completed to "Tests completed successfully",
    R.string.ai_tool_output_tests_failed to "Tests failed",
    R.string.ai_tool_output_test_status to "Test status: %s",
    R.string.ai_tool_output_found_matches to "Found %s matches%s:",
    R.string.ai_tool_output_showing_first_suffix to " (showing first %s)",
    R.string.ai_tool_output_line_content to "Line %s: %s",
    R.string.ai_tool_output_found_symbols to "Found %s symbol(s):",
    R.string.ai_tool_output_location to "Location: %s",
    R.string.ai_tool_output_signature to "Signature: %s",
    R.string.ai_tool_output_documentation to "Documentation: %s",
    R.string.ai_tool_output_found_references to "Found %s reference(s):",
    R.string.ai_tool_output_reference_line_content to "%s Line %s:%s: %s",
    R.string.ai_tool_output_code_outline_for to "Code outline for %s (%s):",
    R.string.ai_tool_success_no_symbols_found to "No symbols found for: %s",
    R.string.ai_tool_success_no_references_found to "No references found for: %s",
    R.string.ai_tool_error_failed_search_code to "Failed to search code: %s",
    R.string.ai_tool_error_failed_find_symbol to "Failed to find symbol: %s",
    R.string.ai_tool_error_failed_find_references to "Failed to find references: %s",
    R.string.ai_tool_error_failed_get_code_outline to "Failed to get code outline: %s",
    R.string.ai_tool_output_found_diagnostics to "Found %s diagnostic(s):",
    R.string.ai_tool_output_diagnostic_line to "%s Line %s:%s - %s",
    R.string.ai_tool_output_code_label to "Code: %s",
    R.string.ai_tool_output_project_diagnostics_summary to "Project Diagnostics Summary:",
    R.string.ai_tool_output_total_issues to "Total Issues: %s",
    R.string.ai_tool_output_affected_files to "Affected Files: %s",
    R.string.ai_tool_output_files_with_issues to "Files with Issues:",
    R.string.ai_tool_output_file_issue_counts to "%s: %s error(s), %s warning(s)",
    R.string.ai_tool_output_more_files to "... and %s more file(s)",
    R.string.ai_tool_error_failed_get_diagnostics to "Failed to get diagnostics: %s",
    R.string.ai_tool_error_failed_get_all_diagnostics to "Failed to get all diagnostics: %s",
    R.string.ai_tool_error_failed_clear_diagnostics to "Failed to clear diagnostics: %s",
    R.string.ai_tool_output_project_structure to "Project Structure: %s",
    R.string.ai_tool_output_found_files_matching to "Found %s file(s) matching '%s':",
    R.string.ai_tool_output_results_limited to "(Results limited to %s files)",
    R.string.ai_tool_output_no_files_matching to "No files found matching the pattern.",
    R.string.ai_tool_output_code_statistics_for to "Code Statistics for: %s",
    R.string.ai_tool_output_total_summary to "Total Summary:",
    R.string.ai_tool_output_files_count to "Files: %s",
    R.string.ai_tool_output_total_lines_count to "Total Lines: %s",
    R.string.ai_tool_output_code_lines_count to "Code Lines: %s",
    R.string.ai_tool_output_comment_lines_count to "Comment Lines: %s",
    R.string.ai_tool_output_blank_lines_count to "Blank Lines: %s",
    R.string.ai_tool_output_breakdown_by_file_type to "Breakdown by File Type:",
    R.string.ai_tool_output_no_code_files_found to "No code files found.",
    R.string.ai_tool_output_file_type_percentage to ".%s (%s%%)",
    R.string.ai_tool_output_lines_count to "Lines: %s",
    R.string.ai_tool_output_code_count to "Code: %s",
    R.string.ai_tool_output_comments_count to "Comments: %s",
    R.string.ai_tool_output_blank_count to "Blank: %s",
    R.string.ai_tool_output_files_in to "Files in %s:",
    R.string.ai_tool_output_total_files to "Total: %s file(s)",
    R.string.ai_tool_output_directory_marker to "[DIR]",
    R.string.ai_tool_output_file_marker to "[FILE]",
    R.string.ai_tool_output_file_size_bytes to "%s bytes",
    R.string.ai_tool_output_lines_range to "(lines %s-%s)",
    R.string.ai_tool_output_code_formatted_success to "Code formatted successfully",
    R.string.ai_tool_output_file to "File: %s%s",
    R.string.ai_tool_output_code_formatted_clang to "The code has been formatted using clang-format.",
    R.string.ai_tool_output_changes_applied_editor to "Changes have been applied to the editor.",
    R.string.ai_tool_output_extracted_method to "Extracted method:",
    R.string.ai_tool_output_replace_original_code to "Replace the original code with:",
    R.string.ai_tool_output_generated_documentation to "Generated documentation:",
    R.string.ai_tool_output_original_code to "Original code:",
    R.string.ai_tool_doc_property_description to "The %s parameter",
    R.string.ai_tool_doc_param_description to "Description of %s",
    R.string.ai_tool_doc_return_description to "Description of return value",
    R.string.ai_tool_error_path_not_directory to "Path is not a directory: %s",
    R.string.exception_path_not_allowed to "Path %s is not in allowed range"
)

private fun toolTestFormatArgs(invocationArgs: List<Any?>): Array<Any?> {
    val formatArgs = invocationArgs.drop(1)
    val varargArray = formatArgs.singleOrNull()
    return if (varargArray is Array<*>) {
        @Suppress("UNCHECKED_CAST")
        (varargArray as Array<Any?>)
    } else {
        formatArgs.toTypedArray()
    }
}

private fun String.formatToolTestString(vararg formatArgs: Any?): String = runCatching { format(*formatArgs) }.getOrDefault(this)

internal fun resetToolTestAppStrings() {
    val field = AppStrings::class.java.getDeclaredField("appContext")
    field.isAccessible = true
    field.set(AppStrings, null)
}
