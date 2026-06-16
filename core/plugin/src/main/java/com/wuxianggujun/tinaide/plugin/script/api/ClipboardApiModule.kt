package com.wuxianggujun.tinaide.plugin.script.api

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Handler
import android.os.Looper
import com.wuxianggujun.tinaide.plugin.script.PluginPermission
import com.wuxianggujun.tinaide.plugin.script.ScriptPluginRuntime
import party.iroiro.luajava.Lua
import timber.log.Timber

class ClipboardApiModule(private val context: Context) : PluginApiModule {
    override val namespace = "clipboard"

    private var runtime: ScriptPluginRuntime? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private val clipboardManager by lazy {
        context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    }

    override fun register(runtime: ScriptPluginRuntime, lua: Lua) {
        this.runtime = runtime

        lua.push { L: Lua ->
            withPermission(this.runtime, L, namespace, "getText", PluginPermission.CLIPBOARD_READ) {
                try {
                    val text = clipboardManager.primaryClip?.getItemAt(0)?.text?.toString()
                    if (text != null) L.push(text) else L.pushNil()
                    1
                } catch (e: Exception) {
                    Timber.w(e, "Failed to read clipboard")
                    L.pushNil()
                    L.push("Failed to read clipboard: ${e.message}")
                    2
                }
            }
        }
        lua.setField(-2, "getText")

        lua.push { L: Lua ->
            withPermission(this.runtime, L, namespace, "setText", PluginPermission.CLIPBOARD_WRITE) {
                val text = L.getStringArg(1)
                if (text == null) {
                    L.push(false)
                    L.push("Text is required")
                    return@withPermission 2
                }
                try {
                    mainHandler.post {
                        clipboardManager.setPrimaryClip(ClipData.newPlainText("Plugin", text))
                    }
                    L.push(true)
                    1
                } catch (e: Exception) {
                    Timber.w(e, "Failed to write clipboard")
                    L.push(false)
                    L.push("Failed to write clipboard: ${e.message}")
                    2
                }
            }
        }
        lua.setField(-2, "setText")

        lua.push { L: Lua ->
            withPermission(this.runtime, L, namespace, "hasText", PluginPermission.CLIPBOARD_READ) {
                val hasText = clipboardManager.hasPrimaryClip() &&
                    clipboardManager.primaryClipDescription?.hasMimeType("text/plain") == true
                L.push(hasText)
                1
            }
        }
        lua.setField(-2, "hasText")
    }

    override fun unregister() {
        runtime = null
    }
}
