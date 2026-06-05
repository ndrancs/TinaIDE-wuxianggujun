package com.wuxianggujun.tinaide.plugin.script.api

import android.content.Context
import com.wuxianggujun.tinaide.plugin.script.ScriptPluginRuntime
import party.iroiro.luajava.Lua

/**
 * A plugin API module that exposes functions under `tina.<namespace>`.
 *
 * The registry creates a Lua table for each module, passes it via [register],
 * and mounts it at `tina.<namespace>` automatically.
 * Implementations must NOT call `lua.setGlobal()`.
 */
interface PluginApiModule {
    /** Sub-key under the `tina` global table (e.g. "editor", "fs", "log"). */
    val namespace: String

    /**
     * Populate the table currently on top of the Lua stack with API functions.
     * The table is already created by the registry; just push functions and set fields.
     */
    fun register(runtime: ScriptPluginRuntime, lua: Lua)

    /** Release resources held by this module. */
    fun unregister()
}

/**
 * Central registry that collects all [PluginApiModule]s and mounts them
 * under a single `tina` global table when a script plugin is initialised.
 */
class PluginApiRegistry(private val context: Context) {
    private val moduleFactories = linkedMapOf<String, () -> PluginApiModule>()
    private val runtimeModules = mutableMapOf<String, List<PluginApiModule>>()

    fun registerModule(namespace: String, factory: () -> PluginApiModule) {
        moduleFactories[namespace] = factory
    }

    fun unregisterModule(namespace: String) {
        moduleFactories.remove(namespace)
        runtimeModules.values.forEach { modules ->
            modules
                .filter { module -> module.namespace == namespace }
                .forEach { module -> module.unregister() }
        }
    }

    /**
     * Build the `tina` global table and populate every registered module.
     *
     * Stack layout while iterating:
     *   [-2] tina table
     *   [-1] current module sub-table
     *
     * After the call the stack is clean and `tina` is available globally.
     */
    fun initializeForRuntime(runtime: ScriptPluginRuntime) {
        val lua = runtime.getLuaState() ?: return
        cleanupRuntime(runtime.pluginId)
        val modules = moduleFactories.values.map { createModule -> createModule() }
        runtimeModules[runtime.pluginId] = modules

        // tina = {}
        lua.createTable(0, modules.size + 1)

        modules.forEach { module ->
            lua.createTable(0, 12)
            module.register(runtime, lua)
            lua.setField(-2, module.namespace)
        }

        // tina.pluginId = "<id>"
        lua.push(runtime.pluginId)
        lua.setField(-2, "pluginId")

        // tina.apiVersion = 1
        lua.push(runtime.apiVersion)
        lua.setField(-2, "apiVersion")

        lua.setGlobal("tina")
    }

    fun cleanupRuntime(pluginId: String) {
        runtimeModules.remove(pluginId)?.forEach { module -> module.unregister() }
    }

    fun cleanup() {
        runtimeModules.values
            .flatten()
            .forEach { module -> module.unregister() }
        runtimeModules.clear()
    }

    companion object {
        fun createDefaultRegistry(
            context: Context,
            projectRootProvider: () -> String? = { null }
        ): PluginApiRegistry {
            val workspaceFileAccess = PluginWorkspaceFileAccess(projectRootProvider)
            return PluginApiRegistry(context).apply {
                registerModule("editor") { EditorApiModule() }
                registerModule("ui") { UiApiModule(context) }
                registerModule("config") { ConfigApiModule(context) }
                registerModule("storage") { StorageApiModule(context) }
                registerModule("commands") { CommandsApiModule(projectRootProvider) }
                registerModule("events") { EventsApiModule() }
                registerModule("diagnostics") { DiagnosticsApiModule() }
                registerModule("workspace") { WorkspaceApiModule(workspaceFileAccess) }
                registerModule("fs") { FileApiModule(workspaceFileAccess) }
                registerModule("clipboard") { ClipboardApiModule(context) }
                registerModule("network") { NetworkApiModule(context) }
                registerModule("db") { DatabaseApiModule(context) }
            }
        }
    }
}
