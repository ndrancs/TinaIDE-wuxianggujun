package com.wuxianggujun.tinaide.core.ndk

import android.app.Application
import android.content.Context
import com.google.common.truth.Truth.assertThat
import com.wuxianggujun.tinaide.core.i18n.Strings
import io.mockk.every
import io.mockk.mockk
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(
    sdk = [34],
    manifest = Config.NONE,
    application = Application::class
)
class ToolchainInfoDisplayTest {
    private val context: Context = mockk {
        every { getString(Strings.toolchain_version_label, *anyVararg()) } answers {
            val formatArgs = args.drop(1).flatMap { arg ->
                if (arg is Array<*>) arg.toList() else listOf(arg)
            }
            "Version: ${formatArgs.joinToString()}"
        }
    }

    @Test
    fun displayName_shouldUseCustomNameOrFallbackId() {
        assertThat(toolchain(name = " Android NDK ").displayName(context)).isEqualTo("Android NDK")
        assertThat(toolchain(id = "custom-id", name = " ").displayName(context)).isEqualTo("custom-id")
    }

    @Test
    fun displayVersionLabel_shouldIgnoreBlankVersion() {
        assertThat(toolchain(version = null).displayVersionLabel(context)).isNull()
        assertThat(toolchain(version = " ").displayVersionLabel(context)).isNull()
    }

    @Test
    fun displayLabel_shouldAppendVersionWhenPresent() {
        val label = toolchain(name = "Clang", version = "18.1").displayLabel(context)

        assertThat(label).contains("Clang")
        assertThat(label).contains("18.1")
    }

    private fun toolchain(
        id: String = "custom",
        name: String = "Custom",
        version: String? = "1.0",
    ): ToolchainInfo = ToolchainInfo(
        id = id,
        name = name,
        version = version,
        type = ToolchainType.CUSTOM,
        path = "/toolchains/$id",
        installedAt = 100L
    )
}
