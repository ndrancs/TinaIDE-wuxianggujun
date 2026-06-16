package com.wuxianggujun.tinaide.plugin

import com.google.common.truth.Truth.assertThat
import java.io.File
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import org.junit.Test

class BundledCppSnippetsAssetTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun bundledCppSnippets_shouldDeclareAndParseExpectedSnippetFiles() {
        val pluginDir = projectRoot()
            .resolve("app/src/main/assets/bundled_plugins/tinaide.cpp.snippets")

        val manifest = json.decodeFromString<PluginManifest>(
            pluginDir.resolve("manifest.json").readText(Charsets.UTF_8)
        )

        assertThat(manifest.id).isEqualTo("tinaide.cpp.snippets")
        assertThat(manifest.type).isEqualTo("config")
        assertThat(manifest.apiVersion).isEqualTo(1)
        assertThat(manifest.contributions?.snippets)
            .containsExactly("snippets/cpp.json", "snippets/c.json")
            .inOrder()

        val snippetsByLanguage = manifest.contributions?.snippets.orEmpty()
            .map { relativePath ->
                val snippetFile = pluginDir.resolve(relativePath)
                assertThat(snippetFile.exists()).isTrue()
                json.decodeFromString<SnippetFile>(snippetFile.readText(Charsets.UTF_8))
            }
            .associateBy { it.language }

        assertSnippetFile(
            snippetFile = snippetsByLanguage.getValue("cpp"),
            expectedPrefixes = setOf("main", "fori", "class", "vec", "cmake_exe")
        )
        assertSnippetFile(
            snippetFile = snippetsByLanguage.getValue("c"),
            expectedPrefixes = setOf("main", "fori", "printf", "struct")
        )
    }

    private fun assertSnippetFile(
        snippetFile: SnippetFile,
        expectedPrefixes: Set<String>
    ) {
        assertThat(snippetFile.language).isNotEmpty()
        assertThat(snippetFile.snippets.map { it.prefix }).containsAtLeastElementsIn(expectedPrefixes)

        val duplicatePrefixes = snippetFile.snippets
            .groupBy { it.prefix }
            .filterValues { it.size > 1 }
            .keys
        assertThat(duplicatePrefixes).isEmpty()

        snippetFile.snippets.forEach { snippet ->
            assertThat(snippet.prefix).isNotEmpty()
            assertThat(snippet.name).isNotEmpty()
            assertThat(snippet.body).isNotEmpty()
            assertThat(snippet.body.joinToString("\n").trim()).isNotEmpty()
        }
    }

    private fun projectRoot(): File = generateSequence(File("").absoluteFile) { it.parentFile }
        .first { File(it, "settings.gradle.kts").exists() }
}
