package com.wuxianggujun.tinaide.plugin

import android.app.Application
import android.content.Context
import com.google.common.truth.Truth.assertThat
import java.io.File
import kotlinx.coroutines.runBlocking
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
class PluginManagerDefaultEnabledTest {

    private lateinit var context: Application
    private lateinit var pluginsDir: File

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        pluginsDir = File(context.filesDir, "plugins")
        pluginsDir.deleteRecursively()
        pluginsDir.mkdirs()
        context.getSharedPreferences("tinaide_plugins", Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
    }

    @After
    fun tearDown() {
        pluginsDir.deleteRecursively()
        context.getSharedPreferences("tinaide_plugins", Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
    }

    @Test
    fun refreshInstalledPlugins_shouldUseCurrentDefaultEnabledRules() = runBlocking {
        writePluginManifest(pluginId = "tinaide.system.default", type = PluginTypes.SYSTEM)
        writePluginManifest(pluginId = "tinaide.config.default", type = PluginTypes.CONFIG)

        val pluginManager = PluginManager(context)
        pluginManager.refreshInstalledPlugins()

        assertThat(pluginManager.isPluginEnabled("tinaide.system.default")).isFalse()
        assertThat(pluginManager.getInstalledPlugin("tinaide.system.default")?.enabled).isFalse()
        assertThat(pluginManager.isPluginEnabled("tinaide.config.default")).isTrue()
        assertThat(pluginManager.getInstalledPlugin("tinaide.config.default")?.enabled).isTrue()
    }

    private fun writePluginManifest(pluginId: String, type: String) {
        val pluginDir = File(pluginsDir, pluginId).apply { mkdirs() }
        File(pluginDir, PluginManager.MANIFEST_FILE_NAME).writeText(
            """
            {
              "id": "$pluginId",
              "name": "$pluginId",
              "version": "1.0.0",
              "type": "$type"
            }
            """.trimIndent()
        )
    }
}
