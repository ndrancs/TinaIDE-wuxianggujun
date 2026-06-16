package com.wuxianggujun.tinaide.ai.tools.editor

import com.wuxianggujun.tinaide.ai.api.ToolCall
import com.wuxianggujun.tinaide.ai.tools.AiTool
import com.wuxianggujun.tinaide.ai.tools.ToolCategory
import com.wuxianggujun.tinaide.ai.tools.ToolExecutionContext
import com.wuxianggujun.tinaide.ai.tools.ToolExecutionResult
import com.wuxianggujun.tinaide.ai.tools.ToolParameterParser
import com.wuxianggujun.tinaide.ai.tools.localizedToolText
import com.wuxianggujun.tinaide.ai.tools.rethrowIfCancellation
import com.wuxianggujun.tinaide.core.i18n.Strings
import com.wuxianggujun.tinaide.core.i18n.str
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * 获取当前文件工具
 * 读取编辑器中当前打开文件的完整内容和元数据
 */
object GetCurrentFileTool : AiTool {
    override val name = "get_current_file"
    override val description: String
        get() = localizedToolText(
            Strings.ai_tool_desc_get_current_file,
            "Retrieve the complete content of the currently open file in the editor, including file name, language type, and full text. Use this to understand the context of the current working file."
        )
    override val category = ToolCategory.EDITOR

    override fun getParameters(): JsonElement = buildJsonObject {
        put("type", "object")
        put("properties", buildJsonObject {})
        put("required", buildJsonArray {})
    }

    override suspend fun execute(toolCall: ToolCall, context: ToolExecutionContext): ToolExecutionResult {
        val callbacks = context.additionalData["editorCallbacks"] as? com.wuxianggujun.tinaide.ai.tools.executor.editor.EditorToolCallbacks
            ?: return ToolExecutionResult.Error(Strings.ai_tool_error_editor_callbacks_unavailable.str())

        val fileInfo = callbacks.getCurrentFile()
            ?: return ToolExecutionResult.Error(Strings.ai_tool_error_no_current_file_open.str())

        val metadata = mapOf(
            "fileName" to fileInfo.fileName,
            "language" to fileInfo.language,
            "lines" to fileInfo.content.lines().size
        )

        // 截断内容
        val content = if (fileInfo.content.length > 50_000) {
            fileInfo.content.take(50_000) + "\n... (truncated)"
        } else {
            fileInfo.content
        }

        val result = buildJsonObject {
            put("fileName", fileInfo.fileName)
            put("language", fileInfo.language)
            put("content", content)
        }.toString()

        return ToolExecutionResult.Success(result, metadata)
    }
}

/**
 * 获取选中代码工具
 * 获取用户在编辑器中选中的代码片段及其位置信息
 */
object GetSelectedCodeTool : AiTool {
    override val name = "get_selected_code"
    override val description: String
        get() = localizedToolText(
            Strings.ai_tool_desc_get_selected_code,
            "Get the code snippet currently selected by the user in the editor, including file name, language, line range (start/end), and the selected text. Useful for analyzing or modifying specific code sections."
        )
    override val category = ToolCategory.EDITOR

    override fun getParameters(): JsonElement = buildJsonObject {
        put("type", "object")
        put("properties", buildJsonObject {})
        put("required", buildJsonArray {})
    }

    override suspend fun execute(toolCall: ToolCall, context: ToolExecutionContext): ToolExecutionResult {
        val callbacks = context.additionalData["editorCallbacks"] as? com.wuxianggujun.tinaide.ai.tools.executor.editor.EditorToolCallbacks
            ?: return ToolExecutionResult.Error(Strings.ai_tool_error_editor_callbacks_unavailable.str())

        val codeInfo = callbacks.getSelectedCode()
            ?: return ToolExecutionResult.Error(Strings.ai_tool_error_no_code_selected.str())

        val metadata = mapOf(
            "fileName" to codeInfo.fileName,
            "language" to codeInfo.language,
            "startLine" to codeInfo.startLine,
            "endLine" to codeInfo.endLine,
            "lines" to codeInfo.content.lines().size
        )

        // 截断内容
        val content = if (codeInfo.content.length > 50_000) {
            codeInfo.content.take(50_000) + "\n... (truncated)"
        } else {
            codeInfo.content
        }

        val result = buildJsonObject {
            put("fileName", codeInfo.fileName)
            put("language", codeInfo.language)
            put("startLine", codeInfo.startLine)
            put("endLine", codeInfo.endLine)
            put("content", content)
        }.toString()

        return ToolExecutionResult.Success(result, metadata)
    }
}

/**
 * 插入代码工具
 * 在光标当前位置插入新代码，不替换现有内容
 */
object InsertCodeTool : AiTool {
    override val name = "insert_code"
    override val description: String
        get() = localizedToolText(
            Strings.ai_tool_desc_insert_code,
            "Insert code at the current cursor position in the editor without replacing existing content. Use this to add new code snippets, imports, or functions at the cursor location."
        )
    override val category = ToolCategory.EDITOR
    override val isDangerous = true

    override fun getParameters(): JsonElement = buildJsonObject {
        put("type", "object")
        put(
            "properties",
            buildJsonObject {
                put(
                    "code",
                    buildJsonObject {
                        put("type", "string")
                        put(
                            "description",
                            localizedToolText(
                                Strings.ai_tool_param_desc_the_code_to_insert,
                                "The code to insert"
                            )
                        )
                    }
                )
            }
        )
        put(
            "required",
            buildJsonArray {
                add(JsonPrimitive("code"))
            }
        )
    }

    override suspend fun execute(toolCall: ToolCall, context: ToolExecutionContext): ToolExecutionResult {
        val callbacks = context.additionalData["editorCallbacks"] as? com.wuxianggujun.tinaide.ai.tools.executor.editor.EditorToolCallbacks
            ?: return ToolExecutionResult.Error(Strings.ai_tool_error_editor_callbacks_unavailable.str())

        val args = ToolParameterParser.parseArguments(toolCall)
        val code = ToolParameterParser.getStringParameter(args, "code")

        if (code.isBlank()) {
            return ToolExecutionResult.Error(Strings.ai_tool_error_code_parameter_required.str())
        }

        return try {
            callbacks.insertCode(code)
            ToolExecutionResult.Success(Strings.ai_tool_success_code_inserted.str())
        } catch (e: Exception) {
            e.rethrowIfCancellation()
            ToolExecutionResult.Error(Strings.ai_tool_error_failed_insert_code.str(e.message))
        }
    }
}

/**
 * 替换选中代码工具
 * 用新代码替换用户当前选中的代码片段
 */
object ReplaceSelectedCodeTool : AiTool {
    override val name = "replace_selected_code"
    override val description: String
        get() = localizedToolText(
            Strings.ai_tool_desc_replace_selected_code,
            "Replace the currently selected code in the editor with new code. Use this for refactoring, fixing bugs, or updating specific code sections that the user has highlighted."
        )
    override val category = ToolCategory.EDITOR
    override val isDangerous = true

    override fun getParameters(): JsonElement = buildJsonObject {
        put("type", "object")
        put(
            "properties",
            buildJsonObject {
                put(
                    "code",
                    buildJsonObject {
                        put("type", "string")
                        put(
                            "description",
                            localizedToolText(
                                Strings.ai_tool_param_desc_the_new_code_to_replace_the_selection,
                                "The new code to replace the selection"
                            )
                        )
                    }
                )
            }
        )
        put(
            "required",
            buildJsonArray {
                add(JsonPrimitive("code"))
            }
        )
    }

    override suspend fun execute(toolCall: ToolCall, context: ToolExecutionContext): ToolExecutionResult {
        val callbacks = context.additionalData["editorCallbacks"] as? com.wuxianggujun.tinaide.ai.tools.executor.editor.EditorToolCallbacks
            ?: return ToolExecutionResult.Error(Strings.ai_tool_error_editor_callbacks_unavailable.str())

        val args = ToolParameterParser.parseArguments(toolCall)
        val code = ToolParameterParser.getStringParameter(args, "code")

        if (code.isBlank()) {
            return ToolExecutionResult.Error(Strings.ai_tool_error_code_parameter_required.str())
        }

        return try {
            callbacks.replaceSelectedCode(code)
            ToolExecutionResult.Success(Strings.ai_tool_success_code_replaced.str())
        } catch (e: Exception) {
            e.rethrowIfCancellation()
            ToolExecutionResult.Error(Strings.ai_tool_error_failed_replace_code.str(e.message))
        }
    }
}
