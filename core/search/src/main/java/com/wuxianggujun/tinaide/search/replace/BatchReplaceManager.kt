package com.wuxianggujun.tinaide.search.replace

import com.wuxianggujun.tinaide.search.ProjectSearchResult
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

/**
 * 批量替换管理器
 * 负责管理批量替换操作的执行和进度
 */
class BatchReplaceManager(
    private val replaceEngine: ReplaceEngine = ReplaceEngine()
) {
    private val _progress = MutableStateFlow(ReplaceProgress(0, 0))
    val progress: StateFlow<ReplaceProgress> = _progress.asStateFlow()

    private val _isRunning = MutableStateFlow(false)
    val isRunning: StateFlow<Boolean> = _isRunning.asStateFlow()

    // 存储成功替换的备份信息，用于撤销
    private val backupInfos = mutableListOf<Pair<File, String>>()

    /**
     * 生成批量替换预览
     */
    suspend fun generateBatchPreview(
        results: List<ProjectSearchResult>,
        searchQuery: String,
        replacement: String,
        options: ReplaceOptions
    ): BatchReplacePreview = withContext(Dispatchers.IO) {
        val groupedByFile = results.groupBy { it.file }
        val previews = mutableListOf<ReplacePreview>()

        groupedByFile.forEach { (file, matches) ->
            val preview = replaceEngine.generatePreview(
                file = file,
                searchQuery = searchQuery,
                replacement = replacement,
                matches = matches,
                options = options
            )
            previews.add(preview)
        }

        BatchReplacePreview(
            previews = previews,
            totalReplacements = previews.sumOf { it.replacements.size },
            totalFiles = previews.size
        )
    }

    /**
     * 执行批量替换
     */
    suspend fun executeBatch(
        previews: List<ReplacePreview>,
        createBackups: Boolean = true
    ): BatchReplaceResult = withContext(Dispatchers.IO) {
        _isRunning.value = true
        backupInfos.clear()

        val results = mutableListOf<ReplaceResult>()
        val totalFiles = previews.size
        var completedFiles = 0
        var totalReplacements = 0

        _progress.value = ReplaceProgress(
            totalFiles = totalFiles,
            completedFiles = 0,
            isRunning = true
        )

        previews.forEach { preview ->
            if (!preview.hasChanges) {
                completedFiles++
                _progress.value = _progress.value.copy(
                    completedFiles = completedFiles,
                    currentFile = preview.file.name
                )
                return@forEach
            }

            val result = replaceEngine.replaceInFile(preview, createBackups)
            results.add(result)

            if (result is ReplaceResult.Success) {
                totalReplacements += result.replacedCount
                result.backupPath?.let { backupInfos.add(preview.file to it) }
            }

            completedFiles++
            _progress.value = _progress.value.copy(
                completedFiles = completedFiles,
                currentFile = preview.file.name
            )
        }

        _isRunning.value = false
        _progress.value = _progress.value.copy(isRunning = false)

        val successCount = results.count { it is ReplaceResult.Success }
        val failedCount = results.count { it is ReplaceResult.Failure }

        BatchReplaceResult(
            totalFiles = totalFiles,
            successFiles = successCount,
            failedFiles = failedCount,
            totalReplacements = totalReplacements,
            results = results
        )
    }

    /**
     * 撤销上一次批量替换
     */
    suspend fun undoLastBatch(): Boolean = withContext(Dispatchers.IO) {
        if (backupInfos.isEmpty()) return@withContext false

        var allSuccess = true
        backupInfos.forEach { (file, backupPath) ->
            val success = replaceEngine.restoreFromBackup(backupPath, file)
            if (!success) allSuccess = false
        }

        if (allSuccess) {
            backupInfos.clear()
        }

        allSuccess
    }

    /**
     * 清理备份文件
     */
    suspend fun cleanupBackups() = withContext(Dispatchers.IO) {
        backupInfos.forEach { (_, backupPath) ->
            try {
                File(backupPath).delete()
            } catch (e: Exception) {
                // 忽略删除失败
            }
        }
        backupInfos.clear()
    }

    /**
     * 检查是否有可撤销的操作
     */
    fun canUndo(): Boolean = backupInfos.isNotEmpty()
}
