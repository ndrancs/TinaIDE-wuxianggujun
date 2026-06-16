package com.wuxianggujun.tinaide.ai.tools

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ToolInitializerTest {

    @Test
    fun `registerBuiltInTools registers complete stable tool catalog`() {
        ToolRegistry.clear()
        try {
            ToolInitializer.registerBuiltInTools()

            val tools = ToolRegistry.getAllTools()
            val names = tools.map { it.name }

            assertThat(names).containsExactlyElementsIn(expectedBuiltInToolNames())
            assertThat(names.distinct()).hasSize(expectedBuiltInToolNames().size)
            assertThat(tools).hasSize(41)
            assertThat(ToolRegistry.getEnabledTools()).hasSize(41)

            assertThat(tools.filter { it.isDangerous }.map { it.name })
                .containsExactlyElementsIn(expectedDangerousToolNames())
            assertThat(ToolInitializer.getToolStatistics().dangerousCount).isEqualTo(15)
            assertThat(ToolInitializer.getToolStatistics().byCategory[ToolCategory.FILE_SYSTEM]).isEqualTo(13)
            assertThat(ToolInitializer.getToolStatistics().byCategory[ToolCategory.EXECUTION]).isEqualTo(6)
            assertThat(ToolInitializer.getToolStatistics().byCategory[ToolCategory.BUILD]).isEqualTo(3)

            ToolInitializer.getDefaultEnabledToolNames().forEach { name ->
                assertThat(names).contains(name)
            }
            ToolInitializer.getRecommendedToolSets().values.flatten().forEach { name ->
                assertThat(names).contains(name)
            }
        } finally {
            ToolRegistry.clear()
        }
    }

    @Test
    fun `registered tools expose valid request tool schema`() {
        ToolRegistry.clear()
        try {
            ToolInitializer.registerBuiltInTools()

            ToolRegistry.getAllTools().forEach { tool ->
                val requestTool = tool.toRequestTool()
                assertThat(requestTool.type).isEqualTo("function")
                assertThat(requestTool.function.name).isEqualTo(tool.name)
                assertThat(requestTool.function.description).isNotEmpty()
                assertThat(requestTool.function.parameters.toString()).contains("\"type\":\"object\"")
            }
        } finally {
            ToolRegistry.clear()
        }
    }

    @Test
    fun `registerBasicTools registers only editor essentials`() {
        ToolRegistry.clear()
        try {
            ToolInitializer.registerBasicTools()

            val tools = ToolRegistry.getAllTools()
            val names = tools.map { it.name }
            val statistics = ToolInitializer.getToolStatistics()

            assertThat(names).containsExactly("get_current_file", "get_selected_code", "insert_code")
            assertThat(ToolRegistry.getEnabledTools().map { it.name }).containsExactlyElementsIn(names)
            assertThat(ToolInitializer.getToolsByCategory()).containsKey(ToolCategory.EDITOR)
            assertThat(ToolInitializer.getToolsByCategory()[ToolCategory.EDITOR]).containsExactlyElementsIn(tools)
            assertThat(statistics.totalCount).isEqualTo(3)
            assertThat(statistics.enabledCount).isEqualTo(3)
            assertThat(statistics.disabledCount).isEqualTo(0)
            assertThat(statistics.dangerousCount).isEqualTo(1)
            assertThat(tools.filter { it.isDangerous }.map { it.name }).containsExactly("insert_code")
            assertThat(statistics.byCategory).containsExactly(ToolCategory.EDITOR, 3)
        } finally {
            ToolRegistry.clear()
        }
    }

    private fun expectedBuiltInToolNames(): List<String> = listOf(
        "get_current_file",
        "get_selected_code",
        "insert_code",
        "replace_selected_code",
        "read_file",
        "write_file",
        "list_files",
        "delete_file",
        "create_directory",
        "move_file",
        "copy_file",
        "get_file_info",
        "replace_text",
        "replace_line",
        "insert_line",
        "delete_lines",
        "search_code",
        "find_symbol",
        "find_references",
        "get_code_outline",
        "get_diagnostics",
        "get_all_diagnostics",
        "clear_diagnostics",
        "run_project",
        "run_tests",
        "build_project",
        "stop_execution",
        "get_execution_status",
        "get_execution_output",
        "get_build_errors",
        "navigate_to_run_output",
        "navigate_to_build_log",
        "get_project_structure",
        "find_file",
        "count_code_lines",
        "format_code",
        "extract_method",
        "add_documentation",
        "github_search",
        "read_github_file",
        "web_search"
    )

    private fun expectedDangerousToolNames(): List<String> = listOf(
        "insert_code",
        "replace_selected_code",
        "write_file",
        "delete_file",
        "create_directory",
        "move_file",
        "copy_file",
        "replace_text",
        "replace_line",
        "insert_line",
        "delete_lines",
        "run_project",
        "run_tests",
        "stop_execution",
        "format_code"
    )
}
