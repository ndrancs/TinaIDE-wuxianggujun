package com.wuxianggujun.tinaide.plugin

import android.app.Application
import com.google.common.truth.Truth.assertThat
import com.wuxianggujun.tinaide.core.commands.HostCommands
import com.wuxianggujun.tinaide.core.serialization.JsonSerializer
import java.io.File
import java.nio.file.Files
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
class PluginDoctorTest {

    private lateinit var context: Application
    private lateinit var pluginDir: File

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        pluginDir = Files.createTempDirectory("plugin-doctor").toFile()
    }

    @After
    fun tearDown() {
        pluginDir.deleteRecursively()
    }

    @Test
    fun `inspectDirectory should accept script plugin custom command when declared and permission exists`() {
        writeMainLua()
        writeManifest(
            PluginManifest(
                id = "demo.script.command",
                name = "Demo Script Command",
                version = "1.0.0",
                type = PluginTypes.SCRIPT,
                permissions = listOf("command.execute", "editor.read", "editor.write"),
                main = "main.lua",
                contributions = PluginContributions(
                    commands = listOf(
                        PluginCommand(
                            id = "demo.script.command.insertHeader",
                            title = "Insert Header",
                        )
                    ),
                    menus = PluginMenus(
                        editorContext = listOf(
                            PluginMenuItem(command = "demo.script.command.insertHeader")
                        )
                    )
                )
            )
        )

        val report = PluginDoctor.inspectDirectory(context, pluginDir)

        assertThat(report.isInstalled).isFalse()
        assertThat(report.entries).isEmpty()
    }

    @Test
    fun `inspectDirectory should report missing manifest as load issue`() {
        val report = PluginDoctor.inspectDirectory(context, pluginDir)

        assertThat(report.isInstalled).isFalse()
        assertThat(report.entries).hasSize(1)
        assertThat(report.entries.single().source).isEqualTo(PluginDiagnosticSource.LOAD)
        assertThat(report.entries.single().issue.category).isEqualTo(PluginDiagnosticCategory.MANIFEST)
    }

    @Test
    fun `inspectDirectory should flag custom command on config plugin as unsupported`() {
        writeManifest(
            PluginManifest(
                id = "demo.config.command",
                name = "Demo Config Command",
                version = "1.0.0",
                type = PluginTypes.CONFIG,
                contributions = PluginContributions(
                    commands = listOf(
                        PluginCommand(
                            id = "demo.config.command.sayHello",
                            title = "Say Hello",
                        )
                    ),
                    menus = PluginMenus(
                        editorContext = listOf(
                            PluginMenuItem(command = "demo.config.command.sayHello")
                        )
                    )
                )
            )
        )

        val report = PluginDoctor.inspectDirectory(context, pluginDir)

        assertThat(report.entries).isNotEmpty()
        assertThat(
            report.entries.count { entry ->
                entry.issue.category == PluginDiagnosticCategory.COMPATIBILITY
            }
        ).isAtLeast(2)
        assertThat(
            report.entries.any { entry ->
                entry.issue.message.contains("demo.config.command.sayHello")
            }
        ).isTrue()
    }

    @Test
    fun `inspectDirectory should accept editor toolbar host command`() {
        writeManifest(
            PluginManifest(
                id = "demo.config.toolbar",
                name = "Demo Config Toolbar",
                version = "1.0.0",
                type = PluginTypes.CONFIG,
                contributions = PluginContributions(
                    menus = PluginMenus(
                        editorToolbar = listOf(
                            PluginMenuItem(command = HostCommands.EDITOR_SAVE)
                        )
                    )
                )
            )
        )

        val report = PluginDoctor.inspectDirectory(context, pluginDir)

        assertThat(report.entries).isEmpty()
    }

    @Test
    fun `inspectDirectory should accept keybinding host command`() {
        File(pluginDir, "keybindings.json").writeText(
            """
            [
              { "key": "Ctrl+Alt+S", "command": "${HostCommands.EDITOR_SAVE}", "when": "isDirty" }
            ]
            """.trimIndent()
        )
        writeManifest(
            PluginManifest(
                id = "demo.config.keybinding",
                name = "Demo Config Keybinding",
                version = "1.0.0",
                type = PluginTypes.CONFIG,
                contributions = PluginContributions(
                    keybindings = listOf("keybindings.json")
                )
            )
        )

        val report = PluginDoctor.inspectDirectory(context, pluginDir)

        assertThat(report.entries).isEmpty()
    }

    @Test
    fun `inspectDirectory should surface manifest requirements as info`() {
        writeManifest(
            PluginManifest(
                id = "demo.config.requires",
                name = "Demo Config Requires",
                version = "1.0.0",
                type = PluginTypes.CONFIG,
                requires = PluginRequirements(
                    toolchain = PluginToolchainRequirements(
                        recommended = listOf("clangd", " cmake ", ""),
                        optional = listOf("lldb"),
                    ),
                    packages = mapOf(
                        "proot" to listOf("python3", " nodejs ", ""),
                        " " to listOf("ignored"),
                    ),
                ),
            )
        )

        val report = PluginDoctor.inspectDirectory(context, pluginDir)

        assertThat(report.entries).hasSize(1)
        val entry = report.entries.single()
        assertThat(entry.issue.severity).isEqualTo(PluginDiagnosticSeverity.INFO)
        assertThat(entry.issue.category).isEqualTo(PluginDiagnosticCategory.MANIFEST)
        assertThat(entry.issue.message).contains("clangd")
        assertThat(entry.issue.message).contains("cmake")
        assertThat(entry.issue.message).contains("lldb")
        assertThat(entry.issue.message).contains("proot: nodejs, python3")
        assertThat(entry.issue.fixHint).isNotEmpty()
    }

    @Test
    fun `inspectDirectory should warn when script menu command is not declared and command permission missing`() {
        writeMainLua()
        writeManifest(
            PluginManifest(
                id = "demo.script.missing",
                name = "Demo Script Missing",
                version = "1.0.0",
                type = PluginTypes.SCRIPT,
                permissions = listOf("editor.read"),
                main = "main.lua",
                contributions = PluginContributions(
                    menus = PluginMenus(
                        editorContext = listOf(
                            PluginMenuItem(command = "demo.script.missing.insertHeader")
                        )
                    )
                )
            )
        )

        val report = PluginDoctor.inspectDirectory(context, pluginDir)

        assertThat(
            report.entries.count { entry ->
                entry.issue.category == PluginDiagnosticCategory.CONTRIBUTIONS
            }
        ).isAtLeast(1)
        assertThat(
            report.entries.count { entry ->
                entry.issue.category == PluginDiagnosticCategory.PERMISSIONS
            }
        ).isAtLeast(1)
        assertThat(
            report.entries.any { entry ->
                entry.issue.message.contains("demo.script.missing.insertHeader")
            }
        ).isTrue()
    }

    private fun writeManifest(manifest: PluginManifest) {
        JsonSerializer.encodeToFile(
            File(pluginDir, PluginManager.MANIFEST_FILE_NAME),
            manifest,
        )
    }

    private fun writeMainLua() {
        File(pluginDir, "main.lua").writeText("print('hello')")
    }
}
