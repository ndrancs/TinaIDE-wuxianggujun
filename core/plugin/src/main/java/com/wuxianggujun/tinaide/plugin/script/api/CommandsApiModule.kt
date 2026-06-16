package com.wuxianggujun.tinaide.plugin.script.api

import com.wuxianggujun.tinaide.core.commands.HostCommandInvocation
import com.wuxianggujun.tinaide.core.commands.HostCommands
import com.wuxianggujun.tinaide.plugin.script.PluginPermission
import com.wuxianggujun.tinaide.plugin.script.ScriptPluginRuntime
import java.io.File
import party.iroiro.luajava.Lua
import timber.log.Timber

class CommandsApiModule(
    private val projectRootProvider: () -> String? = { null }
) : PluginApiModule {
    override val namespace = "commands"

    private var runtime: ScriptPluginRuntime? = null

    override fun register(runtime: ScriptPluginRuntime, lua: Lua) {
        this.runtime = runtime
        val currentRuntime = runtime

        lua.push { L: Lua ->
            withPermission(currentRuntime, L, namespace, "register", PluginPermission.COMMAND_EXECUTE) {
                val commandId = normalizeCommandId(L.getStringArg(1))
                val callbackName = L.getStringArg(2)
                if (commandId == null || callbackName == null) {
                    return@withPermission L.pushSuccess(false, "Command ID and callback name are required")
                }

                val result = PluginCommandRegistry.register(
                    pluginId = currentRuntime.pluginId,
                    pluginName = currentRuntime.pluginName,
                    commandId = commandId,
                    callbackName = callbackName,
                    title = L.getStringArg(3)
                )
                if (result.isFailure) {
                    return@withPermission L.pushSuccess(
                        false,
                        result.exceptionOrNull()?.message ?: "Failed to register command"
                    )
                }
                L.push(true)
                1
            }
        }
        lua.setField(-2, "register")

        lua.push { L: Lua ->
            withPermission(currentRuntime, L, namespace, "unregister", PluginPermission.COMMAND_EXECUTE) {
                val commandId = normalizeCommandId(L.getStringArg(1))
                if (commandId == null) {
                    return@withPermission L.pushSuccess(false, "Command ID is required")
                }
                L.push(PluginCommandRegistry.unregister(currentRuntime.pluginId, commandId))
                1
            }
        }
        lua.setField(-2, "unregister")

        lua.push { L: Lua ->
            withPermission(currentRuntime, L, namespace, "execute", PluginPermission.COMMAND_EXECUTE) {
                val commandId = normalizeCommandId(L.getStringArg(1))
                if (commandId == null) {
                    return@withPermission L.pushSuccess(false, "Command ID is required")
                }
                val isHostCommand = HostCommands.isSupported(commandId)
                val isPluginCommand = PluginCommandRegistry.isRegistered(commandId)
                if (!isHostCommand && !isPluginCommand) {
                    return@withPermission L.pushSuccess(false, "Unsupported command: $commandId")
                }
                val targetPath = L.getStringArg(2)
                val targetFile = targetPath?.let(::resolveInvocationFile)
                if (targetPath != null && targetFile == null) {
                    return@withPermission L.pushSuccess(false, "Invalid command target: $targetPath")
                }
                val isDirectory = L.getBoolArg(3) ?: targetFile?.isDirectory
                val invocation = HostCommandInvocation(
                    file = targetFile,
                    isDirectory = isDirectory
                )
                Timber.d("Plugin ${currentRuntime.pluginId} executing command: $commandId")
                val handled = when {
                    isHostCommand -> {
                        val executor = PluginHostCommandExecutorHolder.get()
                            ?: return@withPermission L.pushSuccess(false, "Host command executor unavailable")
                        executor.execute(
                            commandId = commandId,
                            invocation = invocation
                        )
                    }
                    else -> {
                        PluginCommandRegistry.dispatch(
                            commandId = commandId,
                            invocation = invocation
                        )
                    }
                }
                L.push(handled)
                1
            }
        }
        lua.setField(-2, "execute")
    }

    override fun unregister() {
        runtime?.let { currentRuntime ->
            PluginCommandRegistry.unregisterAll(currentRuntime.pluginId)
        }
        runtime = null
    }

    internal fun normalizeCommandId(commandId: String?): String? = commandId?.trim()?.takeIf { it.isNotBlank() }

    internal fun resolveInvocationFile(path: String): File? {
        if (path.isBlank()) return null
        val projectRoot = projectRootProvider() ?: return null
        val rootFile = File(projectRoot)
        if (!rootFile.exists()) return null

        val normalizedPath = path.replace('\\', '/')
        val rawTarget = if (File(normalizedPath).isAbsolute) {
            File(normalizedPath)
        } else {
            File(rootFile, normalizedPath.removePrefix("/"))
        }

        val canonicalRoot = runCatching { rootFile.canonicalFile }.getOrNull() ?: return null
        val canonicalTarget = runCatching { rawTarget.canonicalFile }.getOrNull() ?: return null
        return canonicalTarget.takeIf {
            it == canonicalRoot || it.path.startsWith(canonicalRoot.path + File.separator)
        }
    }
}
