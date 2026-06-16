package com.wuxianggujun.tinaide.core.crash

import android.app.Application
import android.content.Context
import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(
    sdk = [34],
    manifest = Config.NONE,
    application = Application::class
)
class CrashUploadStateTest {
    private lateinit var context: Context

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication().applicationContext
        context.getSharedPreferences("tinaide_config", Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
    }

    @Test
    fun uploadLifecycle_shouldKeepLatestStatusAndAttemptCount() {
        assertThat(CrashUploadState.isAutoUploadEnabled(context)).isFalse()
        context.getSharedPreferences("tinaide_config", Context.MODE_PRIVATE)
            .edit()
            .putBoolean("logs.crash.auto_upload", true)
            .commit()
        assertThat(CrashUploadState.isAutoUploadEnabled(context)).isTrue()

        CrashUploadState.markUploadQueued(context, "tombstone.log", "captured_by_xcrash")
        assertThat(CrashUploadState.getLastUploadSnapshot(context).status)
            .isEqualTo(CrashUploadState.Status.QUEUED)

        CrashUploadState.markUploadAttemptStarted(context, "tombstone.log", 100L)
        var snapshot = CrashUploadState.getLastUploadSnapshot(context)
        assertThat(snapshot.status).isEqualTo(CrashUploadState.Status.UPLOADING)
        assertThat(snapshot.attemptCount).isEqualTo(1)

        CrashUploadState.markUploadDeferred(context, "tombstone.log", 100L, "network_error")
        snapshot = CrashUploadState.getLastUploadSnapshot(context)
        assertThat(snapshot.status).isEqualTo(CrashUploadState.Status.RETRY_PENDING)
        assertThat(snapshot.reason).isEqualTo("network_error")
        assertThat(snapshot.attemptCount).isEqualTo(1)

        CrashUploadState.markUploadAttemptStarted(context, "tombstone.log", 100L)
        CrashUploadState.markUploaded(context, "tombstone.log", 100L)
        snapshot = CrashUploadState.getLastUploadSnapshot(context)
        assertThat(snapshot.status).isEqualTo(CrashUploadState.Status.UPLOADED)
        assertThat(snapshot.attemptCount).isEqualTo(2)
        assertThat(CrashUploadState.isUploaded(context, "tombstone.log")).isTrue()
    }

    @Test
    fun markUploadSkipped_shouldDeduplicateAndRecordReason() {
        CrashUploadState.markUploadSkipped(context, "user-runtime.log", "user_runtime_crash")

        val snapshot = CrashUploadState.getLastUploadSnapshot(context)
        assertThat(CrashUploadState.isUploadSkipped(context, "user-runtime.log")).isTrue()
        assertThat(snapshot.status).isEqualTo(CrashUploadState.Status.SKIPPED)
        assertThat(snapshot.fileName).isEqualTo("user-runtime.log")
        assertThat(snapshot.reason).isEqualTo("user_runtime_crash")
    }
}
