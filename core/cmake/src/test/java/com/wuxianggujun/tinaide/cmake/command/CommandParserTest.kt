package com.wuxianggujun.tinaide.cmake.command

import com.google.common.truth.Truth.assertThat
import com.wuxianggujun.tinaide.cmake.parser.CMakeParser
import com.wuxianggujun.tinaide.cmake.parser.CommandInvocation
import org.junit.Test

class CommandParserTest {

    @Test
    fun parseFindPackage_shouldSplitVersionModesAndComponents() {
        val command = parseSingleCommand(
            "find_package(Qt6 6.5 EXACT REQUIRED COMPONENTS Core Widgets OPTIONAL_COMPONENTS Svg CONFIG)"
        )

        val parsed = CommandParser.parse(command) as FindPackageCommand

        assertThat(parsed.packageName).isEqualTo("Qt6")
        assertThat(parsed.version).isEqualTo("6.5")
        assertThat(parsed.isExact).isTrue()
        assertThat(parsed.isRequired).isTrue()
        assertThat(parsed.isConfig).isTrue()
        assertThat(parsed.components).containsExactly("Core", "Widgets").inOrder()
        assertThat(parsed.optionalComponents).containsExactly("Svg")
    }

    @Test
    fun parseTargetLinkLibraries_shouldCarryVisibilityUntilChanged() {
        val command = parseSingleCommand(
            "target_link_libraries(app PRIVATE core PUBLIC ui INTERFACE headers)"
        )

        val parsed = CommandParser.parse(command) as TargetLinkLibrariesCommand

        assertThat(parsed.target).isEqualTo("app")
        assertThat(parsed.libraries).containsExactly(
            TargetLinkLibrariesCommand.LibraryLink(
                "core",
                TargetLinkLibrariesCommand.Visibility.PRIVATE
            ),
            TargetLinkLibrariesCommand.LibraryLink(
                "ui",
                TargetLinkLibrariesCommand.Visibility.PUBLIC
            ),
            TargetLinkLibrariesCommand.LibraryLink(
                "headers",
                TargetLinkLibrariesCommand.Visibility.INTERFACE
            )
        ).inOrder()
    }

    @Test
    fun parseAddExecutableAlias_shouldSeparateAliasTargetFromSources() {
        val command = parseSingleCommand("add_executable(tool_alias ALIAS real_tool)")

        val parsed = CommandParser.parse(command) as AddExecutableCommand

        assertThat(parsed.targetName).isEqualTo("tool_alias")
        assertThat(parsed.isAlias).isTrue()
        assertThat(parsed.aliasTarget).isEqualTo("real_tool")
        assertThat(parsed.sources).isEmpty()
    }

    private fun parseSingleCommand(source: String): CommandInvocation = CMakeParser.parseCommands("$source\n").getOrThrow().single()
}
