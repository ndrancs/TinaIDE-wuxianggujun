package com.wuxianggujun.tinaide.ai.tools.refactor

import com.wuxianggujun.tinaide.ai.api.ToolCall
import com.wuxianggujun.tinaide.ai.tools.AiTool
import com.wuxianggujun.tinaide.ai.tools.ToolCategory
import com.wuxianggujun.tinaide.ai.tools.ToolExecutionContext
import com.wuxianggujun.tinaide.ai.tools.ToolExecutionResult
import com.wuxianggujun.tinaide.ai.tools.ToolParameterParser
import com.wuxianggujun.tinaide.ai.tools.appendLocalizedToolLine
import com.wuxianggujun.tinaide.ai.tools.executor.editor.EditorToolCallbacks
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
 * 格式化代码工具
 * 使用 clang-format 格式化 C/C++ 代码
 */
object FormatCodeTool : AiTool {
    override val name = "format_code"
    override val description: String
        get() = localizedToolText(
            Strings.ai_tool_desc_format_code,
            "Format C/C++ code using clang-format. Automatically applies consistent code style including indentation, spacing, braces, and line breaks. Supports formatting entire files or specific code ranges. Essential for maintaining code quality and readability."
        )
    override val category = ToolCategory.REFACTOR
    override val isDangerous = true

    override fun getParameters(): JsonElement = buildJsonObject {
        put("type", "object")
        put(
            "properties",
            buildJsonObject {
                put(
                    "file_path",
                    buildJsonObject {
                        put("type", "string")
                        put(
                            "description",
                            localizedToolText(
                                Strings.ai_tool_param_desc_path_to_the_file_to_format_relative_to,
                                "Path to the file to format (relative to project root)"
                            )
                        )
                    }
                )
                put(
                    "start_line",
                    buildJsonObject {
                        put("type", "integer")
                        put(
                            "description",
                            localizedToolText(
                                Strings.ai_tool_param_desc_start_line_for_range_formatting_optional_1_based,
                                "Start line for range formatting (optional, 1-based)"
                            )
                        )
                        put("minimum", 1)
                    }
                )
                put(
                    "end_line",
                    buildJsonObject {
                        put("type", "integer")
                        put(
                            "description",
                            localizedToolText(
                                Strings.ai_tool_param_desc_end_line_for_range_formatting_optional_1_based,
                                "End line for range formatting (optional, 1-based)"
                            )
                        )
                        put("minimum", 1)
                    }
                )
            }
        )
        put(
            "required",
            buildJsonArray {
                add(JsonPrimitive("file_path"))
            }
        )
    }

    override suspend fun execute(toolCall: ToolCall, context: ToolExecutionContext): ToolExecutionResult {
        val callbacks = context.additionalData["editorCallbacks"] as? EditorToolCallbacks
            ?: return ToolExecutionResult.Error(Strings.ai_tool_error_editor_callbacks_context_unavailable.str())

        val args = ToolParameterParser.parseArguments(toolCall)
        val filePath = ToolParameterParser.getStringParameter(args, "file_path")
        val startLine = args["start_line"]?.toIntOrNull()
        val endLine = args["end_line"]?.toIntOrNull()

        if (filePath.isBlank()) {
            return ToolExecutionResult.Error(Strings.ai_tool_error_file_path_required.str())
        }

        // 验证行号范围
        if (startLine != null && endLine != null && startLine > endLine) {
            return ToolExecutionResult.Error(Strings.ai_tool_error_start_line_after_end_line.str(startLine, endLine))
        }

        return try {
            val result = if (startLine != null && endLine != null) {
                // 范围格式化
                callbacks.formatCodeRange(filePath, startLine, endLine)
            } else {
                // 全文件格式化
                callbacks.formatCode(filePath)
            }

            if (result) {
                val rangeInfo = if (startLine != null && endLine != null) {
                    " " + localizedToolText(
                        Strings.ai_tool_output_lines_range,
                        "(lines %1\$s-%2\$s)",
                        startLine,
                        endLine
                    )
                } else {
                    ""
                }

                val content = buildString {
                    appendLocalizedToolLine(Strings.ai_tool_output_code_formatted_success, "Code formatted successfully")
                    appendLocalizedToolLine(Strings.ai_tool_output_file, "File: %1\$s%2\$s", filePath, rangeInfo)
                    appendLine()
                    appendLocalizedToolLine(
                        Strings.ai_tool_output_code_formatted_clang,
                        "The code has been formatted using clang-format."
                    )
                    appendLocalizedToolLine(
                        Strings.ai_tool_output_changes_applied_editor,
                        "Changes have been applied to the editor."
                    )
                }

                ToolExecutionResult.Success(content)
            } else {
                ToolExecutionResult.Error(Strings.ai_tool_error_failed_format_code_unsupported.str())
            }
        } catch (e: Exception) {
            e.rethrowIfCancellation()
            ToolExecutionResult.Error(Strings.ai_tool_error_failed_format_code.str(e.message))
        }
    }
}

/**
 * 提取方法工具
 * 将选中的代码提取为独立方法
 */
object ExtractMethodTool : AiTool {
    override val name = "extract_method"
    override val description: String
        get() = localizedToolText(
            Strings.ai_tool_desc_extract_method,
            "Extract selected code into a new method/function. Automatically detects parameters and return type. Useful for refactoring long methods and improving code reusability. Requires method name and the code to extract."
        )
    override val category = ToolCategory.EDITOR

    override fun getParameters(): JsonElement = buildJsonObject {
        put("type", "object")
        put(
            "properties",
            buildJsonObject {
                put(
                    "method_name",
                    buildJsonObject {
                        put("type", "string")
                        put(
                            "description",
                            localizedToolText(
                                Strings.ai_tool_param_desc_name_for_the_new_method,
                                "Name for the new method"
                            )
                        )
                    }
                )
                put(
                    "code",
                    buildJsonObject {
                        put("type", "string")
                        put(
                            "description",
                            localizedToolText(
                                Strings.ai_tool_param_desc_code_to_extract_into_the_method,
                                "Code to extract into the method"
                            )
                        )
                    }
                )
                put(
                    "visibility",
                    buildJsonObject {
                        put("type", "string")
                        put(
                            "description",
                            localizedToolText(
                                Strings.ai_tool_param_desc_method_visibility_private_public_protected,
                                "Method visibility (private, public, protected)"
                            )
                        )
                        put(
                            "enum",
                            buildJsonArray {
                                add(JsonPrimitive("private"))
                                add(JsonPrimitive("public"))
                                add(JsonPrimitive("protected"))
                            }
                        )
                        put("default", "private")
                    }
                )
            }
        )
        put(
            "required",
            buildJsonArray {
                add(JsonPrimitive("method_name"))
                add(JsonPrimitive("code"))
            }
        )
    }

    override suspend fun execute(toolCall: ToolCall, context: ToolExecutionContext): ToolExecutionResult {
        val args = ToolParameterParser.parseArguments(toolCall)
        val methodName = ToolParameterParser.getStringParameter(args, "method_name")
        val code = ToolParameterParser.getStringParameter(args, "code")
        val visibility = ToolParameterParser.getStringParameter(args, "visibility", "private")

        if (methodName.isBlank() || code.isBlank()) {
            return ToolExecutionResult.Error(Strings.ai_tool_error_method_name_code_required.str())
        }

        return try {
            // Generate method signature
            val methodSignature = "$visibility fun $methodName() {\n"
            val methodBody = code.lines().joinToString("\n") { "    $it" }
            val methodEnd = "\n}"

            val extractedMethod = methodSignature + methodBody + methodEnd

            val metadata = mapOf(
                "methodName" to methodName,
                "visibility" to visibility,
                "linesExtracted" to code.lines().size
            )

            val content = buildString {
                appendLocalizedToolLine(Strings.ai_tool_output_extracted_method, "Extracted method:")
                appendLine()
                appendLine(extractedMethod)
                appendLine()
                appendLocalizedToolLine(Strings.ai_tool_output_replace_original_code, "Replace the original code with:")
                appendLine("    $methodName()")
            }

            ToolExecutionResult.Success(content, metadata)
        } catch (e: Exception) {
            e.rethrowIfCancellation()
            ToolExecutionResult.Error(Strings.ai_tool_error_failed_extract_method.str(e.message))
        }
    }
}

/**
 * 添加注释工具
 * 为代码添加文档注释
 */
object AddDocumentationTool : AiTool {
    override val name = "add_documentation"
    override val description: String
        get() = localizedToolText(
            Strings.ai_tool_desc_add_documentation,
            "Generate documentation comments for code elements (classes, functions, properties). Creates KDoc/JavaDoc format comments with parameter descriptions, return value, and usage examples. Improves code maintainability."
        )
    override val category = ToolCategory.EDITOR

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
                                Strings.ai_tool_param_desc_code_to_document,
                                "Code to document"
                            )
                        )
                    }
                )
                put(
                    "element_type",
                    buildJsonObject {
                        put("type", "string")
                        put(
                            "description",
                            localizedToolText(
                                Strings.ai_tool_param_desc_type_of_code_element_class_function_property,
                                "Type of code element (class, function, property)"
                            )
                        )
                        put(
                            "enum",
                            buildJsonArray {
                                add(JsonPrimitive("class"))
                                add(JsonPrimitive("function"))
                                add(JsonPrimitive("property"))
                            }
                        )
                    }
                )
                put(
                    "description",
                    buildJsonObject {
                        put("type", "string")
                        put(
                            "description",
                            localizedToolText(
                                Strings.ai_tool_param_desc_brief_description_of_what_the_code_does,
                                "Brief description of what the code does"
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
                add(JsonPrimitive("element_type"))
                add(JsonPrimitive("description"))
            }
        )
    }

    override suspend fun execute(toolCall: ToolCall, context: ToolExecutionContext): ToolExecutionResult {
        val args = ToolParameterParser.parseArguments(toolCall)
        val code = ToolParameterParser.getStringParameter(args, "code")
        val elementType = ToolParameterParser.getStringParameter(args, "element_type")
        val description = ToolParameterParser.getStringParameter(args, "description")

        if (code.isBlank() || description.isBlank()) {
            return ToolExecutionResult.Error(Strings.ai_tool_error_code_description_required.str())
        }

        return try {
            val documentation = when (elementType) {
                "class" -> generateClassDoc(description, code)
                "function" -> generateFunctionDoc(description, code)
                "property" -> generatePropertyDoc(description)
                else -> return ToolExecutionResult.Error(Strings.ai_tool_error_unsupported_element_type.str(elementType))
            }

            val metadata = mapOf(
                "elementType" to elementType
            )

            val content = buildString {
                appendLocalizedToolLine(Strings.ai_tool_output_generated_documentation, "Generated documentation:")
                appendLine()
                appendLine(documentation)
                appendLine()
                appendLocalizedToolLine(Strings.ai_tool_output_original_code, "Original code:")
                appendLine(code)
            }

            ToolExecutionResult.Success(content, metadata)
        } catch (e: Exception) {
            e.rethrowIfCancellation()
            ToolExecutionResult.Error(Strings.ai_tool_error_failed_add_documentation.str(e.message))
        }
    }

    private fun generateClassDoc(description: String, code: String): String = buildString {
        appendLine("/**")
        appendLine(" * $description")
        appendLine(" *")
        // Extract constructor parameters if any
        val paramRegex = """(\w+):\s*(\w+)""".toRegex()
        paramRegex.findAll(code).forEach { match ->
            val paramName = match.groupValues[1]
            val paramType = match.groupValues[2]
            val parameterDescription = localizedToolText(
                Strings.ai_tool_doc_property_description,
                "The %1\$s parameter",
                paramName
            )
            appendLine(" * @property $paramName $parameterDescription")
        }
        appendLine(" */")
    }

    private fun generateFunctionDoc(description: String, code: String): String = buildString {
        appendLine("/**")
        appendLine(" * $description")
        appendLine(" *")
        // Extract parameters
        val paramRegex = """(\w+):\s*(\w+)""".toRegex()
        paramRegex.findAll(code).forEach { match ->
            val paramName = match.groupValues[1]
            val parameterDescription = localizedToolText(
                Strings.ai_tool_doc_param_description,
                "Description of %1\$s",
                paramName
            )
            appendLine(" * @param $paramName $parameterDescription")
        }
        // Check for return type
        if (code.contains(":") && !code.contains("Unit")) {
            appendLine(
                " * @return " + localizedToolText(
                    Strings.ai_tool_doc_return_description,
                    "Description of return value"
                )
            )
        }
        appendLine(" */")
    }

    private fun generatePropertyDoc(description: String): String = buildString {
        appendLine("/**")
        appendLine(" * $description")
        appendLine(" */")
    }
}
