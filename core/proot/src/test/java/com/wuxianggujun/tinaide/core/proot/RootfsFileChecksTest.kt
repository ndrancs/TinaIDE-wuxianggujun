package com.wuxianggujun.tinaide.core.proot

import com.google.common.truth.Truth.assertThat
import java.io.File
import java.nio.file.Files
import org.junit.Test

class RootfsFileChecksTest {

    @Test
    fun `exists returns true for ordinary file`() {
        val rootDir = createTempRootfs()
        File(rootDir, "etc/passwd").apply {
            parentFile?.mkdirs()
            writeText("root:x:0:0:root:/root:/bin/sh\n")
        }

        val exists = RootfsFileChecks.exists(rootDir, "/etc/passwd")
        val content = RootfsFileChecks.readTextOrNull(rootDir, "/etc/passwd")

        assertThat(exists).isTrue()
        assertThat(content).contains("/bin/sh")
    }

    @Test
    fun `exists resolves absolute symlink inside rootfs`() {
        val rootDir = createTempRootfs()
        File(rootDir, "bin/busybox").apply {
            parentFile?.mkdirs()
            writeText("busybox\n")
        }

        val linkTargets = mapOf("bin/sh" to "/bin/busybox")

        val exists = RootfsFileChecks.exists(rootDir, "/bin/sh", readLinkTarget = fakeReadLink(rootDir, linkTargets))
        val content = RootfsFileChecks.readTextOrNull(rootDir, "/bin/sh", readLinkTarget = fakeReadLink(rootDir, linkTargets))

        assertThat(exists).isTrue()
        assertThat(content).isEqualTo("busybox\n")
    }

    @Test
    fun `exists resolves relative symlink inside rootfs`() {
        val rootDir = createTempRootfs()
        File(rootDir, "bin/busybox").apply {
            parentFile?.mkdirs()
            writeText("busybox\n")
        }

        val linkTargets = mapOf("usr/bin/sh" to "../../bin/busybox")

        val exists = RootfsFileChecks.exists(rootDir, "/usr/bin/sh", readLinkTarget = fakeReadLink(rootDir, linkTargets))
        val content = RootfsFileChecks.readTextOrNull(rootDir, "/usr/bin/sh", readLinkTarget = fakeReadLink(rootDir, linkTargets))

        assertThat(exists).isTrue()
        assertThat(content).isEqualTo("busybox\n")
    }

    @Test
    fun `isDirectory resolves absolute symlink to directory`() {
        val rootDir = createTempRootfs()
        File(rootDir, "var/cache").mkdirs()

        val linkTargets = mapOf("cache" to "/var/cache")

        val isDirectory = RootfsFileChecks.isDirectory(rootDir, "/cache", readLinkTarget = fakeReadLink(rootDir, linkTargets))

        assertThat(isDirectory).isTrue()
    }

    private fun createTempRootfs(): File = Files.createTempDirectory("rootfs-checks").toFile().apply {
        deleteOnExit()
    }

    private fun fakeReadLink(rootDir: File, linkTargets: Map<String, String>): (File) -> String? = { file ->
        val relativePath = file.relativeTo(rootDir).invariantSeparatorsPath
        linkTargets[relativePath]
    }
}
