package com.wuxianggujun.tinaide.core.crash

import android.app.job.JobInfo
import android.app.job.JobScheduler
import android.content.ComponentName
import android.content.Context
import timber.log.Timber

/**
 * 崩溃日志上传任务调度器。
 *
 * 目标：
 * - 允许在 :crash 进程或任意时刻快速入队，不阻塞 UI；
 * - 由系统调度执行，满足网络条件时自动运行，失败时自动退避重试；
 * - 用户关闭开关后立即取消任何待执行任务。
 */
object CrashLogUploadScheduler {
    private const val TAG = "CrashLogUploadScheduler"

    /**
     * 固定 JobId，避免重复注册多个任务。
     *
     * 注意：JobId 需要在整个 App 内唯一。
     */
    private const val JOB_ID = 0x54494E41 // "TINA"

    fun scheduleIfNeeded(context: Context) {
        if (!CrashUploadState.isAutoUploadEnabled(context)) {
            Timber.tag(TAG).d("Crash auto-upload is disabled")
            return
        }

        val pending = CrashLogUploader.hasPendingUploadableTombstone(context)

        if (!pending) {
            Timber.tag(TAG).d("No pending tombstone file found")
            return
        }

        schedule(context)
    }

    fun schedule(context: Context) {
        if (!CrashUploadState.isAutoUploadEnabled(context)) return

        val scheduler = context.getSystemService(JobScheduler::class.java) ?: return
        val component = ComponentName(context, CrashLogUploadJobService::class.java)

        val builder = JobInfo.Builder(JOB_ID, component)
            .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
            .setBackoffCriteria(30_000L, JobInfo.BACKOFF_POLICY_EXPONENTIAL)

        // 给崩溃日志写文件留一点时间，避免立刻上传时误判重复上报。
        builder.setMinimumLatency(5_000L)

        val result = scheduler.schedule(builder.build())
        if (result == JobScheduler.RESULT_SUCCESS) {
            Timber.tag(TAG).i("Crash log upload job scheduled")
        } else {
            Timber.tag(TAG).w("Crash log upload job schedule failed: %s", result)
        }
    }

    fun cancel(context: Context) {
        val scheduler = context.getSystemService(JobScheduler::class.java) ?: return
        scheduler.cancel(JOB_ID)
        Timber.tag(TAG).i("Crash log upload job canceled")
    }
}
