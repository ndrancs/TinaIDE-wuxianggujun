package com.wuxianggujun.tinaide.core.crash

import com.wuxianggujun.tinaide.core.i18n.Strings

data class CrashUploadStatusTextSpec(
    val messageRes: Int,
    val isAttention: Boolean,
)

object CrashUploadStatusTextResolver {
    fun resolve(status: CrashUploadState.Status): CrashUploadStatusTextSpec = when (status) {
        CrashUploadState.Status.NONE -> CrashUploadStatusTextSpec(
            messageRes = Strings.crash_upload_status_none,
            isAttention = false,
        )
        CrashUploadState.Status.QUEUED -> CrashUploadStatusTextSpec(
            messageRes = Strings.crash_upload_status_queued,
            isAttention = false,
        )
        CrashUploadState.Status.UPLOADING -> CrashUploadStatusTextSpec(
            messageRes = Strings.crash_upload_status_uploading,
            isAttention = false,
        )
        CrashUploadState.Status.UPLOADED -> CrashUploadStatusTextSpec(
            messageRes = Strings.crash_upload_status_uploaded,
            isAttention = false,
        )
        CrashUploadState.Status.SKIPPED -> CrashUploadStatusTextSpec(
            messageRes = Strings.crash_upload_status_skipped,
            isAttention = true,
        )
        CrashUploadState.Status.RETRY_PENDING -> CrashUploadStatusTextSpec(
            messageRes = Strings.crash_upload_status_retry_pending,
            isAttention = true,
        )
        CrashUploadState.Status.FAILED -> CrashUploadStatusTextSpec(
            messageRes = Strings.crash_upload_status_failed,
            isAttention = true,
        )
    }
}
