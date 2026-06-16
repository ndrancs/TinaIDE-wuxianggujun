package com.wuxianggujun.tinaide.core.proot

import android.system.Os
import java.io.File
import java.nio.charset.Charset
import java.nio.file.Files

/**
 * Rootfs 文件存在性检查。
 *
 * 部分发行版 rootfs 中的常用命令是指向 BusyBox 的绝对符号链接。
 * 宿主机直接使用 File.exists() 会按 Android 根目录解析，导致有效链接被误判为缺失。
 */
internal object RootfsFileChecks {

    private const val MAX_LINK_DEPTH = 16

    fun exists(
        rootDir: File,
        pathInRootfs: String,
        readLinkTarget: (File) -> String? = ::readLinkTarget,
    ): Boolean = resolveExistingFile(rootDir, pathInRootfs, readLinkTarget) != null

    fun isDirectory(
        rootDir: File,
        pathInRootfs: String,
        readLinkTarget: (File) -> String? = ::readLinkTarget,
    ): Boolean = resolveExistingFile(rootDir, pathInRootfs, readLinkTarget)?.isDirectory == true

    fun readTextOrNull(
        rootDir: File,
        pathInRootfs: String,
        charset: Charset = Charsets.UTF_8,
        readLinkTarget: (File) -> String? = ::readLinkTarget,
    ): String? {
        val resolvedFile = resolveExistingFile(rootDir, pathInRootfs, readLinkTarget) ?: return null
        return resolvedFile.takeIf { it.isFile }?.readText(charset)
    }

    private fun resolveExistingFile(
        rootDir: File,
        pathInRootfs: String,
        readLinkTarget: (File) -> String?,
    ): File? {
        var currentRelativePath = normalizeRootfsPath(pathInRootfs) ?: return null
        repeat(MAX_LINK_DEPTH) {
            val currentFile = File(rootDir, currentRelativePath)
            if (currentFile.exists()) return currentFile

            val linkTarget = readLinkTarget(currentFile) ?: return null
            currentRelativePath = resolveTargetPath(currentRelativePath, linkTarget) ?: return null
        }
        return null
    }

    private fun normalizeRootfsPath(pathInRootfs: String): String? {
        val normalized = pathInRootfs.replace('\\', '/').trim()
        if (normalized.isEmpty() || normalized == "/") return ""
        if (normalized == ".." || normalized.startsWith("../") || normalized.contains("/../")) return null
        return normalized.removePrefix("/")
    }

    private fun resolveTargetPath(pathInRootfs: String, linkTarget: String): String? {
        val segments = ArrayDeque<String>()

        if (!linkTarget.startsWith("/")) {
            val parentPath = pathInRootfs.substringBeforeLast('/', missingDelimiterValue = "")
            if (parentPath.isNotEmpty()) {
                parentPath.split('/').filter { it.isNotEmpty() }.forEach(segments::addLast)
            }
        }

        val normalizedTarget = linkTarget.replace('\\', '/')
        for (segment in normalizedTarget.split('/')) {
            when (segment) {
                "", "." -> Unit
                ".." -> {
                    if (segments.isEmpty()) return null
                    segments.removeLast()
                }
                else -> segments.addLast(segment)
            }
        }

        return segments.joinToString("/")
    }

    private fun readLinkTarget(file: File): String? = runCatching { Os.readlink(file.absolutePath) }
        .recoverCatching { Files.readSymbolicLink(file.toPath()).toString() }
        .getOrNull()
}
