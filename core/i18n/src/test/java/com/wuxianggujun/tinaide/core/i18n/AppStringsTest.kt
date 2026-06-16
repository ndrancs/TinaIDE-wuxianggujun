package com.wuxianggujun.tinaide.core.i18n

import android.content.Context
import com.google.common.truth.Truth.assertThat
import io.mockk.every
import io.mockk.mockk
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class AppStringsTest {

    @Test
    fun getOr_shouldUseProvidedContextWithoutGlobalInitialization() {
        val context = fakeStringContext()

        val appName = AppStrings.getOr(context, Strings.app_name)

        assertThat(appName).isEqualTo("TinaIDE")
    }

    @Test
    fun getOr_shouldReplaceNullFormatArgsWithBlankStrings() {
        val context = fakeStringContext()

        val text = AppStrings.getOr(context, Strings.log_export_app_name, null)

        assertThat(text).doesNotContain("null")
    }

    private fun fakeStringContext(): Context = mockk {
        every { getString(Strings.app_name) } returns "TinaIDE"
        every { getString(Strings.log_export_app_name) } returns "应用名称: %1\$s"
        every { getString(Strings.log_export_app_name, *anyVararg()) } answers {
            "应用名称: %1\$s".format(*args.drop(1).toTypedArray())
        }
    }
}
