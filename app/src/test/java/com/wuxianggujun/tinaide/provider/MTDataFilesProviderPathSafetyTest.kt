package com.wuxianggujun.tinaide.provider

import com.google.common.truth.Truth.assertThat
import java.io.File
import java.io.FileNotFoundException
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class MTDataFilesProviderPathSafetyTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    private val packageName = "com.example.app"

    @Test
    fun parseDocumentId_acceptsOnlyExactPackageRootOrPackageScopedChildren() {
        assertThat(MTDataFilesProviderPathSafety.parseDocumentId(packageName, packageName))
            .isEqualTo(MTDocumentIdParts(type = null, subPath = ""))

        assertThat(
            MTDataFilesProviderPathSafety.parseDocumentId(
                packageName,
                "$packageName/data/files/main.cpp",
            )
        ).isEqualTo(MTDocumentIdParts(type = "data", subPath = "files/main.cpp"))

        assertThrows(FileNotFoundException::class.java) {
            MTDataFilesProviderPathSafety.parseDocumentId(packageName, "${packageName}evil/data")
        }
        assertThrows(FileNotFoundException::class.java) {
            MTDataFilesProviderPathSafety.parseDocumentId(packageName, "other.package/data")
        }
    }

    @Test
    fun sanitizeDocumentSubPath_rejectsTraversalAndMalformedSegments() {
        assertThat(
            MTDataFilesProviderPathSafety.sanitizeDocumentSubPath(
                "files/include/main.h",
                "$packageName/data/files/include/main.h",
            )
        ).isEqualTo("files/include/main.h")

        listOf(
            "../outside",
            "files/../outside",
            "files//main.cpp",
            "files/./main.cpp",
            "/absolute/path",
            "windows\\path",
            "bad\u0000name",
        ).forEach { subPath ->
            assertThrows(FileNotFoundException::class.java) {
                MTDataFilesProviderPathSafety.sanitizeDocumentSubPath(
                    subPath,
                    "$packageName/data/$subPath",
                )
            }
        }
    }

    @Test
    fun sanitizeDisplayName_rejectsNamesThatCanChangePathScope() {
        assertThat(MTDataFilesProviderPathSafety.sanitizeDisplayName("main.cpp"))
            .isEqualTo("main.cpp")

        listOf("", " ", ".", "..", "../main.cpp", "dir/main.cpp", "dir\\main.cpp", "bad\u0000name")
            .forEach { displayName ->
                assertThrows(FileNotFoundException::class.java) {
                    MTDataFilesProviderPathSafety.sanitizeDisplayName(displayName)
                }
            }
    }

    @Test
    fun documentIdHelpers_preserveParentScope() {
        assertThat(
            MTDataFilesProviderPathSafety.getParentDocumentId(
                packageName,
                "$packageName/data/files/main.cpp",
            )
        ).isEqualTo("$packageName/data/files")

        assertThat(MTDataFilesProviderPathSafety.appendDocumentId("$packageName/data", "main.cpp"))
            .isEqualTo("$packageName/data/main.cpp")

        assertThrows(FileNotFoundException::class.java) {
            MTDataFilesProviderPathSafety.getParentDocumentId(packageName, "$packageName/data")
        }
    }

    @Test
    fun requireFileInsideRoot_rejectsCanonicalEscape() {
        val root = tempFolder.newFolder("root").canonicalFile
        val insideDir = File(root, "files").apply { mkdirs() }
        val insideFile = File(insideDir, "main.cpp")

        MTDataFilesProviderPathSafety.requireFileInsideRoot(
            root = root,
            file = insideFile,
            docId = "$packageName/data/files/main.cpp",
            existsWithoutFollowing = { false },
            isSymbolicLink = { false },
            readLink = { error("not a symlink: $it") },
        )

        assertThrows(FileNotFoundException::class.java) {
            MTDataFilesProviderPathSafety.requireFileInsideRoot(
                root = root,
                file = File(root, "../outside.txt"),
                docId = "$packageName/data/../outside.txt",
                existsWithoutFollowing = { false },
                isSymbolicLink = { false },
                readLink = { error("not a symlink: $it") },
            )
        }
    }

    @Test
    fun requireFileInsideRoot_rejectsSymlinkTargetOutsideRoot() {
        val root = tempFolder.newFolder("root").canonicalFile
        val linkFile = File(root, "bin/tool").apply { parentFile?.mkdirs() }

        assertThrows(FileNotFoundException::class.java) {
            MTDataFilesProviderPathSafety.requireFileInsideRoot(
                root = root,
                file = linkFile,
                docId = "$packageName/data/bin/tool",
                existsWithoutFollowing = { it == linkFile },
                isSymbolicLink = { it == linkFile },
                readLink = { "../../outside" },
            )
        }
    }

    @Test
    fun requireSymlinkTargetInsideRoot_allowsInternalTargetsOnly() {
        val root = tempFolder.newFolder("root").canonicalFile
        val linkFile = File(root, "bin/tool").apply { parentFile?.mkdirs() }
        File(root, "lib").mkdirs()
        val outsideTarget = File(tempFolder.root, "outside").absolutePath

        assertThat(
            MTDataFilesProviderPathSafety.requireSymlinkTargetInsideRoot(
                root = root,
                linkFile = linkFile,
                linkTarget = "../lib/tool",
                docId = "$packageName/data/bin/tool",
            )
        ).isEqualTo("../lib/tool")

        listOf("../../outside", outsideTarget, "", "bad\u0000target").forEach { target ->
            assertThrows(FileNotFoundException::class.java) {
                MTDataFilesProviderPathSafety.requireSymlinkTargetInsideRoot(
                    root = root,
                    linkFile = linkFile,
                    linkTarget = target,
                    docId = "$packageName/data/bin/tool",
                )
            }
        }
    }
}
