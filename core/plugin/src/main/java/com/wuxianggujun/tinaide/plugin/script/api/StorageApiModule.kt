package com.wuxianggujun.tinaide.plugin.script.api

import android.content.Context
import com.wuxianggujun.tinaide.plugin.script.PluginPermission
import com.wuxianggujun.tinaide.plugin.script.ScriptPluginRuntime
import party.iroiro.luajava.Lua

class StorageApiModule(private val context: Context) : PluginApiModule {
    override val namespace = "storage"

    private var runtime: ScriptPluginRuntime? = null

    override fun register(runtime: ScriptPluginRuntime, lua: Lua) {
        this.runtime = runtime
        val pluginId = runtime.pluginId

        lua.push { L: Lua ->
            withPermission(this.runtime, L, namespace, "get", PluginPermission.STORAGE_LOCAL) {
                val key = L.getStringArg(1)
                if (key == null) {
                    L.pushNil()
                    return@withPermission 1
                }
                val fullKey = "$pluginId:$key"
                val prefs = context.getSharedPreferences("plugin_storage", Context.MODE_PRIVATE)
                val value = prefs.getString(fullKey, null)
                if (value != null) L.push(value) else L.pushNil()
                1
            }
        }
        lua.setField(-2, "get")

        lua.push { L: Lua ->
            withPermission(this.runtime, L, namespace, "set", PluginPermission.STORAGE_LOCAL) {
                val key = L.getStringArg(1)
                val value = L.getStringArg(2)
                if (key != null) {
                    val fullKey = "$pluginId:$key"
                    val prefs = context.getSharedPreferences("plugin_storage", Context.MODE_PRIVATE)
                    prefs.edit().putString(fullKey, value).apply()
                }
                0
            }
        }
        lua.setField(-2, "set")

        lua.push { L: Lua ->
            withPermission(this.runtime, L, namespace, "remove", PluginPermission.STORAGE_LOCAL) {
                val key = L.getStringArg(1)
                if (key != null) {
                    val fullKey = "$pluginId:$key"
                    val prefs = context.getSharedPreferences("plugin_storage", Context.MODE_PRIVATE)
                    prefs.edit().remove(fullKey).apply()
                }
                0
            }
        }
        lua.setField(-2, "remove")
    }

    override fun unregister() {
        runtime = null
    }
}
