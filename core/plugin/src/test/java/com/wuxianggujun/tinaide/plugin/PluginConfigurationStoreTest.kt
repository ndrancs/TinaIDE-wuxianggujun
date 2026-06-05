package com.wuxianggujun.tinaide.plugin

import android.app.Application
import android.content.Context
import com.google.common.truth.Truth.assertThat
import com.wuxianggujun.tinaide.plugin.script.PluginExecutionResult
import com.wuxianggujun.tinaide.plugin.script.ScriptPluginRuntime
import com.wuxianggujun.tinaide.plugin.script.api.PluginEvent
import com.wuxianggujun.tinaide.plugin.script.api.PluginEventBus
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.serialization.json.JsonPrimitive
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
    application = Application::class,
)
class PluginConfigurationStoreTest {

    private lateinit var context: Application
    private lateinit var store: PluginConfigurationStore

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences("tina_plugin_configuration", Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
        store = PluginConfigurationStore.getInstance(context)
        PluginEventBus.clear()
        PluginEventBus.setRuntimeProvider { null }
    }

    @After
    fun tearDown() {
        PluginEventBus.clear()
        PluginEventBus.setRuntimeProvider { null }
    }

    @Test
    fun `configuration store should isolate values by plugin id and fall back to defaults`() {
        val firstManifest = manifest(
            id = "demo.first",
            defaultEnabled = false,
        )
        val secondManifest = manifest(
            id = "demo.second",
            defaultEnabled = false,
        )

        assertThat(store.getValue(firstManifest, "feature.enabled"))
            .isEqualTo(JsonPrimitive(false))
        assertThat(store.getValue(secondManifest, "feature.enabled"))
            .isEqualTo(JsonPrimitive(false))

        assertThat(store.setValue(firstManifest, "feature.enabled", JsonPrimitive(true))).isTrue()

        assertThat(store.getValue(firstManifest, "feature.enabled"))
            .isEqualTo(JsonPrimitive(true))
        assertThat(store.getValue(secondManifest, "feature.enabled"))
            .isEqualTo(JsonPrimitive(false))

        assertThat(store.resetValue(firstManifest, "feature.enabled")).isTrue()
        assertThat(store.getValue(firstManifest, "feature.enabled"))
            .isEqualTo(JsonPrimitive(false))
    }

    @Test
    fun `configuration store should reject undeclared keys and invalid values`() {
        val manifest = manifest(
            id = "demo.validation",
            defaultEnabled = false,
        )

        assertThat(store.setValue(manifest, "missing.key", JsonPrimitive(true))).isFalse()
        assertThat(store.setValue(manifest, "feature.enabled", JsonPrimitive("true"))).isFalse()
        assertThat(store.setValue(manifest, "output.format", JsonPrimitive("xml"))).isFalse()
        assertThat(store.setValue(manifest, "output.format", JsonPrimitive("json"))).isTrue()

        assertThat(store.getValue(manifest, "output.format")).isEqualTo(JsonPrimitive("json"))
    }

    @Test
    fun `configuration store should emit targeted config changed events`() {
        val manifest = manifest(
            id = "demo.events",
            defaultEnabled = false,
        )
        val runtime = mockk<ScriptPluginRuntime>()
        val otherRuntime = mockk<ScriptPluginRuntime>()
        coEvery { runtime.callFunction("onConfigChanged", any()) } returns PluginExecutionResult.Success(Unit)
        coEvery { otherRuntime.callFunction("onConfigChanged", any()) } returns PluginExecutionResult.Success(Unit)
        PluginEventBus.setRuntimeProvider { pluginId ->
            when (pluginId) {
                manifest.id -> runtime
                "demo.other" -> otherRuntime
                else -> null
            }
        }
        PluginEventBus.subscribe(manifest.id, PluginEvent.CONFIG_CHANGED.id, "onConfigChanged")
        PluginEventBus.subscribe("demo.other", PluginEvent.CONFIG_CHANGED.id, "onConfigChanged")

        assertThat(store.setValue(manifest, "feature.enabled", JsonPrimitive(true))).isTrue()

        coVerify(timeout = 1_000, exactly = 1) {
            runtime.callFunction(
                "onConfigChanged",
                match<Map<String, Any?>> { payload ->
                    payload["pluginId"] == manifest.id &&
                        payload["key"] == "feature.enabled" &&
                        payload["value"] == true &&
                        payload["previousValue"] == false
                }
            )
        }
        coVerify(exactly = 0) {
            otherRuntime.callFunction("onConfigChanged", any())
        }
    }

    private fun manifest(
        id: String,
        defaultEnabled: Boolean,
    ): PluginManifest = PluginManifest(
        id = id,
        name = "Configuration Test",
        version = "1.0.0",
        configuration = PluginConfiguration(
            title = "Configuration",
            properties = mapOf(
                "feature.enabled" to PluginConfigurationProperty(
                    type = "boolean",
                    default = JsonPrimitive(defaultEnabled),
                ),
                "output.format" to PluginConfigurationProperty(
                    type = "string",
                    default = JsonPrimitive("text"),
                    enumValues = listOf("text", "json"),
                ),
            ),
        ),
    )
}
