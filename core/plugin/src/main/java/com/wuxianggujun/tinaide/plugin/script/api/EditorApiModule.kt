package com.wuxianggujun.tinaide.plugin.script.api

import android.os.Handler
import android.os.Looper
import com.wuxianggujun.tinaide.plugin.script.PluginPermission
import com.wuxianggujun.tinaide.plugin.script.ScriptPluginRuntime
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import party.iroiro.luajava.Lua
import timber.log.Timber

class EditorApiModule : PluginApiModule {
    companion object {
        private const val TAG = "EditorApiModule"
        private const val MAIN_THREAD_WAIT_TIMEOUT_MS = 2_000L
    }

    override val namespace = "editor"

    private var runtime: ScriptPluginRuntime? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    override fun register(runtime: ScriptPluginRuntime, lua: Lua) {
        this.runtime = runtime

        lua.push { L: Lua ->
            withPermission(this.runtime, L, namespace, "getActiveEditor", PluginPermission.EDITOR_READ) {
                val editor = PluginEditorBridgeHolder.get()?.getActiveEditor()
                if (editor == null) {
                    L.pushNil()
                } else {
                    L.pushActiveEditor(editor)
                }
                1
            }
        }
        lua.setField(-2, "getActiveEditor")

        lua.push { L: Lua ->
            withPermission(this.runtime, L, namespace, "getText", PluginPermission.EDITOR_READ) {
                val text = PluginEditorBridgeHolder.get()?.getText() ?: ""
                L.push(text)
                1
            }
        }
        lua.setField(-2, "getText")

        lua.push { L: Lua ->
            withPermission(this.runtime, L, namespace, "setText", PluginPermission.EDITOR_WRITE) {
                val text = L.getStringArg(1) ?: ""
                val bridge = PluginEditorBridgeHolder.get()
                val success = runOnMainThreadBlocking(false) {
                    bridge?.setText(text) ?: false
                }
                L.push(success)
                1
            }
        }
        lua.setField(-2, "setText")

        lua.push { L: Lua ->
            withPermission(this.runtime, L, namespace, "getSelection", PluginPermission.EDITOR_SELECTION) {
                val selection = PluginEditorBridgeHolder.get()?.getSelection()
                L.createTable(0, 5)
                L.push(selection?.startLine ?: 0)
                L.setField(-2, "startLine")
                L.push(selection?.startColumn ?: 0)
                L.setField(-2, "startColumn")
                L.push(selection?.endLine ?: 0)
                L.setField(-2, "endLine")
                L.push(selection?.endColumn ?: 0)
                L.setField(-2, "endColumn")
                L.push(selection?.text ?: "")
                L.setField(-2, "text")
                1
            }
        }
        lua.setField(-2, "getSelection")

        lua.push { L: Lua ->
            withPermission(this.runtime, L, namespace, "insertText", PluginPermission.EDITOR_WRITE) {
                val text = L.getStringArg(1) ?: ""
                val line = L.getIntArg(2)
                val column = L.getIntArg(3)
                val bridge = PluginEditorBridgeHolder.get()
                val success = runOnMainThreadBlocking(false) {
                    bridge?.insertText(text, line, column) ?: false
                }
                L.push(success)
                1
            }
        }
        lua.setField(-2, "insertText")

        lua.push { L: Lua ->
            withPermission(this.runtime, L, namespace, "replaceSelection", PluginPermission.EDITOR_WRITE) {
                val text = L.getStringArg(1) ?: ""
                val bridge = PluginEditorBridgeHolder.get()
                val success = runOnMainThreadBlocking(false) {
                    bridge?.replaceSelection(text) ?: false
                }
                L.push(success)
                1
            }
        }
        lua.setField(-2, "replaceSelection")

        lua.push { L: Lua ->
            withPermission(this.runtime, L, namespace, "getLanguage", PluginPermission.EDITOR_READ) {
                val language = PluginEditorBridgeHolder.get()?.getLanguage() ?: "unknown"
                L.push(language)
                1
            }
        }
        lua.setField(-2, "getLanguage")

        lua.push { L: Lua ->
            withPermission(this.runtime, L, namespace, "getCursorPosition", PluginPermission.EDITOR_READ) {
                val pos = PluginEditorBridgeHolder.get()?.getCursorPosition()
                L.createTable(0, 2)
                L.push(pos?.line ?: 0)
                L.setField(-2, "line")
                L.push(pos?.column ?: 0)
                L.setField(-2, "column")
                1
            }
        }
        lua.setField(-2, "getCursorPosition")

        lua.push { L: Lua ->
            withPermission(this.runtime, L, namespace, "setCursorPosition", PluginPermission.EDITOR_WRITE) {
                val line = L.getIntArg(1) ?: 0
                val column = L.getIntArg(2) ?: 0
                val bridge = PluginEditorBridgeHolder.get()
                val success = runOnMainThreadBlocking(false) {
                    bridge?.setCursorPosition(line, column) ?: false
                }
                L.push(success)
                1
            }
        }
        lua.setField(-2, "setCursorPosition")

        lua.push { L: Lua ->
            withPermission(this.runtime, L, namespace, "setSelection", PluginPermission.EDITOR_WRITE) {
                val startLine = L.getIntArg(1) ?: 0
                val startColumn = L.getIntArg(2) ?: 0
                val endLine = L.getIntArg(3) ?: startLine
                val endColumn = L.getIntArg(4) ?: startColumn
                val bridge = PluginEditorBridgeHolder.get()
                val success = runOnMainThreadBlocking(false) {
                    bridge?.setSelection(startLine, startColumn, endLine, endColumn) ?: false
                }
                L.push(success)
                1
            }
        }
        lua.setField(-2, "setSelection")

        lua.push { L: Lua ->
            withPermission(this.runtime, L, namespace, "getFilePath", PluginPermission.EDITOR_READ) {
                val file = PluginEditorBridgeHolder.get()?.getActiveFile()
                if (file != null) L.push(file.absolutePath) else L.pushNil()
                1
            }
        }
        lua.setField(-2, "getFilePath")

        lua.push { L: Lua ->
            withPermission(this.runtime, L, namespace, "getFileName", PluginPermission.EDITOR_READ) {
                val file = PluginEditorBridgeHolder.get()?.getActiveFile()
                if (file != null) L.push(file.name) else L.pushNil()
                1
            }
        }
        lua.setField(-2, "getFileName")
    }

    override fun unregister() {
        runtime = null
    }

    private fun <T> runOnMainThreadBlocking(defaultValue: T, block: () -> T): T {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            return runCatching(block).getOrElse { error ->
                Timber.tag(TAG).e(error, "Editor main-thread action failed")
                defaultValue
            }
        }

        val result = AtomicReference<Any?>(UnsetResult)
        val latch = CountDownLatch(1)
        if (!mainHandler.post {
                try {
                    result.set(block())
                } catch (error: Throwable) {
                    Timber.tag(TAG).e(error, "Editor main-thread action failed")
                    result.set(defaultValue)
                } finally {
                    latch.countDown()
                }
            }
        ) {
            return defaultValue
        }

        return try {
            val awaited = latch.await(MAIN_THREAD_WAIT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            if (!awaited) {
                Timber.tag(TAG).w("Editor main-thread action timed out after %sms", MAIN_THREAD_WAIT_TIMEOUT_MS)
                defaultValue
            } else {
                @Suppress("UNCHECKED_CAST")
                val value = result.get()
                if (value === UnsetResult) defaultValue else value as T
            }
        } catch (error: InterruptedException) {
            Thread.currentThread().interrupt()
            Timber.tag(TAG).w(error, "Editor main-thread action interrupted")
            defaultValue
        }
    }

    private fun Lua.pushActiveEditor(editor: PluginActiveEditor) {
        createTable(0, 6)
        push(editor.tabId)
        setField(-2, "tabId")
        push(editor.filePath)
        setField(-2, "filePath")
        push(editor.fileName)
        setField(-2, "fileName")
        push(editor.languageId)
        setField(-2, "languageId")
        push(editor.isDirty)
        setField(-2, "isDirty")
        editor.cursor?.let { cursor ->
            createTable(0, 2)
            push(cursor.line)
            setField(-2, "line")
            push(cursor.column)
            setField(-2, "column")
        } ?: pushNil()
        setField(-2, "cursor")
    }

    private object UnsetResult
}
