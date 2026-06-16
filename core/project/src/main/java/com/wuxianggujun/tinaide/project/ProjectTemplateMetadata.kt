package com.wuxianggujun.tinaide.project

import java.io.File
import java.util.Locale
import java.util.zip.ZipFile
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

data class ProjectTemplateMetadata(
    val name: String? = null,
    val description: String? = null,
    val author: String? = null,
    val buildSystem: ProjectBuildSystem? = null,
    val primaryLanguage: ProjectLanguage? = null,
    val isNdkTemplate: Boolean? = null,
    val variables: Map<String, String> = emptyMap(),
) {
    internal fun hasAnyField(): Boolean = name != null ||
        description != null ||
        author != null ||
        buildSystem != null ||
        primaryLanguage != null ||
        isNdkTemplate != null ||
        variables.isNotEmpty()
}

object ProjectTemplateMetadataReader {
    const val METADATA_FILE_NAME = "tina-template.json"
    private const val MAX_METADATA_BYTES = 64 * 1024
    private val json = Json { ignoreUnknownKeys = true }

    fun readFromZip(zipFile: File): ProjectTemplateMetadata? {
        return runCatching {
            ZipFile(zipFile).use { zip ->
                val metadataEntry = zip.entries().asSequence()
                    .filterNot { it.isDirectory }
                    .firstOrNull { isMetadataEntry(it.name) }
                    ?: return@use null

                val content = zip.getInputStream(metadataEntry).use(::readMetadataContent)
                    ?: return@use null
                parse(content)
            }
        }.getOrNull()
    }

    fun isMetadataEntry(entryName: String): Boolean = entryName
        .replace('\\', '/')
        .trimStart('/')
        .equals(METADATA_FILE_NAME, ignoreCase = true)

    private fun parse(content: String): ProjectTemplateMetadata? {
        val root = json.parseToJsonElement(content).jsonObject
        val metadata = ProjectTemplateMetadata(
            name = root.firstString("name"),
            description = root.firstString("description"),
            author = root.firstString("author"),
            buildSystem = parseBuildSystem(root.firstString("buildSystem", "build_system")),
            primaryLanguage = parseLanguage(root.firstString("primaryLanguage", "primary_language")),
            isNdkTemplate = root.firstBoolean("ndkTemplate", "ndk_template", "isNdkTemplate"),
            variables = root.stringMap("variables"),
        )
        return metadata.takeIf { it.hasAnyField() }
    }

    private fun readMetadataContent(input: java.io.InputStream): String? {
        val buffer = ByteArray(MAX_METADATA_BYTES + 1)
        var totalBytesRead = 0
        while (totalBytesRead < buffer.size) {
            val bytesRead = input.read(buffer, totalBytesRead, buffer.size - totalBytesRead)
            if (bytesRead <= 0) break
            totalBytesRead += bytesRead
        }
        if (totalBytesRead > MAX_METADATA_BYTES) {
            return null
        }
        return String(buffer, 0, totalBytesRead, Charsets.UTF_8)
    }

    private fun JsonObject.firstString(vararg keys: String): String? = keys.asSequence()
        .mapNotNull { key -> this[key]?.jsonPrimitive?.contentOrNull?.trim() }
        .firstOrNull { it.isNotBlank() }

    private fun JsonObject.firstBoolean(vararg keys: String): Boolean? = keys.asSequence()
        .mapNotNull { key -> this[key]?.jsonPrimitive?.booleanOrNull }
        .firstOrNull()

    private fun JsonObject.stringMap(key: String): Map<String, String> {
        val content = runCatching { this[key]?.jsonObject }.getOrNull() ?: return emptyMap()
        return content.entries
            .mapNotNull { (entryKey, value) ->
                val normalizedKey = entryKey.trim().takeIf { it.isNotBlank() }
                val normalizedValue = value.stringContentOrNull()?.trim()?.takeIf { it.isNotBlank() }
                if (normalizedKey != null && normalizedValue != null) {
                    normalizedKey to normalizedValue
                } else {
                    null
                }
            }
            .toMap()
    }

    private fun kotlinx.serialization.json.JsonElement.stringContentOrNull(): String? {
        val primitive = runCatching { jsonPrimitive }.getOrNull() ?: return null
        return primitive
            .takeIf { it.toString().startsWith("\"") }
            ?.contentOrNull
    }

    private fun parseBuildSystem(value: String?): ProjectBuildSystem? {
        val normalized = value.normalizedToken() ?: return null
        return when (normalized) {
            "SINGLE", "SINGLEFILE", "SINGLE_FILE" -> ProjectBuildSystem.SINGLE_FILE
            "CMAKE" -> ProjectBuildSystem.CMAKE
            "MAKE", "MAKEFILE" -> ProjectBuildSystem.MAKE
            "PLUGIN" -> ProjectBuildSystem.PLUGIN
            "UNKNOWN" -> ProjectBuildSystem.UNKNOWN
            else -> ProjectBuildSystem.entries.firstOrNull { it.name == normalized }
        }
    }

    private fun parseLanguage(value: String?): ProjectLanguage? {
        val normalized = value.normalizedToken() ?: return null
        return when (normalized) {
            "CXX", "C_PLUS_PLUS", "CPP" -> ProjectLanguage.CPP
            "JS", "JAVASCRIPT" -> ProjectLanguage.JAVASCRIPT
            "TS", "TYPESCRIPT" -> ProjectLanguage.TYPESCRIPT
            else -> ProjectLanguage.entries.firstOrNull { it.name == normalized }
        }
    }

    private fun String?.normalizedToken(): String? {
        val normalized = this
            ?.trim()
            ?.replace("+", "_PLUS_")
            ?.replace(Regex("[\\s-]+"), "_")
            ?.replace(Regex("[^A-Za-z0-9_]+"), "")
            ?.replace(Regex("_+"), "_")
            ?.uppercase(Locale.ROOT)
            ?.trim('_')
            .orEmpty()
        return normalized.ifBlank { null }
    }
}
