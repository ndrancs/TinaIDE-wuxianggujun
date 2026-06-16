package com.wuxianggujun.tinaide.core.ndk

import android.content.Context
import com.google.common.truth.Truth.assertThat
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class AndroidSysrootManagerTest {
    private lateinit var context: Context
    private lateinit var manager: AndroidSysrootManager

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        File(context.filesDir, "sysroot-profile-config.json").delete()
        File(context.filesDir, "android-sysroot").deleteRecursively()
        File(context.filesDir, "android-sysroots").deleteRecursively()
        manager = AndroidSysrootManager(context, testBuiltinManifest())
    }

    @Test
    fun getActiveProfile_shouldUseInstalledDefaultBundledProfileWhenNoConfig() {
        val defaultProfileDir = manager.getConfigManager().getProfileDir("builtin-ndk-r27c-arm64")
        createMinimalSysroot(defaultProfileDir, AndroidSysrootManager.Companion.Arch.ARM64)

        val profile = manager.getActiveProfile(AndroidSysrootManager.Companion.Arch.ARM64)

        assertThat(profile?.id).isEqualTo("builtin-ndk-r27c-arm64")
        assertThat(profile?.type).isEqualTo(SysrootProfileType.BUILTIN)
        assertThat(manager.getSysrootDir(AndroidSysrootManager.Companion.Arch.ARM64)).isEqualTo(defaultProfileDir)
        assertThat(manager.isInstalled(AndroidSysrootManager.Companion.Arch.ARM64)).isTrue()
    }

    @Test
    fun activateProfile_shouldSwitchSysrootDirectory() {
        val profileDir = manager.getConfigManager().getProfileDir("custom-r27")
        createMinimalSysroot(profileDir, AndroidSysrootManager.Companion.Arch.ARM64)
        val profile = profile(
            id = "custom-r27",
            path = "android-sysroots/custom-r27"
        )
        assertThat(manager.getConfigManager().registerOrReplaceProfile(profile).isSuccess).isTrue()

        val result = manager.activateProfile("custom-r27", AndroidSysrootManager.Companion.Arch.ARM64)

        assertThat(result.isSuccess).isTrue()
        assertThat(manager.getActiveProfile(AndroidSysrootManager.Companion.Arch.ARM64)?.id)
            .isEqualTo("custom-r27")
        assertThat(manager.getSysrootDir(AndroidSysrootManager.Companion.Arch.ARM64)).isEqualTo(profileDir)
    }

    @Test
    fun listProfiles_shouldExposeBundledProfilesFromManifest() {
        val profiles = manager.listProfiles(AndroidSysrootManager.Companion.Arch.ARM64)

        assertThat(profiles.map { it.id }).contains("builtin-ndk-r27c-arm64")
        assertThat(profiles.map { it.id }).doesNotContain("builtin-current-arm64")
        assertThat(profiles.first { it.id == "builtin-ndk-r27c-arm64" }.type)
            .isEqualTo(SysrootProfileType.BUILTIN)
    }

    @Test
    fun activateOrInstallProfile_shouldSwitchAlreadyExtractedBundledProfile() = runBlocking {
        val profileDir = manager.getConfigManager().getProfileDir("builtin-ndk-r27c-arm64")
        createMinimalSysroot(profileDir, AndroidSysrootManager.Companion.Arch.ARM64)

        val result = manager.activateOrInstallProfile(
            profileId = "builtin-ndk-r27c-arm64",
            arch = AndroidSysrootManager.Companion.Arch.ARM64
        )

        assertThat(result.isSuccess).isTrue()
        assertThat(manager.getActiveProfile(AndroidSysrootManager.Companion.Arch.ARM64)?.id)
            .isEqualTo("builtin-ndk-r27c-arm64")
        assertThat(manager.getSysrootDir(AndroidSysrootManager.Companion.Arch.ARM64)).isEqualTo(profileDir)
    }

    @Test
    fun getActiveProfile_shouldIgnoreBrokenConfiguredProfile() {
        val defaultProfileDir = manager.getConfigManager().getProfileDir("builtin-ndk-r27c-arm64")
        createMinimalSysroot(defaultProfileDir, AndroidSysrootManager.Companion.Arch.ARM64)
        val brokenProfile = profile(
            id = "broken",
            path = "android-sysroots/broken"
        )
        manager.getConfigManager().getProfileDir("broken").mkdirs()
        assertThat(manager.getConfigManager().registerOrReplaceProfile(brokenProfile).isSuccess).isTrue()
        assertThat(manager.getConfigManager().switchProfile("broken", AndroidSysrootManager.Companion.Arch.ARM64).isSuccess)
            .isTrue()

        val active = manager.getActiveProfile(AndroidSysrootManager.Companion.Arch.ARM64)

        assertThat(active?.id).isEqualTo("builtin-ndk-r27c-arm64")
        assertThat(active?.type).isEqualTo(SysrootProfileType.BUILTIN)
        assertThat(manager.getSysrootDir(AndroidSysrootManager.Companion.Arch.ARM64)).isEqualTo(defaultProfileDir)
    }

    @Test
    fun listProfiles_shouldIgnoreStaleBuiltinCurrentProfile() {
        val staleBuiltin = profile(
            id = "builtin-current-arm64",
            path = "android-sysroots/builtin-current-arm64",
            type = SysrootProfileType.BUILTIN
        )
        assertThat(manager.getConfigManager().registerOrReplaceProfile(staleBuiltin).isSuccess).isTrue()

        val profiles = manager.listProfiles(AndroidSysrootManager.Companion.Arch.ARM64)

        assertThat(profiles.map { it.id }).doesNotContain("builtin-current-arm64")
        assertThat(profiles.map { it.id }).contains("builtin-ndk-r27c-arm64")
    }

    private fun createMinimalSysroot(
        root: File,
        arch: AndroidSysrootManager.Companion.Arch
    ) {
        File(root, "usr/include").mkdirs()
        File(root, "usr/lib/${arch.triple}/28").mkdirs()
    }

    private fun profile(
        id: String,
        path: String,
        arch: String = AndroidSysrootManager.Companion.Arch.ARM64.name,
        type: SysrootProfileType = SysrootProfileType.CUSTOM
    ): SysrootProfileInfo = SysrootProfileInfo(
        id = id,
        name = id,
        arch = arch,
        type = type,
        path = path,
        installedAt = 100L,
        apiLevels = listOf(28),
        toolchainTriple = AndroidSysrootManager.Companion.Arch.ARM64.triple
    )

    private fun testBuiltinManifest(): BuiltinSysrootProfileManifest = BuiltinSysrootProfileManifest(
        schemaVersion = 1,
        defaultProfileId = "builtin-ndk-r27c-arm64",
        profiles = listOf(
            BuiltinSysrootProfileAsset(
                id = "builtin-ndk-r25c-arm64",
                name = "NDK r25c",
                arch = AndroidSysrootManager.Companion.Arch.ARM64.name,
                assetPath = "android-sysroot/android-sysroot-arm64-r25c.tar.xz",
                ndkVersion = "r25c",
                ndkLlvmVersion = "14.0.7",
                apiLevels = listOf(21, 22, 23, 24, 26, 27, 28, 29, 30, 31, 32, 33),
                toolchainTriple = AndroidSysrootManager.Companion.Arch.ARM64.triple,
                isDefault = false
            ),
            BuiltinSysrootProfileAsset(
                id = "builtin-ndk-r27c-arm64",
                name = "NDK r27c",
                arch = AndroidSysrootManager.Companion.Arch.ARM64.name,
                assetPath = "android-sysroot/android-sysroot-arm64-r27c.tar.xz",
                ndkVersion = "r27c",
                ndkLlvmVersion = "18",
                apiLevels = listOf(21, 22, 23, 24, 25, 26, 27, 28, 29, 30, 31, 32, 33, 34, 35),
                toolchainTriple = AndroidSysrootManager.Companion.Arch.ARM64.triple,
                isDefault = true
            )
        )
    )
}
