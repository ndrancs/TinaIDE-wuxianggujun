package com.wuxianggujun.tinaide.core.commands

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import com.wuxianggujun.tinaide.core.i18n.Strings
import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Test

class HostCommandsTest {

    @Test
    fun `isSupported should trim command id`() {
        assertThat(HostCommands.isSupported(" ${HostCommands.EDITOR_SAVE} ")).isTrue()
        assertThat(HostCommands.isSupported(" editor.notFound ")).isFalse()
    }

    @Test
    fun `titleResOrNull should trim command id`() {
        assertThat(HostCommands.titleResOrNull(" ${HostCommands.EDITOR_SAVE} "))
            .isEqualTo(Strings.cmd_editor_save)
    }

    @Test
    fun `catalog should define unique command ids`() {
        val ids = HostCommandCatalog.descriptors.map(HostCommandDescriptor::id)

        assertThat(ids).containsNoDuplicates()
        assertThat(HostCommands.getAllCommandIds()).containsExactlyElementsIn(ids).inOrder()
    }

    @Test
    fun `catalog descriptors should include metadata`() {
        HostCommandCatalog.descriptors.forEach { descriptor ->
            assertThat(descriptor.id).isNotEmpty()
            assertWithMessage(descriptor.id).that(descriptor.titleRes).isNotEqualTo(0)
            assertWithMessage(descriptor.id).that(descriptor.keywords).isNotEmpty()
            assertWithMessage(descriptor.id).that(descriptor.surfaces).isNotEmpty()
        }
    }

    @Test
    fun `plugin starter host command rules should match catalog`() {
        val rulesFile = findRepoRoot()
            .resolve("tools/plugin-starters/shared/validation-rules.json")

        val ruleCommands = Json.parseToJsonElement(rulesFile.readText())
            .jsonObject
            .getValue("knownHostCommands")
            .jsonArray
            .map { element -> element.jsonPrimitive.content }

        assertThat(ruleCommands).containsExactlyElementsIn(HostCommands.getAllCommandIds()).inOrder()
    }

    private fun findRepoRoot(): File = generateSequence(File("").absoluteFile) { file -> file.parentFile }
        .first { file -> File(file, "settings.gradle.kts").isFile }
}
