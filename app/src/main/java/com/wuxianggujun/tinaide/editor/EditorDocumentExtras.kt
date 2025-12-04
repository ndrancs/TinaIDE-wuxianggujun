package com.wuxianggujun.tinaide.editor

import android.os.Bundle
import androidx.core.os.bundleOf

/**
 * CodeEditor 文档 extra 的统一入口。
 * 通过 setText(text, extras) 传入的参数会在语言分析、补全等流程中复用。
 */
object EditorDocumentExtras {

    const val KEY_FILE_PATH = "com.wuxianggujun.tinaide.editor.FILE_PATH"
    const val KEY_PROJECT_PATH = "com.wuxianggujun.tinaide.editor.PROJECT_PATH"

    fun create(filePath: String?, projectPath: String?): Bundle {
        return bundleOf(
            KEY_FILE_PATH to filePath,
            KEY_PROJECT_PATH to projectPath
        )
    }
}
