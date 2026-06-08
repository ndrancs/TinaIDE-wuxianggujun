package com.wuxianggujun.tinaide.database.worker

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.wuxianggujun.tinaide.core.network.ApiResult
import com.wuxianggujun.tinaide.core.network.api.UserContentApiClient
import com.wuxianggujun.tinaide.database.user.FavoriteDao
import java.util.concurrent.TimeUnit
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import timber.log.Timber

/**
 * 收藏同步 Worker
 *
 * 定期同步未同步的收藏到服务器
 */
class FavoriteSyncWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params),
    KoinComponent {

    private val favoriteDao: FavoriteDao by inject()
    private val apiClient: UserContentApiClient by inject()

    companion object {
        private const val TAG = "FavoriteSyncWorker"
        private const val WORK_NAME = "favorite_sync_work"

        /**
         * 调度定期同步任务（每 6 小时执行一次）
         */
        fun schedule(context: Context) {
            val workRequest = PeriodicWorkRequestBuilder<FavoriteSyncWorker>(
                repeatInterval = 6,
                repeatIntervalTimeUnit = TimeUnit.HOURS
            ).build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                workRequest
            )
        }

        /**
         * 取消定期同步任务
         */
        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
        }
    }

    override suspend fun doWork(): Result {
        return try {
            // 获取所有未同步的收藏
            val unsyncedFavorites = favoriteDao.getUnsyncedFavorites()

            if (unsyncedFavorites.isEmpty()) {
                return Result.success()
            }

            // 逐个同步到服务器
            var successCount = 0
            var failureCount = 0

            for (favorite in unsyncedFavorites) {
                val result = apiClient.addFavorite(favorite.pluginId)

                when (result) {
                    is ApiResult.Success -> {
                        // 标记为已同步
                        favoriteDao.markAsSynced(favorite.id)
                        successCount++
                    }
                    is ApiResult.Error -> {
                        failureCount++
                        Timber.tag(TAG).w("Failed to sync favorite %s: %s", favorite.pluginId, result.message)
                    }
                    is ApiResult.NetworkError -> {
                        failureCount++
                        Timber.tag(TAG).w("Network error syncing favorite %s: %s", favorite.pluginId, result.message)
                    }
                }
            }

            if (failureCount > 0) {
                Timber.tag(TAG).w("Favorite sync completed with failures: %s success, %s failures", successCount, failureCount)
            }

            // 如果有失败的，返回 retry（WorkManager 会自动重试）
            if (failureCount > 0 && successCount == 0) {
                Result.retry()
            } else {
                Result.success()
            }
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Favorite sync work failed")
            Result.retry()
        }
    }
}
