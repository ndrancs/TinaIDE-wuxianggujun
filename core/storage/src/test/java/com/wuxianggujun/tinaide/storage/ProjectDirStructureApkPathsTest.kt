package com.wuxianggujun.tinaide.storage

import com.google.common.truth.Truth.assertThat
import java.io.File
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ProjectDirStructureApkPathsTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private val projectPath: String
        get() = tempFolder.root.absolutePath

    @Test
    fun `getApkExportDir points to tinaide apk-export`() {
        val dir = ProjectDirStructure.getApkExportDir(projectPath)

        assertThat(dir).isEqualTo(File(tempFolder.root, ".tinaide/apk-export"))
    }

    @Test
    fun `getApkExportIconsDir is child of apk-export`() {
        val dir = ProjectDirStructure.getApkExportIconsDir(projectPath)

        assertThat(dir).isEqualTo(File(tempFolder.root, ".tinaide/apk-export/icons"))
    }

    @Test
    fun `getApkExportRuntimeLibsDir is child of apk-export`() {
        val dir = ProjectDirStructure.getApkExportRuntimeLibsDir(projectPath)

        assertThat(dir).isEqualTo(File(tempFolder.root, ".tinaide/apk-export/runtime-libs"))
    }

    @Test
    fun `getApkPermissionsFile is permissions json under apk-export`() {
        val file = ProjectDirStructure.getApkPermissionsFile(projectPath)

        assertThat(file).isEqualTo(File(tempFolder.root, ".tinaide/apk-export/permissions.json"))
    }

    @Test
    fun `getApkSigningPropertiesFile is signing properties under apk-export`() {
        val file = ProjectDirStructure.getApkSigningPropertiesFile(projectPath)

        assertThat(file).isEqualTo(File(tempFolder.root, ".tinaide/apk-export/signing.properties"))
    }

    @Test
    fun `getKeystoreDir is sibling of apk-export`() {
        val dir = ProjectDirStructure.getKeystoreDir(projectPath)

        assertThat(dir).isEqualTo(File(tempFolder.root, ".tinaide/keystore"))
    }
}
