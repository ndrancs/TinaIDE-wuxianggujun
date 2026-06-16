package com.wuxianggujun.tinaide.core.common.io

import java.io.File

object ArchivePathSafety {
    fun sanitizeRelativePath(path: String, source: String = "archive entry"): String {
        require(path.indexOf('\u0000') < 0) { "Invalid $source path: $path" }
        require(!path.startsWith("/") && !path.startsWith("\\")) { "Invalid $source path: $path" }

        var normalized = path.replace('\\', '/')
        while (normalized.startsWith("./")) normalized = normalized.removePrefix("./")

        require(!DRIVE_PREFIX.containsMatchIn(normalized)) { "Invalid $source path: $path" }

        val segments = normalized
            .split('/')
            .filter { it.isNotEmpty() && it != "." }

        if (segments.isEmpty()) return ""
        require(segments.none { it == ".." }) { "Invalid $source path: $path" }

        return segments.joinToString("/")
    }

    fun resolveEntryFile(targetDir: File, entryName: String, source: String = "archive entry"): File {
        val safeName = sanitizeRelativePath(entryName, source)
        val root = targetDir.canonicalFile
        val outputFile = File(root, safeName)
        val checked = canonicalCandidate(outputFile)

        require(isSameOrChild(root, checked)) {
            "Archive entry escapes target directory: $entryName"
        }

        return outputFile
    }

    fun requireSymlinkTargetInsideTargetDir(
        targetDir: File,
        linkFile: File,
        linkTarget: String,
        source: String = "archive symlink target",
    ): String {
        require(linkTarget.isNotBlank()) { "Invalid $source path: $linkTarget" }
        require(linkTarget.indexOf('\u0000') < 0) { "Invalid $source path: $linkTarget" }

        val root = targetDir.canonicalFile
        val rawTarget = File(linkTarget)
        val targetFile = if (rawTarget.isAbsolute) {
            rawTarget
        } else {
            File(linkFile.parentFile ?: root, linkTarget)
        }
        val checked = canonicalCandidate(targetFile)

        require(isSameOrChild(root, checked)) {
            "Archive symlink target escapes target directory: $linkTarget"
        }

        return linkTarget
    }

    private fun canonicalCandidate(file: File): File {
        if (file.exists()) return file.canonicalFile

        val parent = file.parentFile
            ?: throw IllegalArgumentException("Invalid archive path: ${file.path}")
        return File(parent.canonicalFile, file.name)
    }

    private fun isSameOrChild(root: File, candidate: File): Boolean {
        val rootPath = root.path
        val candidatePath = candidate.path
        return candidatePath == rootPath || candidatePath.startsWith(rootPath + File.separator)
    }

    private val DRIVE_PREFIX = Regex("^[A-Za-z]:")
}
