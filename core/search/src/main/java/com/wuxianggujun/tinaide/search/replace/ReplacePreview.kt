package com.wuxianggujun.tinaide.search.replace
import java.io.File

/**
 * 单个替换项
 */
data class ReplacementItem(
    val lineNumber: Int,
    val originalText: String,
    val newText: String,
    val matchStart: Int,
    val matchEnd: Int,
    val isSelected: Boolean = true    // 用户可取消选择
)

/**
 * 单文件替换预览
 */
data class ReplacePreview(
    val file: File,
    val originalContent: String,
    val newContent: String,
    val replacements: List<ReplacementItem>
) {
    val replacementCount: Int get() = replacements.count { it.isSelected }
    val hasChanges: Boolean get() = replacementCount > 0
}

/**
 * 批量替换预览
 */
data class BatchReplacePreview(
    val previews: List<ReplacePreview>,
    val totalReplacements: Int,
    val totalFiles: Int
) {
    val selectedReplacements: Int
        get() = previews.sumOf { it.replacementCount }

    val selectedFiles: Int
        get() = previews.count { it.hasChanges }
}
