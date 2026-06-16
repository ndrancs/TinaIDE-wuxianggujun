package com.wuxianggujun.tinaide.plugin.script.api

import android.app.Application
import com.google.common.truth.Truth.assertThat
import com.wuxianggujun.tinaide.core.commands.HostCommandInvocation
import com.wuxianggujun.tinaide.plugin.PluginLogEventCodes
import com.wuxianggujun.tinaide.plugin.PluginLogEventKeys
import com.wuxianggujun.tinaide.plugin.PluginLogLevel
import com.wuxianggujun.tinaide.plugin.PluginLogManager
import com.wuxianggujun.tinaide.plugin.PluginManifest
import com.wuxianggujun.tinaide.plugin.script.PluginPermission
import com.wuxianggujun.tinaide.plugin.script.PluginPermissionManager
import com.wuxianggujun.tinaide.plugin.script.ScriptPluginRuntime
import java.io.File
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(
    sdk = [34],
    manifest = Config.NONE,
    application = Application::class
)
class PluginCommandRegistryPermissionTest {

    private lateinit var context: Application
    private lateinit var permissionManager: PluginPermissionManager
    private lateinit var logManager: PluginLogManager

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        permissionManager = PluginPermissionManager.getInstance(context)
        logManager = PluginLogManager.getInstance(context)
        PluginCommandRegistry.clear()
        PluginCommandRegistry.setRuntimeProvider { null }
        logManager.clearAll()
    }

    @After
    fun tearDown() {
        PluginCommandRegistry.clear()
        PluginCommandRegistry.setRuntimeProvider { null }
        permissionManager.revokeAllPermissions(UNDECLARED_PLUGIN_ID)
        permissionManager.revokeAllPermissions(NOT_GRANTED_PLUGIN_ID)
        logManager.clearAll()
    }

    @Test
    fun `dispatch should log not declared when plugin command permission is missing from manifest`() {
        val runtime = createRuntime(
            pluginId = UNDECLARED_PLUGIN_ID,
            permissions = emptyList()
        )
        registerCommandForRuntime(runtime)

        val dispatched = PluginCommandRegistry.dispatch(
            commandId = COMMAND_ID,
            invocation = HostCommandInvocation()
        )

        assertThat(dispatched).isFalse()
        assertPermissionDeniedLog(
            pluginId = UNDECLARED_PLUGIN_ID,
            denialReason = "NOT_DECLARED"
        )
    }

    @Test
    fun `dispatch should log not granted when plugin command permission is declared but not granted`() {
        val runtime = createRuntime(
            pluginId = NOT_GRANTED_PLUGIN_ID,
            permissions = listOf(PluginPermission.COMMAND_EXECUTE.id)
        )
        registerCommandForRuntime(runtime)

        val dispatched = PluginCommandRegistry.dispatch(
            commandId = COMMAND_ID,
            invocation = HostCommandInvocation()
        )

        assertThat(dispatched).isFalse()
        assertPermissionDeniedLog(
            pluginId = NOT_GRANTED_PLUGIN_ID,
            denialReason = "NOT_GRANTED"
        )
    }

    private fun createRuntime(
        pluginId: String,
        permissions: List<String>
    ): ScriptPluginRuntime = ScriptPluginRuntime(
        context = context,
        manifest = PluginManifest(
            id = pluginId,
            name = "Command Permission Test",
            version = "1.0.0",
            type = "script",
            permissions = permissions
        ),
        pluginDir = File(context.cacheDir, pluginId).apply { mkdirs() },
        permissionManager = permissionManager
    )

    private fun registerCommandForRuntime(runtime: ScriptPluginRuntime) {
        PluginCommandRegistry.setRuntimeProvider { pluginId ->
            if (pluginId == runtime.pluginId) runtime else null
        }
        PluginCommandRegistry.register(
            pluginId = runtime.pluginId,
            pluginName = runtime.pluginName,
            commandId = COMMAND_ID,
            callbackName = "handleCommand"
        ).getOrThrow()
    }

    private fun assertPermissionDeniedLog(
        pluginId: String,
        denialReason: String
    ) {
        val entry = logManager.getLogsForPlugin(pluginId).single()
        assertThat(entry.level).isEqualTo(PluginLogLevel.WARN)
        assertThat(entry.eventCode).isEqualTo(PluginLogEventCodes.PERMISSION_DENIED)
        assertThat(entry.attributes[PluginLogEventKeys.API_NAMESPACE]).isEqualTo("commands")
        assertThat(entry.attributes[PluginLogEventKeys.API_METHOD]).isEqualTo("execute")
        assertThat(entry.attributes[PluginLogEventKeys.PERMISSION_ID])
            .isEqualTo(PluginPermission.COMMAND_EXECUTE.id)
        assertThat(entry.attributes[PluginLogEventKeys.DENIAL_REASON]).isEqualTo(denialReason)
        assertThat(entry.message).contains("tina.commands.execute")
        assertThat(entry.message).contains(PluginPermission.COMMAND_EXECUTE.id)
    }

    private companion object {
        const val UNDECLARED_PLUGIN_ID = "plugin.command.permission.undeclared"
        const val NOT_GRANTED_PLUGIN_ID = "plugin.command.permission.not-granted"
        const val COMMAND_ID = "plugin.permission.test"
    }
}
