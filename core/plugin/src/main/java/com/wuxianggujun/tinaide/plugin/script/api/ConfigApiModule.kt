package com.wuxianggujun.tinaide.plugin.script.api

import android.content.Context
import com.wuxianggujun.tinaide.plugin.PluginConfigurationPropertyType
import com.wuxianggujun.tinaide.plugin.PluginConfigurationSchema
import com.wuxianggujun.tinaide.plugin.PluginConfigurationStore
import com.wuxianggujun.tinaide.plugin.ResolvedPluginConfigurationProperty
import com.wuxianggujun.tinaide.plugin.script.ScriptPluginRuntime
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonPrimitive
import party.iroiro.luajava.Lua

class ConfigApiModule(context: Context) : PluginApiModule {
    override val namespace: String = "config"

    private val store = PluginConfigurationStore.getInstance(context)
    private var runtime: ScriptPluginRuntime? = null

    override fun register(runtime: ScriptPluginRuntime, lua: Lua) {
        this.runtime = runtime
        val currentRuntime = runtime

        lua.push { L: Lua ->
            val key = L.getStringArg(1)
            if (key == null) {
                L.pushNil()
                return@push 1
            }
            val property = PluginConfigurationSchema.resolveProperty(
                manifest = currentRuntime.getManifest(),
                propertyKey = key,
            ) ?: return@push L.pushNilWithError("Unknown configuration key: $key")
            val value = store.getValue(
                manifest = currentRuntime.getManifest(),
                propertyKey = key,
            ) ?: readLuaValue(L, 2, property)
            pushConfigValue(L, property, value)
            1
        }
        lua.setField(-2, "get")

        lua.push { L: Lua ->
            val key = L.getStringArg(1)
            if (key == null) {
                return@push L.pushSuccess(false, "Configuration key is required")
            }
            val property = PluginConfigurationSchema.resolveProperty(
                manifest = currentRuntime.getManifest(),
                propertyKey = key,
            ) ?: return@push L.pushSuccess(false, "Unknown configuration key: $key")
            val value = readLuaValue(L, 2, property)
                ?: return@push L.pushSuccess(false, "Invalid configuration value for key: $key")
            L.pushSuccess(
                store.setValue(
                    manifest = currentRuntime.getManifest(),
                    propertyKey = key,
                    value = value,
                ),
                "Invalid configuration value for key: $key",
            )
        }
        lua.setField(-2, "set")

        lua.push { L: Lua ->
            val key = L.getStringArg(1)
            if (key == null) {
                return@push L.pushSuccess(false, "Configuration key is required")
            }
            L.pushSuccess(
                store.resetValue(
                    manifest = currentRuntime.getManifest(),
                    propertyKey = key,
                ),
                "Unknown configuration key: $key",
            )
        }
        lua.setField(-2, "reset")
    }

    override fun unregister() {
        runtime = null
    }

    private fun readLuaValue(
        lua: Lua,
        index: Int,
        property: ResolvedPluginConfigurationProperty,
    ): JsonElement? {
        if (lua.top < index || lua.isNil(index)) return null
        val rawValue = when (property.type) {
            PluginConfigurationPropertyType.BOOLEAN -> {
                if (lua.isBoolean(index)) JsonPrimitive(lua.toBoolean(index)) else null
            }
            PluginConfigurationPropertyType.NUMBER -> {
                if (lua.isNumber(index)) JsonPrimitive(lua.toNumber(index)) else null
            }
            PluginConfigurationPropertyType.STRING -> {
                if (lua.isString(index)) JsonPrimitive(lua.toString(index) ?: "") else null
            }
        } ?: return null
        return PluginConfigurationSchema.normalizeValue(property, rawValue)
    }

    private fun pushConfigValue(
        lua: Lua,
        property: ResolvedPluginConfigurationProperty,
        value: JsonElement?,
    ) {
        if (value == null) {
            lua.pushNil()
            return
        }
        when (property.type) {
            PluginConfigurationPropertyType.BOOLEAN -> lua.push(
                PluginConfigurationSchema.booleanValue(value)
            )
            PluginConfigurationPropertyType.NUMBER ->
                value
                    .jsonPrimitive
                    .doubleOrNull
                    ?.let(lua::push)
                    ?: lua.pushNil()
            PluginConfigurationPropertyType.STRING ->
                PluginConfigurationSchema
                    .stringValue(value)
                    ?.let(lua::push)
                    ?: lua.pushNil()
        }
    }

    private fun Lua.pushNilWithError(message: String): Int {
        pushNil()
        push(message)
        return 2
    }
}
