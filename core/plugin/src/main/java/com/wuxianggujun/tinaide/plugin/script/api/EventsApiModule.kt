package com.wuxianggujun.tinaide.plugin.script.api

import com.wuxianggujun.tinaide.plugin.script.ScriptPluginRuntime
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import party.iroiro.luajava.Lua
import timber.log.Timber

enum class PluginEvent(val id: String) {
    EDITOR_OPENED("editor.opened"),
    EDITOR_CLOSED("editor.closed"),
    EDITOR_SAVED("editor.saved"),
    EDITOR_ACTIVE_CHANGED("editor.activeChanged"),
    EDITOR_SELECTION_CHANGED("editor.selectionChanged"),
    EDITOR_DIRTY_CHANGED("editor.dirtyChanged"),
    FILE_CREATED("file.created"),
    FILE_DELETED("file.deleted"),
    FILE_RENAMED("file.renamed"),
    DIAGNOSTICS_CHANGED("diagnostics.changed"),
    PROJECT_OPENED("project.opened"),
    PROJECT_CLOSED("project.closed"),
    BUILD_STARTED("build.started"),
    BUILD_FINISHED("build.finished"),
    CONFIG_CHANGED("config.changed"),
    CUSTOM("custom");

    companion object {
        fun fromId(id: String): PluginEvent? = entries.find { it.id == id }
    }
}

data class EventListener(
    val pluginId: String,
    val eventId: String,
    val callbackName: String
)

object PluginEventBus {
    private val listeners = ConcurrentHashMap<String, CopyOnWriteArrayList<EventListener>>()
    private var runtimeProvider: ((String) -> ScriptPluginRuntime?)? = null

    fun setRuntimeProvider(provider: (String) -> ScriptPluginRuntime?) {
        runtimeProvider = provider
    }

    fun subscribe(pluginId: String, eventId: String, callbackName: String) {
        val list = listeners.getOrPut(eventId) { CopyOnWriteArrayList() }
        list.add(EventListener(pluginId, eventId, callbackName))
        Timber.d("Plugin $pluginId subscribed to event: $eventId")
    }

    fun unsubscribe(pluginId: String, eventId: String) {
        listeners[eventId]?.removeIf { it.pluginId == pluginId }
        Timber.d("Plugin $pluginId unsubscribed from event: $eventId")
    }

    fun unsubscribeAll(pluginId: String) {
        listeners.values.forEach { list ->
            list.removeIf { it.pluginId == pluginId }
        }
        Timber.d("Plugin $pluginId unsubscribed from all events")
    }

    suspend fun emit(
        eventId: String,
        data: Map<String, Any?>? = null,
        targetPluginId: String? = null,
    ) {
        val eventListeners = listeners[eventId] ?: return
        val listenersToNotify = if (targetPluginId == null) {
            eventListeners
        } else {
            eventListeners.filter { listener -> listener.pluginId == targetPluginId }
        }
        Timber.d("Emitting event: $eventId to ${listenersToNotify.size} listeners")

        listenersToNotify.forEach { listener ->
            try {
                val runtime = runtimeProvider?.invoke(listener.pluginId)
                if (runtime != null) {
                    runtime.callFunction(listener.callbackName, data)
                }
            } catch (e: Exception) {
                Timber.e(e, "Error calling event listener ${listener.callbackName} in plugin ${listener.pluginId}")
            }
        }
    }

    fun clear() {
        listeners.clear()
    }
}

class EventsApiModule : PluginApiModule {
    override val namespace = "events"

    private var runtime: ScriptPluginRuntime? = null

    override fun register(runtime: ScriptPluginRuntime, lua: Lua) {
        this.runtime = runtime
        val pluginId = runtime.pluginId

        lua.push { L: Lua ->
            val eventId = L.getStringArg(1)
            val callbackName = L.getStringArg(2)
            if (eventId == null || callbackName == null) {
                Timber.w("events.on: missing eventId or callbackName")
                return@push 0
            }
            PluginEventBus.subscribe(pluginId, eventId, callbackName)
            0
        }
        lua.setField(-2, "on")

        lua.push { L: Lua ->
            val eventId = L.getStringArg(1)
            if (eventId == null) {
                Timber.w("events.off: missing eventId")
                return@push 0
            }
            PluginEventBus.unsubscribe(pluginId, eventId)
            0
        }
        lua.setField(-2, "off")

        lua.push { L: Lua ->
            val eventId = L.getStringArg(1)
            if (eventId == null) {
                Timber.w("events.emit: missing eventId")
                return@push 0
            }
            Timber.d("Plugin $pluginId emitting custom event: $eventId")
            0
        }
        lua.setField(-2, "emit")

        lua.push { _: Lua ->
            PluginEventBus.unsubscribeAll(pluginId)
            0
        }
        lua.setField(-2, "clear")
    }

    override fun unregister() {
        runtime?.let { rt ->
            PluginEventBus.unsubscribeAll(rt.pluginId)
        }
        runtime = null
    }
}
