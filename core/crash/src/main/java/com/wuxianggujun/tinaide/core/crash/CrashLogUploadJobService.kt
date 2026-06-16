package com.wuxianggujun.tinaide.core.crash

import android.app.job.JobParameters
import android.app.job.JobService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import timber.log.Timber

class CrashLogUploadJobService : JobService() {
    companion object {
        private const val TAG = "CrashLogUploadJobService"
    }

    private val supervisor = SupervisorJob()
    private val scope = CoroutineScope(supervisor + Dispatchers.IO)
    private var running: Job? = null

    override fun onStartJob(params: JobParameters): Boolean {
        running?.cancel()
        running = scope.launch {
            val shouldReschedule = runCatching {
                CrashLogUploader.uploadPending(applicationContext)
            }.onFailure { t ->
                Timber.tag(TAG).e(t, "Crash log upload job failed")
            }.getOrDefault(true)

            jobFinished(params, shouldReschedule)
        }
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean {
        running?.cancel()
        running = null
        return true
    }

    override fun onDestroy() {
        running?.cancel()
        supervisor.cancel()
        super.onDestroy()
    }
}
