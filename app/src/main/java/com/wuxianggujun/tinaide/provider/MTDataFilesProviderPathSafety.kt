package com.wuxianggujun.tinaide.provider

import java.io.File
import java.io.FileNotFoundException

internal data class MTDocumentIdParts(
    val type: String?,
    val subPath: String,
)

internal object MTDataFilesProviderPathSafety {
    @Throws(FileNotFoundException::class)
    fun parseDocumentId(packageName: String, docId: String): MTDocumentIdParts {
        if (docId != packageName && !docId.startsWith("$packageName/")) {
            throw FileNotFoundException("$docId not found")
        }

        val relative = docId.removePrefix(packageName).removePrefix("/")
        if (relative.isEmpty()) {
            return MTDocumentIdParts(type = null, subPath = "")
        }

        val separatorIndex = relative.indexOf('/')
        val type = if (separatorIndex == -1) {
            relative
        } else {
            relative.substring(0, separatorIndex)
        }
        val subPath = if (separatorIndex == -1) {
            ""
        } else {
            relative.substring(separatorIndex + 1)
        }
        return MTDocumentIdParts(type = type, subPath = subPath)
    }

    @Throws(FileNotFoundException::class)
    fun sanitizeDocumentSubPath(subPath: String, docId: String): String {
        if (subPath.isEmpty()) return ""
        if (subPath.indexOf('\u0000') >= 0 || subPath.startsWith("/") || subPath.contains('\\')) {
            throw FileNotFoundException("$docId not found")
        }

        val segments = subPath.split('/')
        if (segments.any { it.isEmpty() || it == "." || it == ".." }) {
            throw FileNotFoundException("$docId not found")
        }
        return segments.joinToString("/")
    }

    @Throws(FileNotFoundException::class)
    fun sanitizeDisplayName(displayName: String): String {
        if (
            displayName.isBlank() ||
            displayName == "." ||
            displayName == ".." ||
            displayName.indexOf('\u0000') >= 0 ||
            displayName.contains('/') ||
            displayName.contains('\\')
        ) {
            throw FileNotFoundException("Invalid display name: $displayName")
        }
        return displayName
    }

    @Throws(FileNotFoundException::class)
    fun getParentDocumentId(packageName: String, documentId: String): String {
        val lastSlash = documentId.lastIndexOf('/')
        if (lastSlash <= packageName.length) {
            throw FileNotFoundException("Root document cannot be renamed: $documentId")
        }
        return documentId.substring(0, lastSlash)
    }

    fun appendDocumentId(parentDocumentId: String, displayName: String): String = if (parentDocumentId.endsWith("/")) {
        parentDocumentId + displayName
    } else {
        "$parentDocumentId/$displayName"
    }

    @Throws(FileNotFoundException::class)
    fun requireFileInsideRoot(
        root: File,
        file: File,
        docId: String,
        existsWithoutFollowing: (File) -> Boolean,
        isSymbolicLink: (File) -> Boolean,
        readLink: (File) -> String,
    ) {
        val rootCanonical = root.canonicalFile
        val checked = runCatching {
            if (existsWithoutFollowing(file) && isSymbolicLink(file)) {
                resolveSymlinkTargetInsideRoot(rootCanonical, file, readLink(file), docId)
            } else {
                canonicalCandidate(file)
            }
        }.getOrElse {
            throw FileNotFoundException("$docId not found")
        }

        if (!isSameOrChild(rootCanonical, checked)) {
            throw FileNotFoundException("$docId not found")
        }
    }

    @Throws(FileNotFoundException::class)
    fun requireSymlinkTargetInsideRoot(root: File, linkFile: File, linkTarget: String, docId: String): String {
        val rootCanonical = root.canonicalFile
        resolveSymlinkTargetInsideRoot(rootCanonical, linkFile, linkTarget, docId)
        return linkTarget
    }

    fun canonicalCandidate(file: File): File {
        if (file.exists()) return file.canonicalFile
        val parent = file.parentFile ?: return file.canonicalFile
        return File(parent.canonicalFile, file.name)
    }

    fun isSameOrChild(root: File, candidate: File): Boolean {
        val rootPath = root.path
        val candidatePath = candidate.path
        return candidatePath == rootPath || candidatePath.startsWith(rootPath + File.separator)
    }

    @Throws(FileNotFoundException::class)
    private fun resolveSymlinkTargetInsideRoot(
        rootCanonical: File,
        linkFile: File,
        linkTarget: String,
        docId: String,
    ): File {
        if (linkTarget.isBlank() || linkTarget.indexOf('\u0000') >= 0) {
            throw FileNotFoundException("$docId not found")
        }

        val target = File(linkTarget).let { raw ->
            if (raw.isAbsolute) raw else File(linkFile.parentFile ?: rootCanonical, linkTarget)
        }
        val checked = canonicalCandidate(target)
        if (!isSameOrChild(rootCanonical, checked)) {
            throw FileNotFoundException("$docId not found")
        }
        return checked
    }
}
