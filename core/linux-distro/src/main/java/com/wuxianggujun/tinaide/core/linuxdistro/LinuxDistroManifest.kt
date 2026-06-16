package com.wuxianggujun.tinaide.core.linuxdistro

import java.io.InputStream
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class LinuxDistroManifest(
    val schemaVersion: Int,
    val generatedAt: String? = null,
    val distros: List<DistroDefinition>,
    val mirrors: List<DistroMirrorRule> = emptyList(),
) {
    init {
        require(schemaVersion == CURRENT_SCHEMA_VERSION) {
            "Unsupported linux distro manifest schema: $schemaVersion"
        }
        require(distros.map { it.id }.distinct().size == distros.size) {
            "Distro ids must be unique."
        }
    }

    companion object {
        const val CURRENT_SCHEMA_VERSION = 1
    }
}

object LinuxDistroManifestParser {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    fun decode(text: String): LinuxDistroManifest = json.decodeFromString(LinuxDistroManifest.serializer(), text)

    fun decode(inputStream: InputStream): LinuxDistroManifest = inputStream.bufferedReader(Charsets.UTF_8).use { reader -> decode(reader.readText()) }

    fun encode(manifest: LinuxDistroManifest): String = json.encodeToString(LinuxDistroManifest.serializer(), manifest)
}
