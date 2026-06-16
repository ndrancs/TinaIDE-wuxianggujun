package com.wuxianggujun.tinaide.search.replace

import com.google.common.truth.Truth.assertThat
import java.io.File
import org.junit.Test

class ReplaceResultTest {

    @Test
    fun `batch result is success when there are no failed files`() {
        val result = BatchReplaceResult(
            totalFiles = 2,
            successFiles = 2,
            failedFiles = 0,
            totalReplacements = 3,
            results = listOf(
                ReplaceResult.Success(
                    file = File("first.kt"),
                    replacedCount = 1
                ),
                ReplaceResult.Success(
                    file = File("second.kt"),
                    replacedCount = 2,
                    backupPath = "second.kt.bak"
                )
            )
        )

        assertThat(result.isSuccess).isTrue()
        assertThat(result.hasPartialSuccess).isFalse()
    }

    @Test
    fun `batch result reports partial success when successes and failures coexist`() {
        val result = BatchReplaceResult(
            totalFiles = 2,
            successFiles = 1,
            failedFiles = 1,
            totalReplacements = 1,
            results = listOf(
                ReplaceResult.Success(file = File("ok.kt"), replacedCount = 1),
                ReplaceResult.Failure(file = File("failed.kt"), error = "denied")
            )
        )

        assertThat(result.isSuccess).isFalse()
        assertThat(result.hasPartialSuccess).isTrue()
    }

    @Test
    fun `replace progress returns ratio and guards empty total`() {
        assertThat(
            ReplaceProgress(
                totalFiles = 4,
                completedFiles = 3,
                currentFile = "Main.kt",
                isRunning = true
            ).progress
        ).isEqualTo(0.75f)

        assertThat(
            ReplaceProgress(
                totalFiles = 0,
                completedFiles = 0
            ).progress
        ).isEqualTo(0f)
    }
}
