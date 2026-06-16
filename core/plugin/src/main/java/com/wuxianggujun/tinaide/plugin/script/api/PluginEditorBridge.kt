package com.wuxianggujun.tinaide.plugin.script.api

import java.io.File
import java.util.concurrent.atomic.AtomicReference

/**
 * 编辑器选区信息
 */
data class EditorSelection(
    val startLine: Int,
    val startColumn: Int,
    val endLine: Int,
    val endColumn: Int,
    val text: String
)

/**
 * 光标位置信息
 */
data class CursorPosition(
    val line: Int,
    val column: Int
)

/**
 * 当前活动编辑器快照
 */
data class PluginActiveEditor(
    val tabId: String,
    val filePath: String,
    val fileName: String,
    val languageId: String,
    val isDirty: Boolean,
    val cursor: CursorPosition? = null
)

/**
 * 插件编辑器桥接接口
 *
 * 提供插件访问编辑器的能力，具体实现由 feature:editor 提供。
 * 这样 core:plugin 不需要依赖具体的编辑器 UI 组件。
 */
interface PluginEditorBridge {
    /**
     * 获取当前活动编辑器的稳定快照
     */
    fun getActiveEditor(): PluginActiveEditor?

    /**
     * 获取当前活动文件
     */
    fun getActiveFile(): File?

    /**
     * 获取当前活动标签页 ID
     */
    fun getActiveTabId(): String?

    /**
     * 获取编辑器文本内容
     */
    fun getText(): String?

    /**
     * 设置编辑器文本内容
     */
    fun setText(text: String): Boolean

    /**
     * 获取当前选区
     */
    fun getSelection(): EditorSelection?

    /**
     * 设置选区
     */
    fun setSelection(startLine: Int, startColumn: Int, endLine: Int, endColumn: Int): Boolean

    /**
     * 插入文本
     */
    fun insertText(text: String, line: Int? = null, column: Int? = null): Boolean

    /**
     * 替换选区文本
     */
    fun replaceSelection(text: String): Boolean

    /**
     * 获取当前文件的语言 ID
     */
    fun getLanguage(): String?

    /**
     * 获取光标位置
     */
    fun getCursorPosition(): CursorPosition?

    /**
     * 设置光标位置
     */
    fun setCursorPosition(line: Int, column: Int): Boolean
}

/**
 * 插件编辑器桥接持有者
 *
 * 用于在 app 层注入具体实现，供 core:plugin 使用。
 */
object PluginEditorBridgeHolder {
    private val bridge = AtomicReference<PluginEditorBridge?>(null)

    fun set(bridge: PluginEditorBridge) {
        this.bridge.set(bridge)
    }

    fun get(): PluginEditorBridge? = bridge.get()

    fun clear() {
        bridge.set(null)
    }
}
