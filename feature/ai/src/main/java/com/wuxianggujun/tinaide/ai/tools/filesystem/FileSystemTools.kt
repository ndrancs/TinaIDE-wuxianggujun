package com.wuxianggujun.tinaide.ai.tools.filesystem

import com.wuxianggujun.tinaide.ai.api.ToolCall
import com.wuxianggujun.tinaide.ai.tools.AiTool
import com.wuxianggujun.tinaide.ai.tools.ToolCategory
import com.wuxianggujun.tinaide.ai.tools.ToolExecutionContext
import com.wuxianggujun.tinaide.ai.tools.ToolExecutionResult
import com.wuxianggujun.tinaide.ai.tools.ToolParameterParser
import com.wuxianggujun.tinaide.ai.tools.appendLocalizedToolLine
import com.wuxianggujun.tinaide.ai.tools.executor.filesystem.*
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
 * 读取文件工具
 * 从文件系统读取文件内容，支持相对和绝对路径
 */
object ReadFileTool : AiTool {
    override val name = "read_file"
    override val description: String
        get() = localizedToolText(
            Strings.ai_tool_desc_read_file,
            "Read the complete content of a file from the file system. Supports both relative (to project root) and absolute paths. Returns file content, size, line count, and encoding. Large files are automatically truncated."
        )
    override val category = ToolCategory.FILE_SYSTEM

    override fun getParameters(): JsonElement = buildJsonObject {
        put("type", "object")
        put(
            "properties",
            buildJsonObject {
                put(
                    "path",
                    buildJsonObject {
                        put("type", "string")
                        put(
                            "description",
                            localizedToolText(
                                Strings.ai_tool_param_desc_the_file_path_to_read_relative_to_project,
                                "The file path to read (relative to project root or absolute)"
                            )
                        )
                    }
                )
            }
        )
        put(
            "required",
            buildJsonArray {
                add(JsonPrimitive("path"))
            }
        )
    }

    override suspend fun execute(toolCall: ToolCall, context: ToolExecutionContext): ToolExecutionResult {
        val callbacks = context.additionalData["fileSystemCallbacks"] as? FileSystemCallbacks
            ?: return ToolExecutionResult.Error(Strings.ai_tool_error_file_system_callbacks_unavailable.str())

        val args = ToolParameterParser.parseArguments(toolCall)
        val path = ToolParameterParser.getStringParameter(args, "path")

        if (path.isBlank()) {
            return ToolExecutionResult.Error(Strings.ai_tool_error_file_path_required.str())
        }

        return try {
            val result = callbacks.readFile(path)

            val metadata = mapOf(
                "path" to result.path,
                "size" to result.size,
                "lines" to result.lines,
                "encoding" to result.encoding
            )

            // 截断大文件内容
            val content = if (result.content.length > 100_000) {
                result.content.take(100_000) + "\n... (content truncated, file too large)"
            } else {
                result.content
            }

            ToolExecutionResult.Success(content, metadata)
        } catch (e: Exception) {
            e.rethrowIfCancellation()
            ToolExecutionResult.Error(Strings.ai_tool_error_failed_read_file.str(e.message))
        }
    }
}

/**
 * 写入文件工具
 * 创建新文件或覆盖现有文件内容
 */
object WriteFileTool : AiTool {
    override val name = "write_file"
    override val description: String
        get() = localizedToolText(
            Strings.ai_tool_desc_write_file,
            "Write content to a file, creating it if it doesn't exist or overwriting if it does. Automatically creates parent directories if needed. Use for generating new files or updating existing ones."
        )
    override val category = ToolCategory.FILE_SYSTEM
    override val isDangerous = true

    override fun getParameters(): JsonElement = buildJsonObject {
        put("type", "object")
        put(
            "properties",
            buildJsonObject {
                put(
                    "path",
                    buildJsonObject {
                        put("type", "string")
                        put(
                            "description",
                            localizedToolText(
                                Strings.ai_tool_param_desc_the_file_path_to_write_relative_to_project,
                                "The file path to write (relative to project root or absolute)"
                            )
                        )
                    }
                )
                put(
                    "content",
                    buildJsonObject {
                        put("type", "string")
                        put(
                            "description",
                            localizedToolText(
                                Strings.ai_tool_param_desc_the_content_to_write_to_the_file,
                                "The content to write to the file"
                            )
                        )
                    }
                )
                put(
                    "create_dirs",
                    buildJsonObject {
                        put("type", "boolean")
                        put(
                            "description",
                            localizedToolText(
                                Strings.ai_tool_param_desc_whether_to_create_parent_directories_if_they_don,
                                "Whether to create parent directories if they don't exist"
                            )
                        )
                        put("default", true)
                    }
                )
            }
        )
        put(
            "required",
            buildJsonArray {
                add(JsonPrimitive("path"))
                add(JsonPrimitive("content"))
            }
        )
    }

    override suspend fun execute(toolCall: ToolCall, context: ToolExecutionContext): ToolExecutionResult {
        val callbacks = context.additionalData["fileSystemCallbacks"] as? FileSystemCallbacks
            ?: return ToolExecutionResult.Error(Strings.ai_tool_error_file_system_callbacks_unavailable.str())

        val args = ToolParameterParser.parseArguments(toolCall)
        val path = ToolParameterParser.getStringParameter(args, "path")
        val content = ToolParameterParser.getStringParameter(args, "content")
        val createDirs = ToolParameterParser.getBooleanParameter(args, "create_dirs", true)

        if (path.isBlank()) {
            return ToolExecutionResult.Error(Strings.ai_tool_error_file_path_required.str())
        }

        val request = FileWriteRequest(
            path = path,
            content = content,
            createDirs = createDirs
        )

        return try {
            val result = callbacks.writeFile(request)

            val metadata = mapOf(
                "path" to result.path,
                "size" to result.size,
                "created" to result.created
            )

            val message = if (result.created) {
                "File created successfully: ${result.path}"
            } else {
                "File updated successfully: ${result.path}"
            }

            ToolExecutionResult.Success(message, metadata)
        } catch (e: Exception) {
            e.rethrowIfCancellation()
            ToolExecutionResult.Error(Strings.ai_tool_error_failed_write_file.str(e.message))
        }
    }
}

/**
 * 列出文件工具
 * 列出目录内容，支持递归遍历和模式过滤
 */
object ListFilesTool : AiTool {
    override val name = "list_files"
    override val description: String
        get() = localizedToolText(
            Strings.ai_tool_desc_list_files,
            "List files and directories in a path. Supports recursive traversal, file pattern filtering (*.kt, *.java), and hidden file inclusion. Returns file paths, types (file/directory), and sizes."
        )
    override val category = ToolCategory.FILE_SYSTEM

    override fun getParameters(): JsonElement = buildJsonObject {
        put("type", "object")
        put(
            "properties",
            buildJsonObject {
                put(
                    "path",
                    buildJsonObject {
                        put("type", "string")
                        put(
                            "description",
                            localizedToolText(
                                Strings.ai_tool_param_desc_the_directory_path_to_list_relative_to_project,
                                "The directory path to list (relative to project root or absolute)"
                            )
                        )
                        put("default", ".")
                    }
                )
                put(
                    "recursive",
                    buildJsonObject {
                        put("type", "boolean")
                        put(
                            "description",
                            localizedToolText(
                                Strings.ai_tool_param_desc_whether_to_list_files_recursively,
                                "Whether to list files recursively"
                            )
                        )
                        put("default", false)
                    }
                )
                put(
                    "pattern",
                    buildJsonObject {
                        put("type", "string")
                        put(
                            "description",
                            localizedToolText(
                                Strings.ai_tool_param_desc_file_name_pattern_to_filter_e_g_kt,
                                "File name pattern to filter (e.g., '*.kt', '*.java')"
                            )
                        )
                    }
                )
                put(
                    "include_hidden",
                    buildJsonObject {
                        put("type", "boolean")
                        put(
                            "description",
                            localizedToolText(
                                Strings.ai_tool_param_desc_whether_to_include_hidden_files,
                                "Whether to include hidden files"
                            )
                        )
                        put("default", false)
                    }
                )
            }
        )
        put("required", buildJsonArray {})
    }

    override suspend fun execute(toolCall: ToolCall, context: ToolExecutionContext): ToolExecutionResult {
        val callbacks = context.additionalData["fileSystemCallbacks"] as? FileSystemCallbacks
            ?: return ToolExecutionResult.Error(Strings.ai_tool_error_file_system_callbacks_unavailable.str())

        val args = ToolParameterParser.parseArguments(toolCall)
        val request = ListFilesRequest(
            path = ToolParameterParser.getStringParameter(args, "path", "."),
            recursive = ToolParameterParser.getBooleanParameter(args, "recursive", false),
            pattern = args["pattern"],
            includeHidden = ToolParameterParser.getBooleanParameter(args, "include_hidden", false)
        )

        return try {
            val result = callbacks.listFiles(request)

            val metadata = mapOf(
                "path" to result.path,
                "totalCount" to result.totalCount
            )

            val content = buildString {
                appendLocalizedToolLine(Strings.ai_tool_output_files_in, "Files in %1\$s:", result.path)
                appendLocalizedToolLine(Strings.ai_tool_output_total_files, "Total: %1\$s file(s)", result.totalCount)
                appendLine()

                result.files.forEach { file ->
                    val type = if (file.isDirectory) {
                        localizedToolText(Strings.ai_tool_output_directory_marker, "[DIR]")
                    } else {
                        localizedToolText(Strings.ai_tool_output_file_marker, "[FILE]")
                    }
                    val size = if (file.isDirectory) {
                        ""
                    } else {
                        " (" + localizedToolText(
                            Strings.ai_tool_output_file_size_bytes,
                            "%1\$s bytes",
                            file.size
                        ) + ")"
                    }
                    append("  ")
                    append(type)
                    append(" ")
                    append(file.relativePath)
                    appendLine(size)
                }
            }

            ToolExecutionResult.Success(content, metadata)
        } catch (e: Exception) {
            e.rethrowIfCancellation()
            ToolExecutionResult.Error(Strings.ai_tool_error_failed_list_files.str(e.message))
        }
    }
}

/**
 * 删除文件工具
 */
object DeleteFileTool : AiTool {
    override val name = "delete_file"
    override val description: String
        get() = localizedToolText(
            Strings.ai_tool_desc_delete_file,
            "Delete a file or directory from the file system"
        )
    override val category = ToolCategory.FILE_SYSTEM
    override val isDangerous = true

    override fun getDangerousConfirmation(toolCall: ToolCall): com.wuxianggujun.tinaide.ai.tools.DangerousToolConfirmation {
        val args = ToolParameterParser.parseArguments(toolCall)
        val path = ToolParameterParser.getStringParameter(args, "path")
        val recursive = ToolParameterParser.getBooleanParameter(args, "recursive", false)

        val severity = if (recursive) {
            com.wuxianggujun.tinaide.ai.tools.ConfirmationSeverity.CRITICAL
        } else {
            com.wuxianggujun.tinaide.ai.tools.ConfirmationSeverity.DANGER
        }

        val message = if (recursive) {
            Strings.ai_tool_delete_recursive_warning.str()
        } else {
            Strings.ai_tool_delete_file_warning.str()
        }
        val detail = buildString {
            append(Strings.ai_tool_delete_path_detail.str(path))
            if (recursive) {
                append("\n")
                append(Strings.ai_tool_delete_mode_recursive.str())
            }
        }

        return com.wuxianggujun.tinaide.ai.tools.DangerousToolConfirmation(
            title = Strings.ai_dangerous_tool_title.str(),
            message = message,
            details = detail,
            confirmButtonText = Strings.ai_tool_delete_confirm.str(),
            cancelButtonText = Strings.btn_cancel.str(),
            severity = severity
        )
    }

    override fun getParameters(): JsonElement = buildJsonObject {
        put("type", "object")
        put(
            "properties",
            buildJsonObject {
                put(
                    "path",
                    buildJsonObject {
                        put("type", "string")
                        put(
                            "description",
                            localizedToolText(
                                Strings.ai_tool_param_desc_the_file_or_directory_path_to_delete,
                                "The file or directory path to delete"
                            )
                        )
                    }
                )
                put(
                    "recursive",
                    buildJsonObject {
                        put("type", "boolean")
                        put(
                            "description",
                            localizedToolText(
                                Strings.ai_tool_param_desc_whether_to_delete_directories_recursively,
                                "Whether to delete directories recursively"
                            )
                        )
                        put("default", false)
                    }
                )
            }
        )
        put(
            "required",
            buildJsonArray {
                add(JsonPrimitive("path"))
            }
        )
    }

    override suspend fun execute(toolCall: ToolCall, context: ToolExecutionContext): ToolExecutionResult {
        val callbacks = context.additionalData["fileSystemCallbacks"] as? FileSystemCallbacks
            ?: return ToolExecutionResult.Error(Strings.ai_tool_error_file_system_callbacks_unavailable.str())

        val args = ToolParameterParser.parseArguments(toolCall)
        val path = ToolParameterParser.getStringParameter(args, "path")
        val recursive = ToolParameterParser.getBooleanParameter(args, "recursive", false)

        if (path.isBlank()) {
            return ToolExecutionResult.Error(Strings.ai_tool_error_file_path_required.str())
        }

        val request = DeleteFileRequest(
            path = path,
            recursive = recursive
        )

        return try {
            val result = callbacks.deleteFile(request)

            if (result.success) {
                val metadata = mapOf("path" to path)
                ToolExecutionResult.Success(result.message, metadata)
            } else {
                ToolExecutionResult.Error(result.message)
            }
        } catch (e: Exception) {
            e.rethrowIfCancellation()
            ToolExecutionResult.Error(Strings.ai_tool_error_failed_delete.str(e.message))
        }
    }
}

/**
 * 创建目录工具
 */
object CreateDirectoryTool : AiTool {
    override val name = "create_directory"
    override val description: String
        get() = localizedToolText(
            Strings.ai_tool_desc_create_directory,
            "Create a new directory in the file system"
        )
    override val category = ToolCategory.FILE_SYSTEM
    override val isDangerous = true

    override fun getParameters(): JsonElement = buildJsonObject {
        put("type", "object")
        put(
            "properties",
            buildJsonObject {
                put(
                    "path",
                    buildJsonObject {
                        put("type", "string")
                        put(
                            "description",
                            localizedToolText(
                                Strings.ai_tool_param_desc_the_directory_path_to_create,
                                "The directory path to create"
                            )
                        )
                    }
                )
                put(
                    "create_parents",
                    buildJsonObject {
                        put("type", "boolean")
                        put(
                            "description",
                            localizedToolText(
                                Strings.ai_tool_param_desc_whether_to_create_parent_directories_if_they_don,
                                "Whether to create parent directories if they don't exist"
                            )
                        )
                        put("default", true)
                    }
                )
            }
        )
        put(
            "required",
            buildJsonArray {
                add(JsonPrimitive("path"))
            }
        )
    }

    override suspend fun execute(toolCall: ToolCall, context: ToolExecutionContext): ToolExecutionResult {
        val callbacks = context.additionalData["fileSystemCallbacks"] as? FileSystemCallbacks
            ?: return ToolExecutionResult.Error(Strings.ai_tool_error_file_system_callbacks_unavailable.str())

        val args = ToolParameterParser.parseArguments(toolCall)
        val path = ToolParameterParser.getStringParameter(args, "path")
        val createParents = ToolParameterParser.getBooleanParameter(args, "create_parents", true)

        if (path.isBlank()) {
            return ToolExecutionResult.Error(Strings.ai_tool_error_directory_path_required.str())
        }

        val request = CreateDirectoryRequest(
            path = path,
            createParents = createParents
        )

        return try {
            val result = callbacks.createDirectory(request)
            if (result.success) {
                ToolExecutionResult.Success(result.message)
            } else {
                ToolExecutionResult.Error(result.message)
            }
        } catch (e: Exception) {
            e.rethrowIfCancellation()
            ToolExecutionResult.Error(Strings.ai_tool_error_failed_create_directory.str(e.message))
        }
    }
}

/**
 * 移动/重命名文件工具
 */
object MoveFileTool : AiTool {
    override val name = "move_file"
    override val description: String
        get() = localizedToolText(
            Strings.ai_tool_desc_move_file,
            "Move or rename a file or directory"
        )
    override val category = ToolCategory.FILE_SYSTEM
    override val isDangerous = true

    override fun getParameters(): JsonElement = buildJsonObject {
        put("type", "object")
        put(
            "properties",
            buildJsonObject {
                put(
                    "source",
                    buildJsonObject {
                        put("type", "string")
                        put(
                            "description",
                            localizedToolText(
                                Strings.ai_tool_param_desc_the_source_file_or_directory_path,
                                "The source file or directory path"
                            )
                        )
                    }
                )
                put(
                    "destination",
                    buildJsonObject {
                        put("type", "string")
                        put(
                            "description",
                            localizedToolText(
                                Strings.ai_tool_param_desc_the_destination_file_or_directory_path,
                                "The destination file or directory path"
                            )
                        )
                    }
                )
                put(
                    "overwrite",
                    buildJsonObject {
                        put("type", "boolean")
                        put(
                            "description",
                            localizedToolText(
                                Strings.ai_tool_param_desc_whether_to_overwrite_if_destination_exists,
                                "Whether to overwrite if destination exists"
                            )
                        )
                        put("default", false)
                    }
                )
            }
        )
        put(
            "required",
            buildJsonArray {
                add(JsonPrimitive("source"))
                add(JsonPrimitive("destination"))
            }
        )
    }

    override suspend fun execute(toolCall: ToolCall, context: ToolExecutionContext): ToolExecutionResult {
        val callbacks = context.additionalData["fileSystemCallbacks"] as? FileSystemCallbacks
            ?: return ToolExecutionResult.Error(Strings.ai_tool_error_file_system_callbacks_unavailable.str())

        val args = ToolParameterParser.parseArguments(toolCall)
        val source = ToolParameterParser.getStringParameter(args, "source")
        val destination = ToolParameterParser.getStringParameter(args, "destination")
        val overwrite = ToolParameterParser.getBooleanParameter(args, "overwrite", false)

        if (source.isBlank() || destination.isBlank()) {
            return ToolExecutionResult.Error(Strings.ai_tool_error_source_destination_required.str())
        }

        val request = MoveFileRequest(
            source = source,
            destination = destination,
            overwrite = overwrite
        )

        return try {
            val result = callbacks.moveFile(request)

            if (result.success) {
                val metadata = mapOf(
                    "source" to source,
                    "destination" to destination
                )
                ToolExecutionResult.Success(result.message, metadata)
            } else {
                ToolExecutionResult.Error(result.message)
            }
        } catch (e: Exception) {
            e.rethrowIfCancellation()
            ToolExecutionResult.Error(Strings.ai_tool_error_failed_move_file.str(e.message))
        }
    }
}

/**
 * 复制文件工具
 */
object CopyFileTool : AiTool {
    override val name = "copy_file"
    override val description: String
        get() = localizedToolText(
            Strings.ai_tool_desc_copy_file,
            "Copy a file or directory to a new location"
        )
    override val category = ToolCategory.FILE_SYSTEM
    override val isDangerous = true

    override fun getParameters(): JsonElement = buildJsonObject {
        put("type", "object")
        put(
            "properties",
            buildJsonObject {
                put(
                    "source",
                    buildJsonObject {
                        put("type", "string")
                        put(
                            "description",
                            localizedToolText(
                                Strings.ai_tool_param_desc_the_source_file_or_directory_path,
                                "The source file or directory path"
                            )
                        )
                    }
                )
                put(
                    "destination",
                    buildJsonObject {
                        put("type", "string")
                        put(
                            "description",
                            localizedToolText(
                                Strings.ai_tool_param_desc_the_destination_file_or_directory_path,
                                "The destination file or directory path"
                            )
                        )
                    }
                )
                put(
                    "overwrite",
                    buildJsonObject {
                        put("type", "boolean")
                        put(
                            "description",
                            localizedToolText(
                                Strings.ai_tool_param_desc_whether_to_overwrite_if_destination_exists,
                                "Whether to overwrite if destination exists"
                            )
                        )
                        put("default", false)
                    }
                )
            }
        )
        put(
            "required",
            buildJsonArray {
                add(JsonPrimitive("source"))
                add(JsonPrimitive("destination"))
            }
        )
    }

    override suspend fun execute(toolCall: ToolCall, context: ToolExecutionContext): ToolExecutionResult {
        val callbacks = context.additionalData["fileSystemCallbacks"] as? FileSystemCallbacks
            ?: return ToolExecutionResult.Error(Strings.ai_tool_error_file_system_callbacks_unavailable.str())

        val args = ToolParameterParser.parseArguments(toolCall)
        val source = ToolParameterParser.getStringParameter(args, "source")
        val destination = ToolParameterParser.getStringParameter(args, "destination")
        val overwrite = ToolParameterParser.getBooleanParameter(args, "overwrite", false)

        if (source.isBlank() || destination.isBlank()) {
            return ToolExecutionResult.Error(Strings.ai_tool_error_source_destination_required.str())
        }

        val request = CopyFileRequest(
            source = source,
            destination = destination,
            overwrite = overwrite
        )

        return try {
            val result = callbacks.copyFile(request)

            if (result.success) {
                val metadata = mapOf(
                    "source" to source,
                    "destination" to destination
                )
                ToolExecutionResult.Success(result.message, metadata)
            } else {
                ToolExecutionResult.Error(result.message)
            }
        } catch (e: Exception) {
            e.rethrowIfCancellation()
            ToolExecutionResult.Error(Strings.ai_tool_error_failed_copy_file.str(e.message))
        }
    }
}

/**
 * 获取文件信息工具
 */
object GetFileInfoTool : AiTool {
    override val name = "get_file_info"
    override val description: String
        get() = localizedToolText(
            Strings.ai_tool_desc_get_file_info,
            "Get information about a file or directory (size, modified time, permissions, etc.)"
        )
    override val category = ToolCategory.FILE_SYSTEM

    override fun getParameters(): JsonElement = buildJsonObject {
        put("type", "object")
        put(
            "properties",
            buildJsonObject {
                put(
                    "path",
                    buildJsonObject {
                        put("type", "string")
                        put(
                            "description",
                            localizedToolText(
                                Strings.ai_tool_param_desc_the_file_or_directory_path,
                                "The file or directory path"
                            )
                        )
                    }
                )
            }
        )
        put(
            "required",
            buildJsonArray {
                add(JsonPrimitive("path"))
            }
        )
    }

    override suspend fun execute(toolCall: ToolCall, context: ToolExecutionContext): ToolExecutionResult {
        val callbacks = context.additionalData["fileSystemCallbacks"] as? FileSystemCallbacks
            ?: return ToolExecutionResult.Error(Strings.ai_tool_error_file_system_callbacks_unavailable.str())

        val args = ToolParameterParser.parseArguments(toolCall)
        val path = ToolParameterParser.getStringParameter(args, "path")

        if (path.isBlank()) {
            return ToolExecutionResult.Error(Strings.ai_tool_error_file_path_required.str())
        }

        return try {
            val result = callbacks.getFileInfo(path)

            val content = buildJsonObject {
                put("path", result.path)
                put("name", result.name)
                put("isFile", result.isFile)
                put("isDirectory", result.isDirectory)
                put("size", result.size)
                put("lastModified", result.lastModified)
                put("canRead", result.canRead)
                put("canWrite", result.canWrite)
                put("canExecute", result.canExecute)
                put("isHidden", result.isHidden)
            }.toString()

            ToolExecutionResult.Success(content)
        } catch (e: Exception) {
            e.rethrowIfCancellation()
            ToolExecutionResult.Error(Strings.ai_tool_error_failed_get_file_info.str(e.message))
        }
    }
}

/**
 * 替换文本工具
 */
object ReplaceTextTool : AiTool {
    override val name = "replace_text"
    override val description: String
        get() = localizedToolText(
            Strings.ai_tool_desc_replace_text,
            "Find and replace text in a file. Supports replacing first occurrence or all occurrences. Automatically syncs with open editor tabs."
        )
    override val category = ToolCategory.FILE_SYSTEM
    override val isDangerous = true

    override fun getParameters(): JsonElement = buildJsonObject {
        put("type", "object")
        put(
            "properties",
            buildJsonObject {
                put(
                    "path",
                    buildJsonObject {
                        put("type", "string")
                        put(
                            "description",
                            localizedToolText(
                                Strings.ai_tool_param_desc_the_file_path_relative_to_project_root_or,
                                "The file path (relative to project root or absolute)"
                            )
                        )
                    }
                )
                put(
                    "old_text",
                    buildJsonObject {
                        put("type", "string")
                        put(
                            "description",
                            localizedToolText(
                                Strings.ai_tool_param_desc_the_text_to_find_and_replace,
                                "The text to find and replace"
                            )
                        )
                    }
                )
                put(
                    "new_text",
                    buildJsonObject {
                        put("type", "string")
                        put(
                            "description",
                            localizedToolText(
                                Strings.ai_tool_param_desc_the_replacement_text,
                                "The replacement text"
                            )
                        )
                    }
                )
                put(
                    "replace_all",
                    buildJsonObject {
                        put("type", "boolean")
                        put(
                            "description",
                            localizedToolText(
                                Strings.ai_tool_param_desc_whether_to_replace_all_occurrences_default_false_replaces,
                                "Whether to replace all occurrences (default: false, replaces only first)"
                            )
                        )
                        put("default", false)
                    }
                )
            }
        )
        put(
            "required",
            buildJsonArray {
                add(JsonPrimitive("path"))
                add(JsonPrimitive("old_text"))
                add(JsonPrimitive("new_text"))
            }
        )
    }

    override suspend fun execute(toolCall: ToolCall, context: ToolExecutionContext): ToolExecutionResult {
        val callbacks = context.additionalData["fileSystemCallbacks"] as? FileSystemCallbacks
            ?: return ToolExecutionResult.Error(Strings.ai_tool_error_file_system_callbacks_unavailable.str())

        val args = ToolParameterParser.parseArguments(toolCall)
        val path = ToolParameterParser.getStringParameter(args, "path")
        val oldText = ToolParameterParser.getStringParameter(args, "old_text")
        val newText = ToolParameterParser.getStringParameter(args, "new_text")
        val replaceAll = ToolParameterParser.getBooleanParameter(args, "replace_all", false)

        if (path.isBlank()) {
            return ToolExecutionResult.Error(Strings.ai_tool_error_file_path_required.str())
        }

        if (oldText.isBlank()) {
            return ToolExecutionResult.Error(Strings.ai_tool_error_old_text_required.str())
        }

        val request = ReplaceTextRequest(
            path = path,
            oldText = oldText,
            newText = newText,
            replaceAll = replaceAll
        )

        return try {
            val result = callbacks.replaceText(request)

            if (result.success) {
                val metadata = mapOf("path" to path)
                ToolExecutionResult.Success(result.message, metadata)
            } else {
                ToolExecutionResult.Error(result.message)
            }
        } catch (e: Exception) {
            e.rethrowIfCancellation()
            ToolExecutionResult.Error(Strings.ai_tool_error_failed_replace_text.str(e.message))
        }
    }
}

/**
 * 替换行工具
 */
object ReplaceLineTool : AiTool {
    override val name = "replace_line"
    override val description: String
        get() = localizedToolText(
            Strings.ai_tool_desc_replace_line,
            "Replace the content of a specific line in a file. Line numbers start from 1. Automatically syncs with open editor tabs."
        )
    override val category = ToolCategory.FILE_SYSTEM
    override val isDangerous = true

    override fun getParameters(): JsonElement = buildJsonObject {
        put("type", "object")
        put(
            "properties",
            buildJsonObject {
                put(
                    "path",
                    buildJsonObject {
                        put("type", "string")
                        put(
                            "description",
                            localizedToolText(
                                Strings.ai_tool_param_desc_the_file_path_relative_to_project_root_or,
                                "The file path (relative to project root or absolute)"
                            )
                        )
                    }
                )
                put(
                    "line_number",
                    buildJsonObject {
                        put("type", "integer")
                        put(
                            "description",
                            localizedToolText(
                                Strings.ai_tool_param_desc_the_line_number_to_replace_starts_from_1,
                                "The line number to replace (starts from 1)"
                            )
                        )
                        put("minimum", 1)
                    }
                )
                put(
                    "new_content",
                    buildJsonObject {
                        put("type", "string")
                        put(
                            "description",
                            localizedToolText(
                                Strings.ai_tool_param_desc_the_new_content_for_the_line,
                                "The new content for the line"
                            )
                        )
                    }
                )
            }
        )
        put(
            "required",
            buildJsonArray {
                add(JsonPrimitive("path"))
                add(JsonPrimitive("line_number"))
                add(JsonPrimitive("new_content"))
            }
        )
    }

    override suspend fun execute(toolCall: ToolCall, context: ToolExecutionContext): ToolExecutionResult {
        val callbacks = context.additionalData["fileSystemCallbacks"] as? FileSystemCallbacks
            ?: return ToolExecutionResult.Error(Strings.ai_tool_error_file_system_callbacks_unavailable.str())

        val args = ToolParameterParser.parseArguments(toolCall)
        val path = ToolParameterParser.getStringParameter(args, "path")
        val lineNumber = ToolParameterParser.getIntParameter(args, "line_number", 0)
        val newContent = ToolParameterParser.getStringParameter(args, "new_content")

        if (path.isBlank()) {
            return ToolExecutionResult.Error(Strings.ai_tool_error_file_path_required.str())
        }

        if (lineNumber < 1) {
            return ToolExecutionResult.Error(Strings.ai_tool_error_line_number_min_one.str())
        }

        val request = ReplaceLineRequest(
            path = path,
            lineNumber = lineNumber,
            newContent = newContent
        )

        return try {
            val result = callbacks.replaceLine(request)

            if (result.success) {
                val metadata = mapOf("path" to path, "lineNumber" to lineNumber)
                ToolExecutionResult.Success(result.message, metadata)
            } else {
                ToolExecutionResult.Error(result.message)
            }
        } catch (e: Exception) {
            e.rethrowIfCancellation()
            ToolExecutionResult.Error(Strings.ai_tool_error_failed_replace_line.str(e.message))
        }
    }
}

/**
 * 插入行工具
 */
object InsertLineTool : AiTool {
    override val name = "insert_line"
    override val description: String
        get() = localizedToolText(
            Strings.ai_tool_desc_insert_line,
            "Insert a new line before or after a specific line number in a file. Line numbers start from 1. Automatically syncs with open editor tabs."
        )
    override val category = ToolCategory.FILE_SYSTEM
    override val isDangerous = true

    override fun getParameters(): JsonElement = buildJsonObject {
        put("type", "object")
        put(
            "properties",
            buildJsonObject {
                put(
                    "path",
                    buildJsonObject {
                        put("type", "string")
                        put(
                            "description",
                            localizedToolText(
                                Strings.ai_tool_param_desc_the_file_path_relative_to_project_root_or,
                                "The file path (relative to project root or absolute)"
                            )
                        )
                    }
                )
                put(
                    "line_number",
                    buildJsonObject {
                        put("type", "integer")
                        put(
                            "description",
                            localizedToolText(
                                Strings.ai_tool_param_desc_the_reference_line_number_starts_from_1,
                                "The reference line number (starts from 1)"
                            )
                        )
                        put("minimum", 1)
                    }
                )
                put(
                    "content",
                    buildJsonObject {
                        put("type", "string")
                        put(
                            "description",
                            localizedToolText(
                                Strings.ai_tool_param_desc_the_content_to_insert,
                                "The content to insert"
                            )
                        )
                    }
                )
                put(
                    "position",
                    buildJsonObject {
                        put("type", "string")
                        put(
                            "description",
                            localizedToolText(
                                Strings.ai_tool_param_desc_insert_position_before_or_after_the_line_default,
                                "Insert position: 'before' or 'after' the line (default: 'after')"
                            )
                        )
                        put(
                            "enum",
                            buildJsonArray {
                                add(JsonPrimitive("before"))
                                add(JsonPrimitive("after"))
                            }
                        )
                        put("default", "after")
                    }
                )
            }
        )
        put(
            "required",
            buildJsonArray {
                add(JsonPrimitive("path"))
                add(JsonPrimitive("line_number"))
                add(JsonPrimitive("content"))
            }
        )
    }

    override suspend fun execute(toolCall: ToolCall, context: ToolExecutionContext): ToolExecutionResult {
        val callbacks = context.additionalData["fileSystemCallbacks"] as? FileSystemCallbacks
            ?: return ToolExecutionResult.Error(Strings.ai_tool_error_file_system_callbacks_unavailable.str())

        val args = ToolParameterParser.parseArguments(toolCall)
        val path = ToolParameterParser.getStringParameter(args, "path")
        val lineNumber = ToolParameterParser.getIntParameter(args, "line_number", 0)
        val content = ToolParameterParser.getStringParameter(args, "content")
        val positionStr = ToolParameterParser.getStringParameter(args, "position", "after")

        if (path.isBlank()) {
            return ToolExecutionResult.Error(Strings.ai_tool_error_file_path_required.str())
        }

        if (lineNumber < 1) {
            return ToolExecutionResult.Error(Strings.ai_tool_error_line_number_min_one.str())
        }

        val position = when (positionStr.lowercase()) {
            "before" -> InsertPosition.BEFORE
            "after" -> InsertPosition.AFTER
            else -> InsertPosition.AFTER
        }

        val request = InsertLineRequest(
            path = path,
            lineNumber = lineNumber,
            content = content,
            position = position
        )

        return try {
            val result = callbacks.insertLine(request)

            if (result.success) {
                val metadata = mapOf("path" to path, "lineNumber" to lineNumber, "position" to positionStr)
                ToolExecutionResult.Success(result.message, metadata)
            } else {
                ToolExecutionResult.Error(result.message)
            }
        } catch (e: Exception) {
            e.rethrowIfCancellation()
            ToolExecutionResult.Error(Strings.ai_tool_error_failed_insert_line.str(e.message))
        }
    }
}

/**
 * 删除行工具
 */
object DeleteLinesTool : AiTool {
    override val name = "delete_lines"
    override val description: String
        get() = localizedToolText(
            Strings.ai_tool_desc_delete_lines,
            "Delete one or more lines from a file. Can delete a single line or a range of lines. Line numbers start from 1. Automatically syncs with open editor tabs."
        )
    override val category = ToolCategory.FILE_SYSTEM
    override val isDangerous = true

    override fun getDangerousConfirmation(toolCall: ToolCall): com.wuxianggujun.tinaide.ai.tools.DangerousToolConfirmation {
        val args = ToolParameterParser.parseArguments(toolCall)
        val path = ToolParameterParser.getStringParameter(args, "path")
        val startLine = ToolParameterParser.getIntParameter(args, "start_line", 0)
        val endLine = ToolParameterParser.getIntParameter(args, "end_line", startLine)

        val lineRange = if (startLine == endLine) {
            "line $startLine"
        } else {
            "lines $startLine-$endLine"
        }

        return com.wuxianggujun.tinaide.ai.tools.DangerousToolConfirmation(
            title = Strings.ai_tool_delete_lines_title.str(),
            message = Strings.ai_tool_delete_lines_message.str(),
            details = Strings.ai_tool_delete_lines_detail.str(path, lineRange),
            confirmButtonText = Strings.ai_tool_delete_confirm.str(),
            cancelButtonText = Strings.btn_cancel.str(),
            severity = com.wuxianggujun.tinaide.ai.tools.ConfirmationSeverity.DANGER
        )
    }

    override fun getParameters(): JsonElement = buildJsonObject {
        put("type", "object")
        put(
            "properties",
            buildJsonObject {
                put(
                    "path",
                    buildJsonObject {
                        put("type", "string")
                        put(
                            "description",
                            localizedToolText(
                                Strings.ai_tool_param_desc_the_file_path_relative_to_project_root_or,
                                "The file path (relative to project root or absolute)"
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
                                Strings.ai_tool_param_desc_the_starting_line_number_to_delete_starts_from,
                                "The starting line number to delete (starts from 1)"
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
                                Strings.ai_tool_param_desc_the_ending_line_number_to_delete_default_same,
                                "The ending line number to delete (default: same as start_line)"
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
                add(JsonPrimitive("path"))
                add(JsonPrimitive("start_line"))
            }
        )
    }

    override suspend fun execute(toolCall: ToolCall, context: ToolExecutionContext): ToolExecutionResult {
        val callbacks = context.additionalData["fileSystemCallbacks"] as? FileSystemCallbacks
            ?: return ToolExecutionResult.Error(Strings.ai_tool_error_file_system_callbacks_unavailable.str())

        val args = ToolParameterParser.parseArguments(toolCall)
        val path = ToolParameterParser.getStringParameter(args, "path")
        val startLine = ToolParameterParser.getIntParameter(args, "start_line", 0)
        val endLine = ToolParameterParser.getIntParameter(args, "end_line", startLine)

        if (path.isBlank()) {
            return ToolExecutionResult.Error(Strings.ai_tool_error_file_path_required.str())
        }

        if (startLine < 1) {
            return ToolExecutionResult.Error(Strings.ai_tool_error_start_line_min_one.str())
        }

        if (endLine < startLine) {
            return ToolExecutionResult.Error(Strings.ai_tool_error_end_line_before_start.str())
        }

        val request = DeleteLinesRequest(
            path = path,
            startLine = startLine,
            endLine = endLine
        )

        return try {
            val result = callbacks.deleteLines(request)

            if (result.success) {
                val metadata = mapOf("path" to path, "startLine" to startLine, "endLine" to endLine)
                ToolExecutionResult.Success(result.message, metadata)
            } else {
                ToolExecutionResult.Error(result.message)
            }
        } catch (e: Exception) {
            e.rethrowIfCancellation()
            ToolExecutionResult.Error(Strings.ai_tool_error_failed_delete_lines.str(e.message))
        }
    }
}
