package com.wuxianggujun.tinaide.plugin.script

import android.content.Context
import com.wuxianggujun.tinaide.plugin.InstalledPlugin
import com.wuxianggujun.tinaide.plugin.PluginLogManager
import com.wuxianggujun.tinaide.plugin.PluginManager
import com.wuxianggujun.tinaide.core.i18n.Strings
import com.wuxianggujun.tinaide.core.i18n.strOr
import com.wuxianggujun.tinaide.plugin.script.api.LogApiModule
import com.wuxianggujun.tinaide.plugin.script.api.PluginApiRegistry
import com.wuxianggujun.tinaide.plugin.script.api.PluginCommandRegistry
import com.wuxianggujun.tinaide.plugin.script.api.PluginEventBus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import timber.log.Timber
import java.io.File

enum class ScriptPluginState {
    UNLOADED,
    LOADING,
    ACTIVE,
    ERROR,
    DISABLED
}

data class ScriptPluginInfo(
    val pluginId: String,
    val state: ScriptPluginState,
    val error: String? = null
)

class ScriptPluginManager private constructor(
    private val context: Context,
    private val pluginManager: PluginManager
) {
    companion object {
        private const val TAG = "ScriptPluginManager"
        private const val RELOAD_WAIT_TIMEOUT_MS = 15_000L

        @Volatile
        private var instance: ScriptPluginManager? = null

        fun getInstance(context: Context): ScriptPluginManager {
            return instance ?: synchronized(this) {
                instance ?: ScriptPluginManager(
                    context.applicationContext,
                    PluginManager.getInstance(context)
                ).also { instance = it }
            }
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val permissionManager = PluginPermissionManager.getInstance(context)
    private val logManager = PluginLogManager.getInstance(context)
    private var projectRootProvider: () -> String? = { null }
    private val apiRegistry by lazy {
        PluginApiRegistry.createDefaultRegistry(context, projectRootProvider).apply {
            registerModule("log") { LogApiModule(logManager) }
        }
    }
    
    fun setProjectRootProvider(provider: () -> String?) {
        this.projectRootProvider = provider
    }

    private val runtimes = mutableMapOf<String, ScriptPluginRuntime>()

    private val _pluginStates = MutableStateFlow<Map<String, ScriptPluginInfo>>(emptyMap())
    val pluginStates: StateFlow<Map<String, ScriptPluginInfo>> = _pluginStates.asStateFlow()

    init {
        PluginEventBus.setRuntimeProvider { pluginId -> runtimes[pluginId] }
        PluginCommandRegistry.setRuntimeProvider { pluginId -> runtimes[pluginId] }
        
        scope.launch {
            pluginManager.pluginStateFlow.collect { snapshot ->
                syncWithInstalledPlugins(snapshot.installedPlugins)
            }
        }
    }

    private fun syncWithInstalledPlugins(plugins: List<InstalledPlugin>) {
        val scriptPlugins = plugins.filter { it.manifest.type == "script" || it.manifest.type == "hybrid" }
        val pluginsById = scriptPlugins.associateBy { it.manifest.id }
        val currentIds = pluginsById.keys
        val loadedIds = runtimes.keys.toSet()

        // 禁用插件时也必须立刻卸载运行时，避免进入项目后仍继续响应事件。
        val toUnload = loadedIds.filter { pluginId ->
            val plugin = pluginsById[pluginId]
            plugin == null || !plugin.enabled
        }
        toUnload.forEach { pluginId ->
            val nextState = if (pluginsById[pluginId]?.enabled == false) {
                ScriptPluginState.DISABLED
            } else {
                ScriptPluginState.UNLOADED
            }
            val pluginName = pluginsById[pluginId]?.manifest?.name ?: pluginId
            logManager.info(
                pluginId,
                pluginName,
                "Plugin runtime unload requested: nextState=$nextState"
            )
            unloadPlugin(pluginId, nextState)
        }

        val currentStates = _pluginStates.value.toMutableMap()
        currentStates.keys.removeAll { it !in currentIds }

        scriptPlugins.forEach { plugin ->
            val existing = currentStates[plugin.manifest.id]
            val nextState = when {
                !plugin.enabled -> ScriptPluginState.DISABLED
                runtimes.containsKey(plugin.manifest.id) -> ScriptPluginState.ACTIVE
                existing?.state == ScriptPluginState.LOADING -> ScriptPluginState.LOADING
                existing?.state == ScriptPluginState.ERROR -> ScriptPluginState.ERROR
                else -> ScriptPluginState.UNLOADED
            }
            currentStates[plugin.manifest.id] = ScriptPluginInfo(
                pluginId = plugin.manifest.id,
                state = nextState,
                error = if (nextState == ScriptPluginState.ERROR) existing?.error else null
            )
        }
        _pluginStates.value = currentStates

        scriptPlugins.asSequence()
            .filter { it.enabled }
            .filter { !runtimes.containsKey(it.manifest.id) }
            .filter { currentStates[it.manifest.id]?.state == ScriptPluginState.UNLOADED }
            .forEach { plugin ->
                loadPlugin(plugin).onFailure { throwable ->
                    val message = throwable.message ?: "Unknown error"
                    updateState(plugin.manifest.id, ScriptPluginState.ERROR, message)
                    logManager.error(
                        plugin.manifest.id,
                        plugin.manifest.name,
                        "Auto load failed: $message",
                        throwable.stackTraceToString()
                    )
                }
            }
    }

    fun loadPlugin(plugin: InstalledPlugin): Result<Unit> {
        if (plugin.manifest.type != "script" && plugin.manifest.type != "hybrid") {
            return Result.failure(IllegalArgumentException("Not a script plugin"))
        }

        if (!plugin.enabled) {
            updateState(plugin.manifest.id, ScriptPluginState.DISABLED)
            return Result.failure(IllegalStateException("Plugin is disabled"))
        }

        if (runtimes.containsKey(plugin.manifest.id)) {
            return Result.success(Unit)
        }

        updateState(plugin.manifest.id, ScriptPluginState.LOADING)
        logManager.info(plugin.manifest.id, plugin.manifest.name, "Loading plugin...")

        return try {
            val runtime = ScriptPluginRuntime(
                context = context,
                manifest = plugin.manifest,
                pluginDir = plugin.directory,
                config = PluginSandboxConfig.DEFAULT,
                permissionManager = permissionManager
            )

            scope.launch {
                val result = runtime.initialize()
                if (result.isSuccess) {
                    runtimes[plugin.manifest.id] = runtime
                    val mainScriptFailure = loadMainScript(plugin, runtime)
                    if (mainScriptFailure == null) {
                        updateState(plugin.manifest.id, ScriptPluginState.ACTIVE)
                        logManager.info(plugin.manifest.id, plugin.manifest.name, "Plugin loaded successfully")
                    } else {
                        unloadPlugin(
                            pluginId = plugin.manifest.id,
                            nextState = ScriptPluginState.ERROR,
                            error = mainScriptFailure.message
                        )
                        logManager.error(
                            plugin.manifest.id,
                            plugin.manifest.name,
                            mainScriptFailure.message,
                            mainScriptFailure.stackTrace
                        )
                    }
                } else {
                    val errorMsg = result.exceptionOrNull()?.message ?: "Unknown error"
                    updateState(plugin.manifest.id, ScriptPluginState.ERROR, errorMsg)
                    logManager.error(plugin.manifest.id, plugin.manifest.name, "Failed to load plugin: $errorMsg")
                }
            }

            Result.success(Unit)
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Failed to load script plugin: ${plugin.manifest.id}")
            updateState(plugin.manifest.id, ScriptPluginState.ERROR, e.message)
            logManager.error(plugin.manifest.id, plugin.manifest.name, "Failed to load plugin: ${e.message}", e.stackTraceToString())
            Result.failure(e)
        }
    }

    private data class MainScriptLoadFailure(
        val message: String,
        val stackTrace: String? = null
    )

    private suspend fun loadMainScript(
        plugin: InstalledPlugin,
        runtime: ScriptPluginRuntime
    ): MainScriptLoadFailure? {
        initializeTinaApi(runtime, plugin)

        val mainEntry = plugin.manifest.main ?: "main.lua"
        val mainFile = File(plugin.directory, mainEntry)
        if (!mainFile.exists()) {
            val msg = Strings.plugin_error_main_file_not_exist.strOr(context, mainEntry)
            Timber.tag(TAG).w(msg)
            return MainScriptLoadFailure(msg)
        }

        logManager.debug(plugin.manifest.id, plugin.manifest.name, "Executing main script: $mainEntry")
        val script = mainFile.readText()
        val result = runtime.execute(script)

        return when (result) {
            is PluginExecutionResult.Success -> {
                Timber.tag(TAG).i("Main script loaded: ${plugin.manifest.id}")
                logManager.info(plugin.manifest.id, plugin.manifest.name, "Main script executed successfully")
                null
            }
            is PluginExecutionResult.Error -> {
                Timber.tag(TAG).e("Main script error: ${result.message}")
                MainScriptLoadFailure(
                    message = result.message,
                    stackTrace = result.stack
                )
            }
            is PluginExecutionResult.Timeout -> {
                val msg = Strings.plugin_error_main_script_timeout.strOr(context)
                Timber.tag(TAG).e(msg)
                MainScriptLoadFailure(msg)
            }
            is PluginExecutionResult.PermissionDenied -> {
                val msg = Strings.plugin_error_permission_denied_generic.strOr(context)
                Timber.tag(TAG).e("Permission denied for: ${plugin.manifest.id}")
                MainScriptLoadFailure(msg)
            }
        }
    }

    fun unloadPlugin(
        pluginId: String,
        nextState: ScriptPluginState = ScriptPluginState.UNLOADED,
        error: String? = null
    ) {
        val runtime = runtimes.remove(pluginId)
        PluginEventBus.unsubscribeAll(pluginId)
        PluginCommandRegistry.unregisterAll(pluginId)
        apiRegistry.cleanupRuntime(pluginId)
        runtime?.destroy()
        updateState(pluginId, nextState, error)
        Timber.tag(TAG).i("Plugin unloaded: $pluginId")
        val pluginName = pluginManager.getInstalledPlugin(pluginId)?.manifest?.name ?: pluginId
        logManager.info(pluginId, pluginName, "Plugin unloaded; nextState=$nextState")
    }

    fun getRuntime(pluginId: String): ScriptPluginRuntime? = runtimes[pluginId]

    suspend fun reloadPlugin(pluginId: String): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            pluginManager.refreshInstalledPlugins()
            val plugin = pluginManager.getInstalledPlugin(pluginId)
                ?: throw IllegalArgumentException("Plugin not found: $pluginId")
            if (plugin.manifest.type != "script" && plugin.manifest.type != "hybrid") {
                throw IllegalArgumentException("Reload is only supported for script plugins")
            }
            val nextState = if (plugin.enabled) {
                ScriptPluginState.UNLOADED
            } else {
                ScriptPluginState.DISABLED
            }
            unloadPlugin(pluginId, nextState)
            if (!plugin.enabled) {
                throw IllegalStateException("Plugin is disabled")
            }
            loadPlugin(plugin).getOrThrow()
            val finalState = awaitReloadTerminalState(pluginId)
            if (finalState.state != ScriptPluginState.ACTIVE) {
                throw IllegalStateException(
                    finalState.error?.takeIf { it.isNotBlank() }
                        ?: Strings.error_unknown.strOr(context)
                )
            }
        }
    }

    private suspend fun awaitReloadTerminalState(pluginId: String): ScriptPluginInfo {
        return try {
            pluginStates
                .map { states -> states[pluginId] }
                .filterNotNull()
                .firstWithinTimeout(RELOAD_WAIT_TIMEOUT_MS) { info ->
                    info.state != ScriptPluginState.LOADING &&
                        info.state != ScriptPluginState.UNLOADED
                }
        } catch (timeout: TimeoutCancellationException) {
            val timeoutMessage = Strings.plugin_error_reload_timeout.strOr(context)
            unloadPlugin(
                pluginId = pluginId,
                nextState = ScriptPluginState.ERROR,
                error = timeoutMessage,
            )
            throw IllegalStateException(timeoutMessage, timeout)
        }
    }

    private suspend fun <T> kotlinx.coroutines.flow.Flow<T>.firstWithinTimeout(
        timeoutMillis: Long,
        predicate: suspend (T) -> Boolean,
    ): T {
        return withTimeout(timeoutMillis) {
            first(predicate)
        }
    }

    suspend fun executeInPlugin(
        pluginId: String,
        functionName: String,
        vararg args: Any?
    ): PluginExecutionResult {
        val runtime = runtimes[pluginId]
            ?: return PluginExecutionResult.Error("Plugin not loaded: $pluginId")

        return runtime.callFunction(functionName, *args)
    }

    private fun updateState(pluginId: String, state: ScriptPluginState, error: String? = null) {
        val current = _pluginStates.value.toMutableMap()
        current[pluginId] = ScriptPluginInfo(pluginId, state, error)
        _pluginStates.value = current
    }

    fun getPermissionManager(): PluginPermissionManager = permissionManager

    private fun initializeTinaApi(runtime: ScriptPluginRuntime, plugin: InstalledPlugin) {
        val pluginId = plugin.manifest.id
        val pluginName = plugin.manifest.name

        apiRegistry.initializeForRuntime(runtime)

        runtime.registerFunction("print") { L ->
            val msg = L.toString(-1) ?: ""
            logManager.info(pluginId, pluginName, msg)
            0
        }
    }

    fun shutdown() {
        runtimes.values.forEach { it.destroy() }
        runtimes.clear()
        PluginEventBus.clear()
        PluginCommandRegistry.clear()
        apiRegistry.cleanup()
        _pluginStates.value = emptyMap()
        Timber.tag(TAG).i("ScriptPluginManager shutdown")
    }
}
