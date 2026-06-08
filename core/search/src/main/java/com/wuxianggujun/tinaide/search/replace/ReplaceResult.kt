package com.wuxianggujun.tinaide.search.replace

import java.io.File

/**
 * 单文件替换结果
 */
sealed class ReplaceResult {
    /**
     * 替换成功
     */
    data class Success(
        val file: File,
        val replacedCount: Int,
        val backupPath: String? = null
    ) : ReplaceResult()

    /**
     * 替换失败
     */
    data class Failure(
        val file: File,
        val error: String
    ) : ReplaceResult()
}

/**
 * 批量替换结果
 */
data class BatchReplaceResult(
    val totalFiles: Int,
    val successFiles: Int,
    val failedFiles: Int,
    val totalReplacements: Int,
    val results: List<ReplaceResult>
) {
    val isSuccess: Boolean get() = failedFiles == 0
    val hasPartialSuccess: Boolean get() = successFiles > 0 && failedFiles > 0
}

/**
 * 替换进度
 */
data class ReplaceProgress(
    val totalFiles: Int,
    val completedFiles: Int,
    val currentFile: String? = null,
    val isRunning: Boolean = false
) {
    val progress: Float get() = if (totalFiles > 0) completedFiles.toFloat() / totalFiles else 0f
}
