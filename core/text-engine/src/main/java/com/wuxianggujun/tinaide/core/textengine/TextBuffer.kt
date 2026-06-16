package com.wuxianggujun.tinaide.core.textengine

import java.io.File
import java.nio.charset.Charset
import kotlinx.coroutines.flow.StateFlow

interface TextBuffer {
    val length: Int
    val lineCount: Int
    val version: Long
    val history: EditHistory

    /**
     * 版本号 StateFlow。每次文本变化（insert / delete / replace / undo / redo / load）都会 emit 最新 version。
     *
     * 作用：让订阅方（LSP semantic tokens、反向搜索等）不需要自己维护 callbackFlow + addChangeListener
     * 的注册/注销，避免上层 LaunchedEffect 重启时的 listener 注册风暴。
     */
    val versionFlow: StateFlow<Long>

    fun insert(offset: Int, text: String)
    fun delete(start: Int, end: Int)
    fun replace(start: Int, end: Int, text: String)

    fun substring(start: Int, end: Int): String
    fun charAt(offset: Int): Char?
    fun getLine(line: Int): String
    fun getLineStart(line: Int): Int
    fun getLineEnd(line: Int): Int
    fun offsetToLine(offset: Int): Int
    fun positionToOffset(line: Int, column: Int): Int
    fun offsetToPosition(offset: Int): Position

    fun addChangeListener(listener: TextChangeListener)
    fun removeChangeListener(listener: TextChangeListener)

    fun canUndo(): Boolean
    fun canRedo(): Boolean
    fun undo(): TextChange?
    fun redo(): TextChange?

    suspend fun loadFromFile(file: File, charset: Charset = Charsets.UTF_8): Result<Unit>
    suspend fun saveToFile(file: File, charset: Charset = Charsets.UTF_8): Result<Unit>
}
