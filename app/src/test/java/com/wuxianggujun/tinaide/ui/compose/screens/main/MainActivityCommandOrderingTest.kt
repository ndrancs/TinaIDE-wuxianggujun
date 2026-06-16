package com.wuxianggujun.tinaide.ui.compose.screens.main

import android.app.Application
import com.google.common.truth.Truth.assertThat
import com.wuxianggujun.tinaide.core.commands.HostCommands
import com.wuxianggujun.tinaide.core.i18n.Strings
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
class MainActivityCommandOrderingTest {

    @Test
    fun `orderMainActivityCommands should put pinned commands before recent and category commands`() {
        val context = RuntimeEnvironment.getApplication()
        val commands = listOf(
            command("view.settings", MainActivityCommandCategory.VIEW),
            command("project.build", MainActivityCommandCategory.BUILD),
            command("editor.format", MainActivityCommandCategory.CODE),
        )

        val ordered = orderMainActivityCommands(
            commands = commands,
            context = context,
            pinnedCommandIds = listOf("view.settings"),
            recentCommandIds = listOf("project.build"),
            query = ""
        )

        assertThat(ordered.map(MainActivityCommand::id))
            .containsExactly("view.settings", "project.build", "editor.format")
            .inOrder()
    }

    @Test
    fun `orderMainActivityCommands should hide silent disabled commands and match plugin source name`() {
        val context = RuntimeEnvironment.getApplication()
        val commands = listOf(
            command("plugin.command", MainActivityCommandCategory.PLUGIN, sourceName = "Plugin Tools"),
            command("disabled.command", MainActivityCommandCategory.CODE, enabled = false),
        )

        val ordered = orderMainActivityCommands(
            commands = commands,
            context = context,
            pinnedCommandIds = emptyList(),
            recentCommandIds = emptyList(),
            query = "tools"
        )

        assertThat(ordered.map(MainActivityCommand::id))
            .containsExactly("plugin.command")
    }

    @Test
    fun `orderMainActivityCommands should keep unavailable commands with visible reason`() {
        val context = RuntimeEnvironment.getApplication()
        val commands = listOf(
            command(
                id = "plugin.unavailable",
                category = MainActivityCommandCategory.PLUGIN,
                enabled = false,
                disabledReason = MainActivityCommandText.Literal("Permission not granted"),
                sourceName = "Plugin Tools"
            ),
            command("disabled.command", MainActivityCommandCategory.CODE, enabled = false),
        )

        val ordered = orderMainActivityCommands(
            commands = commands,
            context = context,
            pinnedCommandIds = emptyList(),
            recentCommandIds = emptyList(),
            query = "permission"
        )

        assertThat(ordered.map(MainActivityCommand::id))
            .containsExactly("plugin.unavailable")
    }

    @Test
    fun `groupMainActivityCommands should split pinned recent and category commands`() {
        val commands = listOf(
            command("pinned", MainActivityCommandCategory.VIEW),
            command("recent", MainActivityCommandCategory.BUILD),
            command("code", MainActivityCommandCategory.CODE),
            command("build", MainActivityCommandCategory.BUILD),
        )

        val groups = groupMainActivityCommands(
            commands = commands,
            pinnedCommandIds = listOf("pinned"),
            recentCommandIds = listOf("recent")
        )

        assertThat(groups.map(MainActivityCommandGroup::titleRes))
            .containsExactly(
                Strings.command_palette_pinned,
                Strings.command_palette_quick_actions,
                Strings.menu_section_code,
                Strings.menu_section_build
            )
            .inOrder()
        assertThat(groups.map { group -> group.commands.map(MainActivityCommand::id) })
            .containsExactly(
                listOf("pinned"),
                listOf("recent"),
                listOf("code"),
                listOf("build")
            )
            .inOrder()
    }

    @Test
    fun `selectMainActivityOverflowCommands should use stable top-level menu order`() {
        val commands = listOf(
            command(HostCommands.VIEW_SETTINGS, MainActivityCommandCategory.VIEW),
            command("view.globalSearch", MainActivityCommandCategory.VIEW),
            command(HostCommands.PROJECT_CLOSE, MainActivityCommandCategory.FILE),
            command(HostCommands.VIEW_COMMAND_PALETTE, MainActivityCommandCategory.VIEW),
            command(HostCommands.VIEW_TOGGLE_TERMINAL, MainActivityCommandCategory.VIEW),
            command(HostCommands.PROJECT_BUILD, MainActivityCommandCategory.BUILD),
        )

        val overflowCommands = selectMainActivityOverflowCommands(commands)

        assertThat(overflowCommands.map(MainActivityCommand::id))
            .containsExactly(
                HostCommands.VIEW_COMMAND_PALETTE,
                "view.globalSearch",
                HostCommands.VIEW_TOGGLE_TERMINAL,
                HostCommands.VIEW_SETTINGS,
                HostCommands.PROJECT_CLOSE
            )
            .inOrder()
    }

    private fun command(
        id: String,
        category: MainActivityCommandCategory,
        enabled: Boolean = true,
        disabledReason: MainActivityCommandText? = null,
        sourceName: String? = null,
    ): MainActivityCommand = MainActivityCommand(
        id = id,
        title = MainActivityCommandText.Literal(id),
        category = category,
        enabled = enabled,
        disabledReason = disabledReason,
        sourceName = sourceName,
        execute = {}
    )
}
