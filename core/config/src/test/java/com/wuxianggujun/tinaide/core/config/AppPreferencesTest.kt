package com.wuxianggujun.tinaide.core.config

import android.content.Context
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class AppPreferencesTest {

    @Test
    fun `get uses fixed tinaide preferences name`() {
        val context = RuntimeEnvironment.getApplication().applicationContext as Context
        val appPrefs = AppPreferences.get(context)
        val defaultPrefs = context.getSharedPreferences("${context.packageName}_preferences", Context.MODE_PRIVATE)

        appPrefs.edit().clear().commit()
        defaultPrefs.edit().clear().commit()

        try {
            appPrefs.edit().putString("current_key", "current").commit()
            defaultPrefs.edit().putString("current_key", "default").commit()

            assertThat(appPrefs.getString("current_key", null)).isEqualTo("current")
            assertThat(defaultPrefs.getString("current_key", null)).isEqualTo("default")
        } finally {
            appPrefs.edit().clear().commit()
            defaultPrefs.edit().clear().commit()
        }
    }
}
