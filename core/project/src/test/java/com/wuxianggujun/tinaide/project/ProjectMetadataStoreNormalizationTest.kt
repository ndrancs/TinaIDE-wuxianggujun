package com.wuxianggujun.tinaide.project

import com.google.common.truth.Truth.assertThat
import java.io.File
import java.nio.file.Files
import org.junit.Test

class ProjectMetadataStoreNormalizationTest {

    @Test
    fun `read normalizes current metadata values`() {
        val projectRoot = createTempProjectRoot()
        try {
            writeProjectMetadata(
                projectRoot,
                """
                {
                  "schemaVersion": 2,
                  "id": "meta-1",
                  "displayName": "Demo",
                  "createdAt": 1700000000000,
                  "cppStandard": "c++20",
                  "nativeApiLevel": 99,
                  "nativeIncludeDirs": ["  third_party/SDL3/include ", "", "third_party/SDL3/include"],
                  "nativeCFlags": "-O2\n\n -DDEBUG "
                }
                """.trimIndent()
            )

            val metadata = ProjectMetadataStore.read(projectRoot)
            requireNotNull(metadata)

            assertThat(metadata.schemaVersion).isEqualTo(2)
            assertThat(metadata.cppStandard).isEqualTo("CPP_20")
            assertThat(metadata.nativeApiLevel).isNull()
            assertThat(metadata.nativeIncludeDirs).containsExactly("third_party/SDL3/include")
            assertThat(metadata.nativeCFlags).isEqualTo("-O2 -DDEBUG")

            val persisted = readProjectMetadata(projectRoot)
            assertThat(persisted).contains("\"schemaVersion\": 2")
            assertThat(persisted).contains("\"cppStandard\": \"CPP_20\"")
        } finally {
            projectRoot.deleteRecursively()
        }
    }

    @Test
    fun `write normalizes current metadata and keeps unknown cpp standard`() {
        val projectRoot = createTempProjectRoot()
        try {
            val metadata = ProjectMetadata(
                schemaVersion = 2,
                id = "meta-2",
                displayName = "Demo",
                createdAt = 1700000000000,
                cppStandard = "gnu++2b"
            )

            val wrote = ProjectMetadataStore.write(projectRoot, metadata)
            assertThat(wrote).isTrue()

            val persisted = readProjectMetadata(projectRoot)
            assertThat(persisted).contains("\"schemaVersion\": 2")
            assertThat(persisted).contains("\"cppStandard\": \"gnu++2b\"")
        } finally {
            projectRoot.deleteRecursively()
        }
    }

    private fun createTempProjectRoot(): File {
        return Files.createTempDirectory("project-meta-normalization-test").toFile()
    }

    private fun writeProjectMetadata(projectRoot: File, content: String) {
        val file = File(projectRoot, ".tinaide/project.json")
        file.parentFile?.mkdirs()
        file.writeText(content)
    }

    private fun readProjectMetadata(projectRoot: File): String {
        return File(projectRoot, ".tinaide/project.json").readText()
    }
}
