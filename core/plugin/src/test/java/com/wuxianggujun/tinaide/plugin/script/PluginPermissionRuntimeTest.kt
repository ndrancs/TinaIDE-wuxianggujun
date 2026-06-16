package com.wuxianggujun.tinaide.plugin.script

import android.app.Application
import com.google.common.truth.Truth.assertThat
import com.wuxianggujun.tinaide.core.i18n.Strings
import com.wuxianggujun.tinaide.core.i18n.strOr
import com.wuxianggujun.tinaide.plugin.PluginLogEventCodes
import com.wuxianggujun.tinaide.plugin.PluginLogEventKeys
import com.wuxianggujun.tinaide.plugin.PluginLogLevel
import com.wuxianggujun.tinaide.plugin.PluginLogManager
import com.wuxianggujun.tinaide.plugin.PluginManifest
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
class PluginPermissionRuntimeTest {

    private lateinit var context: Application
    private lateinit var permissionManager: PluginPermissionManager
    private lateinit var logManager: PluginLogManager

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        permissionManager = PluginPermissionManager.getInstance(context)
        logManager = PluginLogManager.getInstance(context)
        logManager.clearAll()
    }

    @After
    fun tearDown() {
        permissionManager.revokeAllPermissions("test.runtime.permissions")
        permissionManager.revokeAllPermissions("test.runtime.aliases")
        permissionManager.revokeAllPermissions("test.runtime.permission.log")
        permissionManager.revokeAllPermissions("test.runtime.network.alt")
        logManager.clearAll()
    }

    @Test
    fun `parseList should support workspace and commands aliases`() {
        val permissions = PluginPermission.parseList(
            listOf("workspace.read", "workspace.write", "commands.execute", "diagnostics.read")
        )

        assertThat(permissions).containsExactly(
            PluginPermission.FILE_READ,
            PluginPermission.FILE_WRITE,
            PluginPermission.COMMAND_EXECUTE,
            PluginPermission.DIAGNOSTICS_READ
        )
        assertThat(PluginPermission.COMMAND_EXECUTE.level)
            .isEqualTo(PermissionLevel.L2_MEDIUM_RISK)
    }

    @Test
    fun `checkPermission should require manifest declaration before grant`() {
        val pluginId = "test.runtime.permissions"
        permissionManager.grantPermission(pluginId, PluginPermission.FILE_READ)

        val runtime = ScriptPluginRuntime(
            context = context,
            manifest = PluginManifest(
                id = pluginId,
                name = "Runtime Test",
                version = "1.0.0",
                type = "script",
                permissions = emptyList()
            ),
            pluginDir = File(context.cacheDir, pluginId).apply { mkdirs() },
            permissionManager = permissionManager
        )

        assertThat(runtime.checkPermission(PluginPermission.FILE_READ)).isFalse()
    }

    @Test
    fun `checkPermission should allow alias-declared permission after grant`() {
        val pluginId = "test.runtime.aliases"
        permissionManager.grantPermission(pluginId, PluginPermission.FILE_READ)

        val runtime = ScriptPluginRuntime(
            context = context,
            manifest = PluginManifest(
                id = pluginId,
                name = "Alias Test",
                version = "1.0.0",
                type = "script",
                permissions = listOf("workspace.read")
            ),
            pluginDir = File(context.cacheDir, pluginId).apply { mkdirs() },
            permissionManager = permissionManager
        )

        assertThat(runtime.checkPermission(PluginPermission.FILE_READ)).isTrue()
        assertThat(runtime.getDeclaredPermissions()).contains(PluginPermission.FILE_READ)
    }

    @Test
    fun `getAllowedHosts should normalize and drop invalid declarations`() {
        val runtime = ScriptPluginRuntime(
            context = context,
            manifest = PluginManifest(
                id = "test.runtime.permissions",
                name = "Host Rules Test",
                version = "1.0.0",
                type = "script",
                permissions = listOf("network.fetch"),
                networkHosts = listOf(
                    "API.example.com",
                    "https://bad.example.com",
                    "api.example.com"
                )
            ),
            pluginDir = File(context.cacheDir, "test.runtime.permissions").apply { mkdirs() },
            permissionManager = permissionManager
        )

        assertThat(runtime.getAllowedHosts()).containsExactly("api.example.com")
    }

    @Test
    fun `reportPermissionDenied should log undeclared permission with api context`() {
        val pluginId = "test.runtime.permission.log"
        val runtime = ScriptPluginRuntime(
            context = context,
            manifest = PluginManifest(
                id = pluginId,
                name = "Permission Log Test",
                version = "1.0.0",
                type = "script",
                permissions = emptyList()
            ),
            pluginDir = File(context.cacheDir, pluginId).apply { mkdirs() },
            permissionManager = permissionManager
        )

        val message = runtime.reportPermissionDenied("fs", "readFile", PluginPermission.FILE_READ)
        val entry = logManager.getLogsForPlugin(pluginId).single()

        assertThat(message).isEqualTo(
            Strings.plugin_error_permission_not_declared.strOr(context, PluginPermission.FILE_READ.id)
        )
        assertThat(entry.level).isEqualTo(PluginLogLevel.WARN)
        assertThat(entry.eventCode).isEqualTo(PluginLogEventCodes.PERMISSION_DENIED)
        assertThat(entry.attributes[PluginLogEventKeys.API_NAMESPACE]).isEqualTo("fs")
        assertThat(entry.attributes[PluginLogEventKeys.API_METHOD]).isEqualTo("readFile")
        assertThat(entry.attributes[PluginLogEventKeys.PERMISSION_ID]).isEqualTo("file.read")
        assertThat(entry.attributes[PluginLogEventKeys.DENIAL_REASON]).isEqualTo("NOT_DECLARED")
        assertThat(entry.message).contains("tina.fs.readFile")
        assertThat(entry.message).contains(PluginPermission.FILE_READ.id)
    }

    @Test
    fun `reportPermissionDenied should prefer declared alternative permission when not granted`() {
        val pluginId = "test.runtime.network.alt"
        val runtime = ScriptPluginRuntime(
            context = context,
            manifest = PluginManifest(
                id = pluginId,
                name = "Network Permission Test",
                version = "1.0.0",
                type = "script",
                permissions = listOf("network.unrestricted")
            ),
            pluginDir = File(context.cacheDir, pluginId).apply { mkdirs() },
            permissionManager = permissionManager
        )

        val message = runtime.reportPermissionDenied(
            "network",
            "fetch",
            PluginPermission.NETWORK_FETCH,
            PluginPermission.NETWORK_UNRESTRICTED
        )
        val entry = logManager.getLogsForPlugin(pluginId).single()

        assertThat(message).isEqualTo(
            Strings.plugin_error_permission_not_granted.strOr(
                context,
                PluginPermission.NETWORK_UNRESTRICTED.id
            )
        )
        assertThat(entry.level).isEqualTo(PluginLogLevel.WARN)
        assertThat(entry.eventCode).isEqualTo(PluginLogEventCodes.PERMISSION_DENIED)
        assertThat(entry.attributes[PluginLogEventKeys.API_NAMESPACE]).isEqualTo("network")
        assertThat(entry.attributes[PluginLogEventKeys.API_METHOD]).isEqualTo("fetch")
        assertThat(entry.attributes[PluginLogEventKeys.PERMISSION_ID]).isEqualTo(
            PluginPermission.NETWORK_UNRESTRICTED.id
        )
        assertThat(entry.attributes[PluginLogEventKeys.DENIAL_REASON]).isEqualTo("NOT_GRANTED")
        assertThat(entry.message).contains("tina.network.fetch")
        assertThat(entry.message).contains(PluginPermission.NETWORK_UNRESTRICTED.id)
    }
}
