package com.wuxianggujun.tinaide.plugin.script

import android.app.Application
import com.google.common.truth.Truth.assertThat
import com.wuxianggujun.tinaide.plugin.InstalledPlugin
import com.wuxianggujun.tinaide.plugin.PluginManager
import com.wuxianggujun.tinaide.plugin.PluginManifest
import com.wuxianggujun.tinaide.plugin.PluginStateSnapshot
import com.wuxianggujun.tinaide.plugin.script.api.EventListener
import com.wuxianggujun.tinaide.plugin.script.api.PluginCommandRegistry
import com.wuxianggujun.tinaide.plugin.script.api.PluginEventBus
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import io.mockk.verify
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.flow.MutableStateFlow
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
class ScriptPluginManagerTest {

    private lateinit var context: Application
    private lateinit var pluginManager: PluginManager
    private lateinit var manager: ScriptPluginManager

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        PluginEventBus.clear()
        PluginCommandRegistry.clear()
        PluginCommandRegistry.setRuntimeProvider { null }
        pluginManager = mockk(relaxed = true)
        every { pluginManager.pluginStateFlow } returns MutableStateFlow(PluginStateSnapshot())
        manager = createManager(context, pluginManager)
    }

    @After
    fun tearDown() {
        runtimesOf(manager).clear()
        manager.shutdown()
        PluginEventBus.clear()
        PluginCommandRegistry.clear()
        PluginCommandRegistry.setRuntimeProvider { null }
    }

    @Test
    fun `syncWithInstalledPlugins should unload runtime and clear subscriptions when plugin becomes disabled`() {
        val pluginId = "sample.script.plugin"
        val runtime = mockk<ScriptPluginRuntime>(relaxed = true)
        every { runtime.destroy() } just runs

        runtimesOf(manager)[pluginId] = runtime
        PluginEventBus.subscribe(pluginId, "project.opened", "onProjectOpened")
        PluginCommandRegistry.register(
            pluginId = pluginId,
            pluginName = "Plugin $pluginId",
            commandId = "plugin.disabled.command",
            callbackName = "onDisabledCommand"
        ).getOrThrow()

        invokeSyncWithInstalledPlugins(
            manager,
            listOf(
                installedPlugin(
                    pluginId = pluginId,
                    type = "script",
                    enabled = false
                )
            )
        )

        assertThat(manager.getRuntime(pluginId)).isNull()
        assertThat(manager.pluginStates.value[pluginId]?.state).isEqualTo(ScriptPluginState.DISABLED)
        assertThat(listenersOfEventBus().values.flatten().any { it.pluginId == pluginId }).isFalse()
        assertThat(PluginCommandRegistry.isRegistered("plugin.disabled.command", pluginId)).isFalse()
        verify(exactly = 1) { runtime.destroy() }
    }

    @Test
    fun `syncWithInstalledPlugins should unload runtime and drop state when plugin is uninstalled`() {
        val pluginId = "sample.uninstalled.plugin"
        val runtime = mockk<ScriptPluginRuntime>(relaxed = true)
        every { runtime.destroy() } just runs

        runtimesOf(manager)[pluginId] = runtime
        statesOf(manager)[pluginId] = ScriptPluginInfo(
            pluginId = pluginId,
            state = ScriptPluginState.ACTIVE
        )
        PluginEventBus.subscribe(pluginId, "project.closed", "onProjectClosed")
        PluginCommandRegistry.register(
            pluginId = pluginId,
            pluginName = "Plugin $pluginId",
            commandId = "plugin.uninstalled.command",
            callbackName = "onUninstalledCommand"
        ).getOrThrow()

        invokeSyncWithInstalledPlugins(manager, emptyList())

        assertThat(manager.getRuntime(pluginId)).isNull()
        assertThat(manager.pluginStates.value).doesNotContainKey(pluginId)
        assertThat(listenersOfEventBus().values.flatten().any { it.pluginId == pluginId }).isFalse()
        assertThat(PluginCommandRegistry.isRegistered("plugin.uninstalled.command", pluginId)).isFalse()
        verify(exactly = 1) { runtime.destroy() }
    }

    @Test
    fun `loadPlugin should reject disabled plugin and keep runtime unloaded`() {
        val plugin = installedPlugin(
            pluginId = "sample.disabled.plugin",
            type = "script",
            enabled = false
        )

        val result = manager.loadPlugin(plugin)

        assertThat(result.isFailure).isTrue()
        assertThat(manager.getRuntime(plugin.manifest.id)).isNull()
        assertThat(manager.pluginStates.value[plugin.manifest.id]?.state)
            .isEqualTo(ScriptPluginState.DISABLED)
    }

    @Test
    fun `shutdown should clear runtimes commands event subscriptions and states`() {
        val pluginId = "sample.shutdown.plugin"
        val runtime = mockk<ScriptPluginRuntime>(relaxed = true)
        every { runtime.destroy() } just runs

        runtimesOf(manager)[pluginId] = runtime
        statesOf(manager)[pluginId] = ScriptPluginInfo(
            pluginId = pluginId,
            state = ScriptPluginState.ACTIVE
        )
        PluginEventBus.subscribe(pluginId, "project.opened", "onProjectOpened")
        PluginCommandRegistry.register(
            pluginId = pluginId,
            pluginName = "Plugin $pluginId",
            commandId = "plugin.shutdown.command",
            callbackName = "onShutdownCommand"
        ).getOrThrow()

        manager.shutdown()

        assertThat(manager.getRuntime(pluginId)).isNull()
        assertThat(manager.pluginStates.value).isEmpty()
        assertThat(listenersOfEventBus()).isEmpty()
        assertThat(PluginCommandRegistry.isRegistered("plugin.shutdown.command", pluginId)).isFalse()
        verify(exactly = 1) { runtime.destroy() }
    }

    private fun installedPlugin(
        pluginId: String,
        type: String,
        enabled: Boolean
    ): InstalledPlugin {
        val pluginDir = File(context.cacheDir, pluginId).apply { mkdirs() }
        return InstalledPlugin(
            manifest = PluginManifest(
                id = pluginId,
                name = "Plugin $pluginId",
                version = "1.0.0",
                type = type
            ),
            directory = pluginDir,
            enabled = enabled
        )
    }

    private fun createManager(
        context: Application,
        pluginManager: PluginManager
    ): ScriptPluginManager {
        val constructor = ScriptPluginManager::class.java.getDeclaredConstructor(
            android.content.Context::class.java,
            PluginManager::class.java
        )
        constructor.isAccessible = true
        return constructor.newInstance(context, pluginManager)
    }

    private fun invokeSyncWithInstalledPlugins(
        manager: ScriptPluginManager,
        plugins: List<InstalledPlugin>
    ) {
        val method = ScriptPluginManager::class.java.getDeclaredMethod(
            "syncWithInstalledPlugins",
            List::class.java
        )
        method.isAccessible = true
        method.invoke(manager, plugins)
    }

    @Suppress("UNCHECKED_CAST")
    private fun runtimesOf(manager: ScriptPluginManager): MutableMap<String, ScriptPluginRuntime> {
        val field = ScriptPluginManager::class.java.getDeclaredField("runtimes")
        field.isAccessible = true
        return field.get(manager) as MutableMap<String, ScriptPluginRuntime>
    }

    @Suppress("UNCHECKED_CAST")
    private fun statesOf(manager: ScriptPluginManager): MutableMap<String, ScriptPluginInfo> {
        val field = ScriptPluginManager::class.java.getDeclaredField("_pluginStates")
        field.isAccessible = true
        val stateFlow = field.get(manager) as MutableStateFlow<Map<String, ScriptPluginInfo>>
        return stateFlow.value.toMutableMap().also { stateFlow.value = it }
    }

    @Suppress("UNCHECKED_CAST")
    private fun listenersOfEventBus(): ConcurrentHashMap<String, CopyOnWriteArrayList<EventListener>> {
        val field = PluginEventBus::class.java.getDeclaredField("listeners")
        field.isAccessible = true
        return field.get(PluginEventBus) as ConcurrentHashMap<String, CopyOnWriteArrayList<EventListener>>
    }
}
