package com.wuxianggujun.tinaide.ai.integration

import android.content.Context
import com.wuxianggujun.tinaide.ai.tools.executor.filesystem.CopyFileRequest
import com.wuxianggujun.tinaide.ai.tools.executor.filesystem.CreateDirectoryRequest
import com.wuxianggujun.tinaide.ai.tools.executor.filesystem.DeleteFileRequest
import com.wuxianggujun.tinaide.ai.tools.executor.filesystem.DeleteLinesRequest
import com.wuxianggujun.tinaide.ai.tools.executor.filesystem.FileEntry
import com.wuxianggujun.tinaide.ai.tools.executor.filesystem.FileInfoResult
import com.wuxianggujun.tinaide.ai.tools.executor.filesystem.FileOperationResult
import com.wuxianggujun.tinaide.ai.tools.executor.filesystem.FileReadResult
import com.wuxianggujun.tinaide.ai.tools.executor.filesystem.FileSystemCallbacks
import com.wuxianggujun.tinaide.ai.tools.executor.filesystem.FileWriteRequest
import com.wuxianggujun.tinaide.ai.tools.executor.filesystem.FileWriteResult
import com.wuxianggujun.tinaide.ai.tools.executor.filesystem.InsertLineRequest
import com.wuxianggujun.tinaide.ai.tools.executor.filesystem.InsertPosition
import com.wuxianggujun.tinaide.ai.tools.executor.filesystem.ListFilesRequest
import com.wuxianggujun.tinaide.ai.tools.executor.filesystem.ListFilesResult
import com.wuxianggujun.tinaide.ai.tools.executor.filesystem.MoveFileRequest
import com.wuxianggujun.tinaide.ai.tools.executor.filesystem.ReplaceLineRequest
import com.wuxianggujun.tinaide.ai.tools.executor.filesystem.ReplaceTextRequest
import com.wuxianggujun.tinaide.file.IFileOperations
import com.wuxianggujun.tinaide.ui.compose.state.editor.EditorContainerState
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * 文件系统回调实现
 * 集成编辑器同步功能
 */
class FileSystemCallbacksImpl(
    private val context: Context,
    private val projectRoot: String,
    private val editorState: EditorContainerState,
    private val fileOperations: IFileOperations
) : FileSystemCallbacks {

    override fun readFile(path: String): FileReadResult {
        val file = resolvePath(path)

        if (!file.exists()) {
            throw IllegalArgumentException("File not found: $path")
        }
        if (!file.isFile) {
            throw IllegalArgumentException("Path is not a file: $path")
        }

        // 优先从打开的编辑器中读取内容
        val content = editorState.readTextFromOpenTabIfPresent(file) ?: file.readText()

        return FileReadResult(
            content = content,
            path = toRelativePath(file.absolutePath),
            size = content.length.toLong(),
            lines = content.lines().size
        )
    }

    override fun writeFile(request: FileWriteRequest): FileWriteResult {
        val file = resolvePath(request.path)

        if (request.createDirs) {
            file.parentFile?.mkdirs()
        }

        val existed = file.exists()

        if (editorState.updateOpenTabTextIfPresent(file, request.content)) {
            // 文件在编辑器中打开，直接修改编辑器内容
            // 返回结果，使用内容长度作为大小
            return FileWriteResult(
                path = toRelativePath(file.absolutePath),
                size = request.content.length.toLong(),
                created = !existed
            )
        } else {
            // 文件未在编辑器中打开，写入文件
            file.writeText(request.content)

            return FileWriteResult(
                path = toRelativePath(file.absolutePath),
                size = file.length(),
                created = !existed
            )
        }
    }

    override fun listFiles(request: ListFilesRequest): ListFilesResult {
        val dir = resolvePath(request.path)

        if (!dir.exists()) {
            throw IllegalArgumentException("Directory not found: ${request.path}")
        }
        if (!dir.isDirectory) {
            throw IllegalArgumentException("Path is not a directory: ${request.path}")
        }

        val files = if (request.recursive) {
            dir.walkTopDown()
                .maxDepth(request.maxDepth)
                .filter { it.isFile }
        } else {
            dir.listFiles()?.asSequence()?.filter { it.isFile } ?: emptySequence()
        }

        val filteredFiles = files
            .filter { request.includeHidden || !it.isHidden }
            .filter { file ->
                if (request.pattern.isNullOrBlank()) {
                    true
                } else {
                    val regex = request.pattern!!.replace("*", ".*").toRegex()
                    regex.matches(file.name)
                }
            }

        val fileEntries = filteredFiles.map { file ->
            FileEntry(
                name = file.name,
                path = toRelativePath(file.absolutePath),
                relativePath = file.relativeTo(dir).path,
                isDirectory = file.isDirectory,
                size = file.length(),
                lastModified = file.lastModified()
            )
        }.toList()

        return ListFilesResult(
            files = fileEntries,
            totalCount = fileEntries.size,
            path = toRelativePath(dir.absolutePath)
        )
    }

    override fun deleteFile(request: DeleteFileRequest): FileOperationResult {
        val file = resolvePath(request.path)

        if (!file.exists()) {
            return FileOperationResult(
                success = false,
                message = "File not found: ${toRelativePath(file.absolutePath)}",
                path = toRelativePath(file.absolutePath)
            )
        }

        if (file.isDirectory && !request.recursive) {
            return FileOperationResult(
                success = false,
                message = "Recursive flag required to delete directory: ${toRelativePath(file.absolutePath)}",
                path = toRelativePath(file.absolutePath)
            )
        }

        return try {
            val deleted = fileOperations.deleteFile(file, recursive = request.recursive)

            if (!deleted) {
                return FileOperationResult(
                    success = false,
                    message = "Failed to delete: ${toRelativePath(file.absolutePath)}",
                    path = toRelativePath(file.absolutePath)
                )
            }

            syncFileDeleted(file.absolutePath)
            FileOperationResult(
                success = true,
                message = "Deleted: ${toRelativePath(file.absolutePath)}",
                path = toRelativePath(file.absolutePath)
            )
        } catch (e: Exception) {
            FileOperationResult(
                success = false,
                message = "Failed to delete: ${e.message}",
                path = toRelativePath(file.absolutePath)
            )
        }
    }

    override fun createDirectory(request: CreateDirectoryRequest): FileOperationResult {
        val dir = resolvePath(request.path)

        val success = if (request.createParents) {
            dir.mkdirs()
        } else {
            dir.mkdir()
        }

        return if (success || dir.exists()) {
            FileOperationResult(
                success = true,
                message = "Directory created: ${toRelativePath(dir.absolutePath)}",
                path = toRelativePath(dir.absolutePath)
            )
        } else {
            FileOperationResult(
                success = false,
                message = "Failed to create directory",
                path = toRelativePath(dir.absolutePath)
            )
        }
    }

    override fun moveFile(request: MoveFileRequest): FileOperationResult {
        val sourceFile = resolvePath(request.source)
        val destFile = resolvePath(request.destination)

        if (!sourceFile.exists()) {
            return FileOperationResult(
                success = false,
                message = "Source file not found: ${request.source}"
            )
        }

        if (destFile.exists() && !request.overwrite) {
            return FileOperationResult(
                success = false,
                message = "Destination already exists: ${request.destination}"
            )
        }

        return try {
            val moved = fileOperations.moveFile(
                source = sourceFile,
                destination = destFile,
                overwrite = request.overwrite
            )

            if (!moved) {
                return FileOperationResult(
                    success = false,
                    message = "Failed to move: ${toRelativePath(sourceFile.absolutePath)} -> ${toRelativePath(destFile.absolutePath)}"
                )
            }

            syncFileMoved(sourceFile.absolutePath, destFile.absolutePath)

            FileOperationResult(
                success = true,
                message = "Moved: ${toRelativePath(sourceFile.absolutePath)} -> ${toRelativePath(destFile.absolutePath)}",
                path = toRelativePath(destFile.absolutePath)
            )
        } catch (e: Exception) {
            FileOperationResult(
                success = false,
                message = "Failed to move: ${e.message}"
            )
        }
    }

    override fun copyFile(request: CopyFileRequest): FileOperationResult {
        val sourceFile = resolvePath(request.source)
        val destFile = resolvePath(request.destination)

        if (!sourceFile.exists()) {
            return FileOperationResult(
                success = false,
                message = "Source file not found: ${request.source}"
            )
        }

        if (destFile.exists() && !request.overwrite) {
            return FileOperationResult(
                success = false,
                message = "Destination already exists: ${request.destination}"
            )
        }

        return try {
            if (sourceFile.isDirectory) {
                sourceFile.copyRecursively(destFile, request.overwrite)
            } else {
                val copyOption = if (request.overwrite) {
                    StandardCopyOption.REPLACE_EXISTING
                } else {
                    StandardCopyOption.COPY_ATTRIBUTES
                }
                Files.copy(sourceFile.toPath(), destFile.toPath(), copyOption)
            }

            FileOperationResult(
                success = true,
                message = "Copied: ${toRelativePath(sourceFile.absolutePath)} -> ${toRelativePath(destFile.absolutePath)}",
                path = toRelativePath(destFile.absolutePath)
            )
        } catch (e: Exception) {
            FileOperationResult(
                success = false,
                message = "Failed to copy: ${e.message}"
            )
        }
    }

    override fun getFileInfo(path: String): FileInfoResult {
        val file = resolvePath(path)

        if (!file.exists()) {
            throw IllegalArgumentException("File not found: $path")
        }

        return FileInfoResult(
            path = toRelativePath(file.absolutePath),
            name = file.name,
            isFile = file.isFile,
            isDirectory = file.isDirectory,
            size = file.length(),
            lastModified = file.lastModified(),
            canRead = file.canRead(),
            canWrite = file.canWrite(),
            canExecute = file.canExecute(),
            isHidden = file.isHidden
        )
    }

    override fun replaceText(request: ReplaceTextRequest): FileOperationResult {
        val file = resolvePath(request.path)

        if (!file.exists()) {
            return FileOperationResult(
                success = false,
                message = "File not found: ${request.path}",
                path = toRelativePath(file.absolutePath)
            )
        }

        return try {
            // 读取当前内容（优先从编辑器）
            val readResult = readFile(request.path)
            var content = readResult.content

            // 规范化行结束符：将所有 CRLF 转换为 LF
            content = content.replace("\r\n", "\n")

            // 规范化要替换的文本
            var oldText = request.oldText.replace("\r\n", "\n")
            var newText = request.newText.replace("\r\n", "\n")

            // 执行替换
            val newContent = if (request.replaceAll) {
                content.replace(oldText, newText)
            } else {
                content.replaceFirst(oldText, newText)
            }

            // 检查是否有变化
            if (content == newContent) {
                return FileOperationResult(
                    success = false,
                    message = "Text not found: ${request.oldText}",
                    path = toRelativePath(file.absolutePath)
                )
            }

            // 写回文件（会自动同步编辑器）
            writeFile(FileWriteRequest(request.path, newContent))

            val replacedCount = if (request.replaceAll) {
                (content.length - newContent.length + newText.length) / (oldText.length - newText.length)
            } else {
                1
            }

            FileOperationResult(
                success = true,
                message = "Replaced $replacedCount occurrence(s) in ${toRelativePath(file.absolutePath)}",
                path = toRelativePath(file.absolutePath)
            )
        } catch (e: Exception) {
            FileOperationResult(
                success = false,
                message = "Failed to replace text: ${e.message}",
                path = toRelativePath(file.absolutePath)
            )
        }
    }

    override fun replaceLine(request: ReplaceLineRequest): FileOperationResult {
        val file = resolvePath(request.path)

        if (!file.exists()) {
            return FileOperationResult(
                success = false,
                message = "File not found: ${request.path}",
                path = toRelativePath(file.absolutePath)
            )
        }

        return try {
            // 读取当前内容
            val readResult = readFile(request.path)
            val content = readResult.content

            // 检测原始行结束符
            val lineEnding = when {
                content.contains("\r\n") -> "\r\n"
                content.contains("\r") -> "\r"
                else -> "\n"
            }

            val lines = content.split(lineEnding).toMutableList()

            // 验证行号
            if (request.lineNumber < 1 || request.lineNumber > lines.size) {
                return FileOperationResult(
                    success = false,
                    message = "Invalid line number: ${request.lineNumber} (file has ${lines.size} lines)",
                    path = toRelativePath(file.absolutePath)
                )
            }

            // 替换行（行号从 1 开始）
            lines[request.lineNumber - 1] = request.newContent

            // 写回文件，保持原始行结束符
            val newContent = lines.joinToString(lineEnding)
            writeFile(FileWriteRequest(request.path, newContent))

            FileOperationResult(
                success = true,
                message = "Replaced line ${request.lineNumber} in ${toRelativePath(file.absolutePath)}",
                path = toRelativePath(file.absolutePath)
            )
        } catch (e: Exception) {
            FileOperationResult(
                success = false,
                message = "Failed to replace line: ${e.message}",
                path = toRelativePath(file.absolutePath)
            )
        }
    }

    override fun insertLine(request: InsertLineRequest): FileOperationResult {
        val file = resolvePath(request.path)

        if (!file.exists()) {
            return FileOperationResult(
                success = false,
                message = "File not found: ${request.path}",
                path = toRelativePath(file.absolutePath)
            )
        }

        return try {
            // 读取当前内容
            val readResult = readFile(request.path)
            val content = readResult.content

            // 检测原始行结束符
            val lineEnding = when {
                content.contains("\r\n") -> "\r\n"
                content.contains("\r") -> "\r"
                else -> "\n"
            }

            val lines = content.split(lineEnding).toMutableList()

            // 验证行号
            if (request.lineNumber < 1 || request.lineNumber > lines.size + 1) {
                return FileOperationResult(
                    success = false,
                    message = "Invalid line number: ${request.lineNumber} (file has ${lines.size} lines)",
                    path = toRelativePath(file.absolutePath)
                )
            }

            // 插入行
            val insertIndex = when (request.position) {
                InsertPosition.BEFORE -> request.lineNumber - 1
                InsertPosition.AFTER -> request.lineNumber
            }
            lines.add(insertIndex, request.content)

            // 写回文件，保持原始行结束符
            val newContent = lines.joinToString(lineEnding)
            writeFile(FileWriteRequest(request.path, newContent))

            FileOperationResult(
                success = true,
                message = "Inserted line ${request.position.name.lowercase()} line ${request.lineNumber} in ${toRelativePath(file.absolutePath)}",
                path = toRelativePath(file.absolutePath)
            )
        } catch (e: Exception) {
            FileOperationResult(
                success = false,
                message = "Failed to insert line: ${e.message}",
                path = toRelativePath(file.absolutePath)
            )
        }
    }

    override fun deleteLines(request: DeleteLinesRequest): FileOperationResult {
        val file = resolvePath(request.path)

        if (!file.exists()) {
            return FileOperationResult(
                success = false,
                message = "File not found: ${request.path}",
                path = toRelativePath(file.absolutePath)
            )
        }

        return try {
            // 读取当前内容
            val readResult = readFile(request.path)
            val content = readResult.content

            // 检测原始行结束符
            val lineEnding = when {
                content.contains("\r\n") -> "\r\n"
                content.contains("\r") -> "\r"
                else -> "\n"
            }

            val lines = content.split(lineEnding).toMutableList()

            // 验证行号
            if (request.startLine < 1 || request.startLine > lines.size) {
                return FileOperationResult(
                    success = false,
                    message = "Invalid start line: ${request.startLine} (file has ${lines.size} lines)",
                    path = toRelativePath(file.absolutePath)
                )
            }

            if (request.endLine < request.startLine || request.endLine > lines.size) {
                return FileOperationResult(
                    success = false,
                    message = "Invalid end line: ${request.endLine} (file has ${lines.size} lines)",
                    path = toRelativePath(file.absolutePath)
                )
            }

            // 删除行（行号从 1 开始）
            val deleteCount = request.endLine - request.startLine + 1
            repeat(deleteCount) {
                lines.removeAt(request.startLine - 1)
            }

            // 写回文件，保持原始行结束符
            val newContent = lines.joinToString(lineEnding)
            writeFile(FileWriteRequest(request.path, newContent))

            val lineRange = if (request.startLine == request.endLine) {
                "line ${request.startLine}"
            } else {
                "lines ${request.startLine}-${request.endLine}"
            }

            FileOperationResult(
                success = true,
                message = "Deleted $lineRange in ${toRelativePath(file.absolutePath)}",
                path = toRelativePath(file.absolutePath)
            )
        } catch (e: Exception) {
            FileOperationResult(
                success = false,
                message = "Failed to delete lines: ${e.message}",
                path = toRelativePath(file.absolutePath)
            )
        }
    }

    override fun resolvePath(path: String): File = PathUtils.resolveProjectFile(path, projectRoot)

    override fun toRelativePath(absolutePath: String): String = PathUtils.toRelativePath(absolutePath, projectRoot)

    private fun syncFileDeleted(absolutePath: String) {
        editorState.closeTabsForDeletedPath(File(absolutePath))
    }

    private fun syncFileMoved(oldAbsolutePath: String, newAbsolutePath: String) {
        editorState.syncTabsForMovedPath(
            oldPath = File(PathUtils.normalizeFilePath(oldAbsolutePath)),
            newPath = File(PathUtils.normalizeFilePath(newAbsolutePath))
        )
    }
}
