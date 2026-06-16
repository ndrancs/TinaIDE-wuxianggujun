package com.wuxianggujun.tinaide.core.compile

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class LaunchEnvironmentTest {

    @Test
    fun `withPrependedPath prepends paths preserves existing paths and de-duplicates`() {
        val merged = LaunchEnvironment.withPrependedPath(
            environment = mapOf(
                "LD_LIBRARY_PATH" to "/custom/lib:/pkg/runtime",
                "EXTRA_FLAG" to "1",
                "BAD-NAME" to "ignored",
            ),
            variableName = "LD_LIBRARY_PATH",
            paths = listOf(" /project/runtime ", "/pkg/runtime", ""),
        )

        assertThat(merged).containsEntry(
            "LD_LIBRARY_PATH",
            "/project/runtime:/pkg/runtime:/custom/lib",
        )
        assertThat(merged).containsEntry("EXTRA_FLAG", "1")
        assertThat(merged).doesNotContainKey("BAD-NAME")
    }

    @Test
    fun `withPrependedPath removes blank path variable when no path remains`() {
        val merged = LaunchEnvironment.withPrependedPath(
            environment = mapOf("LD_LIBRARY_PATH" to " "),
            variableName = "LD_LIBRARY_PATH",
            paths = emptyList(),
        )

        assertThat(merged).doesNotContainKey("LD_LIBRARY_PATH")
    }
}
