package com.wuxianggujun.tinaide.plugin.script.api

import com.wuxianggujun.tinaide.core.commands.HostCommandInvocation
import com.wuxianggujun.tinaide.core.commands.HostCommands
import com.wuxianggujun.tinaide.plugin.script.PluginExecutionResult
import com.wuxianggujun.tinaide.plugin.script.PluginPermission
import com.wuxianggujun.tinaide.plugin.script.ScriptPluginRuntime
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import timber.log.Timber

data class RegisteredPluginCommand(
    val pluginId: String,
    val pluginName: String,
    val commandId: String,
    val title: String?,
    val callbackName: String
)

data class PluginCommandRegistrationIssue(
    val pluginId: String,
    val pluginName: String,
    val commandId: String,
    val message: String
)

data class PluginCommandDispatchResult(
    val handled: Boolean,
    val errorMessage: String? = null
)

data class PluginCommandAvailability(
    val available: Boolean,
    val errorMessage: String? = null
)

object PluginCommandRegistry {
    private const val TAG = "PluginCommandRegistry"
    private const val COMMANDS_API_NAMESPACE = "commands"
    private const val COMMANDS_EXECUTE_METHOD = "execute"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val commandsById = ConcurrentHashMap<String, RegisteredPluginCommand>()
    private val registrationIssuesByPluginId =
        ConcurrentHashMap<String, ConcurrentHashMap<String, PluginCommandRegistrationIssue>>()

    @Volatile
    private var runtimeProvider: ((String) -> ScriptPluginRuntime?)? = null

    fun setRuntimeProvider(provider: (String) -> ScriptPluginRuntime?) {
        runtimeProvider = provider
    }

    fun register(
        pluginId: String,
        pluginName: String,
        commandId: String,
        callbackName: String,
        title: String? = null
    ): Result<RegisteredPluginCommand> {
        val normalizedPluginId = pluginId.trim()
        val normalizedPluginName = pluginName.trim().takeIf { it.isNotBlank() } ?: normalizedPluginId
        val normalizedCommandId = commandId.trim()
        val result = runCatching {
            require(normalizedPluginId.isNotBlank()) { "Plugin ID is required" }
            require(normalizedCommandId.isNotBlank()) { "Command ID is required" }

            val normalizedCallbackName = callbackName.trim()
            require(normalizedCallbackName.isNotBlank()) { "Callback name is required" }

            require(!HostCommands.isSupported(normalizedCommandId)) {
                "Command ID conflicts with host command: $normalizedCommandId"
            }

            val command = RegisteredPluginCommand(
                pluginId = normalizedPluginId,
                pluginName = normalizedPluginName,
                commandId = normalizedCommandId,
                title = title?.trim()?.takeIf { it.isNotBlank() },
                callbackName = normalizedCallbackName
            )

            val existing = commandsById[normalizedCommandId]
            require(existing == null || existing.pluginId == normalizedPluginId) {
                "Command ID already registered by plugin ${existing?.pluginId}: $normalizedCommandId"
            }

            commandsById[normalizedCommandId] = command
            Timber.tag(TAG).d(
                "Registered plugin command: commandId=%s pluginId=%s callback=%s",
                normalizedCommandId,
                normalizedPluginId,
                normalizedCallbackName
            )
            command
        }

        result.onSuccess {
            clearRegistrationIssue(normalizedPluginId, normalizedCommandId)
        }.onFailure { throwable ->
            recordRegistrationIssue(
                pluginId = normalizedPluginId,
                pluginName = normalizedPluginName,
                commandId = normalizedCommandId,
                message = throwable.message ?: "Failed to register command",
            )
        }
        return result
    }

    fun unregister(pluginId: String, commandId: String): Boolean {
        val normalizedPluginId = pluginId.trim()
        val normalizedCommandId = commandId.trim()
        if (normalizedCommandId.isBlank()) return false

        clearRegistrationIssue(normalizedPluginId, normalizedCommandId)
        val existing = commandsById[normalizedCommandId] ?: return false
        if (existing.pluginId != normalizedPluginId) return false

        return commandsById.remove(normalizedCommandId, existing).also { removed ->
            if (removed) {
                Timber.tag(TAG).d(
                    "Unregistered plugin command: commandId=%s pluginId=%s",
                    normalizedCommandId,
                    normalizedPluginId
                )
            }
        }
    }

    fun unregisterAll(pluginId: String) {
        val normalizedPluginId = pluginId.trim()
        commandsById.entries.removeIf { (_, command) -> command.pluginId == normalizedPluginId }
        registrationIssuesByPluginId.remove(normalizedPluginId)
        Timber.tag(TAG).d("Unregistered all commands for plugin: %s", normalizedPluginId)
    }

    fun clear() {
        commandsById.clear()
        registrationIssuesByPluginId.clear()
    }

    fun isRegistered(commandId: String, pluginId: String? = null): Boolean {
        val command = commandsById[commandId.trim()] ?: return false
        return pluginId == null || command.pluginId == pluginId
    }

    fun registrationIssue(commandId: String, pluginId: String): PluginCommandRegistrationIssue? {
        val normalizedPluginId = pluginId.trim()
        val normalizedCommandId = commandId.trim()
        if (normalizedPluginId.isBlank() || normalizedCommandId.isBlank()) return null
        return registrationIssuesByPluginId[normalizedPluginId]?.get(normalizedCommandId)
    }

    fun titleFor(commandId: String, pluginId: String? = null): String? {
        val command = commandsById[commandId.trim()] ?: return null
        if (pluginId != null && command.pluginId != pluginId) return null
        return command.title
    }

    fun availability(commandId: String, pluginId: String? = null): PluginCommandAvailability {
        val command = commandsById[commandId.trim()] ?: return PluginCommandAvailability(available = false)
        if (pluginId != null && command.pluginId != pluginId) {
            return PluginCommandAvailability(available = false)
        }

        val runtime = runtimeProvider?.invoke(command.pluginId)
            ?: return PluginCommandAvailability(available = false)
        if (runtime.checkPermission(PluginPermission.COMMAND_EXECUTE)) {
            return PluginCommandAvailability(available = true)
        }

        return PluginCommandAvailability(
            available = false,
            errorMessage = runtime.describePermissionDenial(PluginPermission.COMMAND_EXECUTE)
        )
    }

    fun dispatch(
        commandId: String,
        invocation: HostCommandInvocation = HostCommandInvocation()
    ): Boolean = dispatchWithResult(commandId, invocation).handled

    fun dispatchWithResult(
        commandId: String,
        invocation: HostCommandInvocation = HostCommandInvocation()
    ): PluginCommandDispatchResult {
        val command = commandsById[commandId.trim()] ?: return PluginCommandDispatchResult(handled = false)
        val runtime = runtimeProvider?.invoke(command.pluginId) ?: return PluginCommandDispatchResult(handled = false)
        if (!runtime.checkPermission(PluginPermission.COMMAND_EXECUTE)) {
            val errorMessage = runtime.reportPermissionDenied(
                COMMANDS_API_NAMESPACE,
                COMMANDS_EXECUTE_METHOD,
                PluginPermission.COMMAND_EXECUTE
            )
            Timber.tag(TAG).w(
                "Plugin command permission denied before dispatch: commandId=%s pluginId=%s",
                command.commandId,
                command.pluginId
            )
            return PluginCommandDispatchResult(
                handled = false,
                errorMessage = errorMessage
            )
        }

        val payload = buildInvocationPayload(command.commandId, invocation)

        scope.launch {
            when (val result = runtime.callFunction(command.callbackName, payload)) {
                is PluginExecutionResult.Success -> {
                    Timber.tag(TAG).d(
                        "Plugin command dispatched: commandId=%s pluginId=%s",
                        command.commandId,
                        command.pluginId
                    )
                }
                is PluginExecutionResult.Error -> {
                    Timber.tag(TAG).e(
                        "Plugin command failed: commandId=%s pluginId=%s message=%s",
                        command.commandId,
                        command.pluginId,
                        result.message
                    )
                }
                PluginExecutionResult.Timeout -> {
                    Timber.tag(TAG).w(
                        "Plugin command timed out: commandId=%s pluginId=%s",
                        command.commandId,
                        command.pluginId
                    )
                }
                PluginExecutionResult.PermissionDenied -> {
                    Timber.tag(TAG).w(
                        "Plugin command permission denied: commandId=%s pluginId=%s",
                        command.commandId,
                        command.pluginId
                    )
                }
            }
        }
        return PluginCommandDispatchResult(handled = true)
    }

    private fun buildInvocationPayload(
        commandId: String,
        invocation: HostCommandInvocation
    ): Map<String, Any?> {
        val file = invocation.file
        return mapOf(
            "commandId" to commandId,
            "filePath" to file?.absolutePath,
            "fileName" to file?.name,
            "isDirectory" to (invocation.isDirectory ?: file?.isDirectory ?: false),
            "isDirty" to (invocation.isDirty ?: false)
        )
    }

    private fun recordRegistrationIssue(
        pluginId: String,
        pluginName: String,
        commandId: String,
        message: String,
    ) {
        if (pluginId.isBlank() || commandId.isBlank()) return
        val issue = PluginCommandRegistrationIssue(
            pluginId = pluginId,
            pluginName = pluginName,
            commandId = commandId,
            message = message,
        )
        registrationIssuesByPluginId.computeIfAbsent(pluginId) {
            ConcurrentHashMap()
        }[commandId] = issue
        Timber.tag(TAG).w(
            "Plugin command registration failed: commandId=%s pluginId=%s message=%s",
            commandId,
            pluginId,
            message
        )
    }

    private fun clearRegistrationIssue(pluginId: String, commandId: String) {
        if (pluginId.isBlank() || commandId.isBlank()) return
        val issues = registrationIssuesByPluginId[pluginId] ?: return
        issues.remove(commandId)
        if (issues.isEmpty()) {
            registrationIssuesByPluginId.remove(pluginId, issues)
        }
    }
}
