package com.wuxianggujun.tinaide.core.ndk

import android.content.Context
import com.google.common.truth.Truth.assertThat
import java.io.File
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class AndroidNativeToolchainManagerTest {
    private lateinit var context: Context
    private lateinit var manager: AndroidNativeToolchainManager

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        File(context.filesDir, "toolchain-config.json").delete()
        File(context.filesDir, "toolchains").deleteRecursively()
        File(context.filesDir, "tina-toolchain").deleteRecursively()
        manager = AndroidNativeToolchainManager(context)
    }

    @Test
    fun getInstallDir_shouldRejectLegacySingleDirectoryWhenConfigIsMissing() {
        val legacyDir = File(context.filesDir, "tina-toolchain").apply { mkdirs() }

        val result = runCatching { manager.getInstallDir() }

        assertThat(result.isFailure).isTrue()
        assertThat(legacyDir.exists()).isTrue()
    }

    @Test
    fun getInstallDir_shouldUseConfiguredToolchainDirectory() {
        val configManager = manager.getConfigManager()
        val info = ToolchainInfo(
            id = "builtin-1.0",
            name = "Builtin 1.0",
            version = "1.0",
            type = ToolchainType.BUILTIN,
            path = "toolchains/builtin-1.0",
            installedAt = 100L,
        )
        val installDir = configManager.getToolchainDir(info.id).apply { mkdirs() }
        configManager.saveConfig(
            InstalledToolchainConfig(
                activeToolchain = info.id,
                toolchains = listOf(info),
            )
        )

        assertThat(manager.getInstallDir().canonicalFile).isEqualTo(installDir.canonicalFile)
    }
}
