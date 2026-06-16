package com.wuxianggujun.tinaide.storage

import com.google.common.truth.Truth.assertThat
import java.io.File
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class StorageCleanupSupportTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun `computeSize on missing file returns 0`() {
        val missing = File(tempFolder.root, "missing.bin")

        assertThat(StorageCleanupSupport.computeSize(missing)).isEqualTo(0L)
    }

    @Test
    fun `computeSize on plain file returns its length`() {
        val file = tempFolder.newFile("a.bin").apply { writeBytes(ByteArray(512)) }

        assertThat(StorageCleanupSupport.computeSize(file)).isEqualTo(512L)
    }

    @Test
    fun `computeSize on directory returns recursive total`() {
        val root = tempFolder.newFolder("data")
        File(root, "a.bin").writeBytes(ByteArray(100))
        val nested = File(root, "nested").apply { mkdirs() }
        File(nested, "b.bin").writeBytes(ByteArray(200))
        File(nested, "c.bin").writeBytes(ByteArray(300))

        assertThat(StorageCleanupSupport.computeSize(root)).isEqualTo(600L)
    }

    @Test
    fun `deleteContents on missing dir returns zero and no failures`() {
        val missing = File(tempFolder.root, "not-there")

        val result = StorageCleanupSupport.deleteContents(missing)

        assertThat(result.deletedBytes).isEqualTo(0L)
        assertThat(result.failedPaths).isEmpty()
    }

    @Test
    fun `deleteContents removes children but keeps the directory itself`() {
        val root = tempFolder.newFolder("build")
        File(root, "f1.bin").writeBytes(ByteArray(100))
        val nested = File(root, "nested").apply { mkdirs() }
        File(nested, "f2.bin").writeBytes(ByteArray(400))

        val result = StorageCleanupSupport.deleteContents(root)

        assertThat(result.deletedBytes).isEqualTo(500L)
        assertThat(result.failedPaths).isEmpty()
        assertThat(root.exists()).isTrue()
        assertThat(root.listFiles()).isEmpty()
    }

    @Test
    fun `deleteContents on empty directory returns zero`() {
        val root = tempFolder.newFolder("empty")

        val result = StorageCleanupSupport.deleteContents(root)

        assertThat(result.deletedBytes).isEqualTo(0L)
        assertThat(result.failedPaths).isEmpty()
        assertThat(root.exists()).isTrue()
    }

    @Test
    fun `deleteContents with file target returns zero and preserves file`() {
        val file = tempFolder.newFile("not-a-dir.bin").apply { writeBytes(ByteArray(50)) }

        val result = StorageCleanupSupport.deleteContents(file)

        assertThat(result.deletedBytes).isEqualTo(0L)
        assertThat(result.failedPaths).isEmpty()
        assertThat(file.exists()).isTrue()
    }

    @Test
    fun `isPathUnderAnyRoot accepts file inside one of the roots`() {
        val rootA = tempFolder.newFolder("rootA")
        val rootB = tempFolder.newFolder("rootB")
        val file = File(rootB, "nested/child.bin").apply {
            parentFile!!.mkdirs()
            writeBytes(ByteArray(8))
        }

        assertThat(StorageCleanupSupport.isPathUnderAnyRoot(file, listOf(rootA, rootB))).isTrue()
    }

    @Test
    fun `isPathUnderAnyRoot accepts the root itself`() {
        val rootA = tempFolder.newFolder("rootA")

        assertThat(StorageCleanupSupport.isPathUnderAnyRoot(rootA, listOf(rootA))).isTrue()
    }

    @Test
    fun `isPathUnderAnyRoot rejects sibling outside the roots`() {
        val rootA = tempFolder.newFolder("rootA")
        val sibling = tempFolder.newFolder("sibling")

        assertThat(StorageCleanupSupport.isPathUnderAnyRoot(sibling, listOf(rootA))).isFalse()
    }

    @Test
    fun `isPathUnderAnyRoot resolves dotdot and rejects escape via canonical path`() {
        val rootA = tempFolder.newFolder("rootA")
        val outside = tempFolder.newFolder("outside")
        // Build a non-canonical path that pretends to be inside rootA but escapes via ..
        val sneaky = File(rootA, "../outside/child.bin").apply {
            parentFile!!.mkdirs()
            writeBytes(ByteArray(8))
        }

        assertThat(StorageCleanupSupport.isPathUnderAnyRoot(sneaky, listOf(rootA))).isFalse()
        assertThat(outside.exists()).isTrue()
    }
}
