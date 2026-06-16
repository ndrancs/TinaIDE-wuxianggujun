package com.wuxianggujun.tinaide.core.common.io

import com.google.common.truth.Truth.assertThat
import java.io.File
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ArchivePathSafetyTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun resolveEntryFile_rejectsTraversalAbsoluteAndDrivePaths() {
        val targetDir = tempFolder.newFolder("target")

        assertThrows(IllegalArgumentException::class.java) {
            ArchivePathSafety.resolveEntryFile(targetDir, "../evil.txt")
        }
        assertThrows(IllegalArgumentException::class.java) {
            ArchivePathSafety.resolveEntryFile(targetDir, "/tmp/evil.txt")
        }
        assertThrows(IllegalArgumentException::class.java) {
            ArchivePathSafety.resolveEntryFile(targetDir, "C:/tmp/evil.txt")
        }
    }

    @Test
    fun resolveEntryFile_allowsNormalizedRelativePathInsideTarget() {
        val targetDir = tempFolder.newFolder("target")

        val entryFile = ArchivePathSafety.resolveEntryFile(targetDir, "./include/./stdio.h")

        assertThat(entryFile.canonicalPath)
            .isEqualTo(File(targetDir.canonicalFile, "include/stdio.h").canonicalPath)
    }

    @Test
    fun symlinkTargetMustStayInsideTargetDir() {
        val targetDir = tempFolder.newFolder("target")
        val linkFile = File(targetDir, "bin/tool")

        assertThat(
            ArchivePathSafety.requireSymlinkTargetInsideTargetDir(
                targetDir = targetDir,
                linkFile = linkFile,
                linkTarget = "../lib/tool"
            )
        ).isEqualTo("../lib/tool")

        assertThrows(IllegalArgumentException::class.java) {
            ArchivePathSafety.requireSymlinkTargetInsideTargetDir(
                targetDir = targetDir,
                linkFile = linkFile,
                linkTarget = "../../outside"
            )
        }
    }
}
