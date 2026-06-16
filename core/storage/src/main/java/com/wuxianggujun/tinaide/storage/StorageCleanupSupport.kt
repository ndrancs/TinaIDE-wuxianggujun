package com.wuxianggujun.tinaide.storage

import java.io.File
import timber.log.Timber

/**
 * 纯文件系统操作的可清理助手，抽离自 [StorageCleanupManager] 以便脱离 Android Context 做单测。
 */
internal object StorageCleanupSupport {

    private const val TAG = "StorageCleanupSupport"

    data class DeleteContentsResult(
        val deletedBytes: Long,
        val failedPaths: List<String>
    )

    /** 递归计算文件或目录占用字节数；失败时记 0。 */
    fun computeSize(file: File): Long = try {
        when {
            !file.exists() -> 0L
            file.isFile -> file.length()
            else -> file.listFiles()?.sumOf { computeSize(it) } ?: 0L
        }
    } catch (t: Throwable) {
        Timber.tag(TAG).w(t, "Failed to compute size: %s", file.absolutePath)
        0L
    }

    /**
     * 只删除目录内的内容，保留目录本身。目录不存在视作已完成（0 字节，无失败）。
     *
     * 设计原因：TinaIDE 全局依赖 ProjectPaths 的 getter 返回的目录存在不变量，直接删掉目录
     * 会破坏下一次 `ensureDir` 之前的链路假设。
     */
    fun deleteContents(dir: File): DeleteContentsResult {
        if (!dir.exists() || !dir.isDirectory) {
            return DeleteContentsResult(0L, emptyList())
        }
        var deleted = 0L
        val failed = mutableListOf<String>()
        dir.listFiles()?.forEach { child ->
            val size = computeSize(child)
            if (tryDeleteRecursively(child)) {
                deleted += size
            } else {
                failed += child.absolutePath
            }
        }
        return DeleteContentsResult(deleted, failed)
    }

    private fun tryDeleteRecursively(file: File): Boolean = try {
        file.deleteRecursively()
    } catch (t: Throwable) {
        Timber.tag(TAG).w(t, "Failed to delete: %s", file.absolutePath)
        false
    }

    /** [tryDeleteRecursively] 的对外别名,供 [StorageCleanupManager.cleanPaths] 复用。 */
    fun deleteRecursivelySafely(file: File): Boolean = tryDeleteRecursively(file)

    /**
     * 判断 [target] 是否位于 [roots] 任一根目录"之下或等于"。使用 canonicalPath 做规范化,
     * 防止通过 `..` 之类的相对路径越权。
     */
    fun isPathUnderAnyRoot(target: File, roots: List<File>): Boolean {
        val targetCanonical = runCatching { target.canonicalPath }.getOrElse { target.absolutePath }
        return roots.any { root ->
            val rootCanonical = runCatching { root.canonicalPath }.getOrElse { root.absolutePath }
            targetCanonical == rootCanonical ||
                targetCanonical.startsWith(rootCanonical + File.separator)
        }
    }
}
