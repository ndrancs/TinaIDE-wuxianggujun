package com.wuxianggujun.tinaide.database.impl

import com.wuxianggujun.tinaide.core.network.ApiResult
import com.wuxianggujun.tinaide.core.network.api.UserContentApiClient
import com.wuxianggujun.tinaide.core.serialization.JsonSerializer
import com.wuxianggujun.tinaide.core.user.DownloadHistoryItem
import com.wuxianggujun.tinaide.core.user.DownloadHistoryResponse
import com.wuxianggujun.tinaide.core.user.FavoritePlugin
import com.wuxianggujun.tinaide.core.user.FavoritesResponse
import com.wuxianggujun.tinaide.core.user.UserContentRepository
import com.wuxianggujun.tinaide.database.user.DownloadHistoryDao
import com.wuxianggujun.tinaide.database.user.FavoriteDao
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json
import timber.log.Timber

/**
 * 混合用户内容仓库实现（本地 + 远程）
 *
 * 策略：
 * - 写操作：先写本地数据库（快速响应），再异步同步到服务器
 * - 读操作：优先读本地数据库，定期从服务器拉取更新
 */
class HybridUserContentRepository(
    private val favoriteDao: FavoriteDao,
    private val downloadHistoryDao: DownloadHistoryDao,
    private val apiClient: UserContentApiClient,
    private val json: Json = JsonSerializer.default
) : UserContentRepository {

    companion object {
        private const val TAG = "HybridUserContentRepo"
    }

    // ===== 收藏相关 =====

    override fun getFavoritesFlow(): Flow<List<FavoritePlugin>> = favoriteDao.getAllFavoritesFlow().map { entities ->
        entities.map { it.toFavoritePlugin() }
    }

    override suspend fun getFavorites(page: Int, pageSize: Int): Result<FavoritesResponse> = try {
        val offset = (page - 1) * pageSize
        val entities = favoriteDao.getFavoritesPaged(pageSize, offset)
        val total = favoriteDao.getFavoritesCount()

        val plugins = entities.map { it.toFavoritePlugin() }
        Result.success(
            FavoritesResponse(
                plugins = plugins,
                total = total,
                page = page,
                pageSize = pageSize
            )
        )
    } catch (e: Exception) {
        Result.failure(e)
    }

    override suspend fun addFavorite(plugin: FavoritePlugin): Result<Unit> = try {
        // 1. 先保存到本地数据库（快速响应）
        val entity = plugin.toFavoriteEntity()
        favoriteDao.insertFavorite(entity)

        // 2. 异步同步到服务器
        syncFavoriteToServer(plugin.pluginId, isAdd = true)

        Result.success(Unit)
    } catch (e: Exception) {
        Timber.tag(TAG).e(e, "addFavorite failed")
        Result.failure(e)
    }

    override suspend fun removeFavorite(pluginId: String): Result<Unit> = try {
        // 1. 先从本地数据库删除（快速响应）
        favoriteDao.deleteFavoriteByPluginId(pluginId)

        // 2. 异步同步到服务器
        syncFavoriteToServer(pluginId, isAdd = false)

        Result.success(Unit)
    } catch (e: Exception) {
        Timber.tag(TAG).e(e, "removeFavorite failed")
        Result.failure(e)
    }

    override suspend fun isFavorite(pluginId: String): Boolean = try {
        favoriteDao.getFavoriteByPluginId(pluginId) != null
    } catch (e: Exception) {
        false
    }

    /**
     * 从服务器同步收藏到本地
     */
    override suspend fun syncFavoritesFromServer(): Result<Unit> {
        return try {
            // 1. 从服务器获取所有收藏（分页获取）
            val allFavorites = mutableListOf<com.wuxianggujun.tinaide.core.network.api.FavoriteItemResponse>()
            var currentPage = 1
            var hasMore = true

            while (hasMore) {
                when (val result = apiClient.getFavorites(page = currentPage, pageSize = 100)) {
                    is ApiResult.Success -> {
                        allFavorites.addAll(result.data.favorites)
                        hasMore = currentPage * result.data.pageSize < result.data.total
                        currentPage++
                    }
                    is ApiResult.Error -> {
                        Timber.tag(TAG).e("Sync favorites from server failed: %s", result.message)
                        return Result.failure(Exception(result.message))
                    }
                    is ApiResult.NetworkError -> {
                        Timber.tag(TAG).e("Sync favorites from server network error: %s", result.message)
                        return Result.failure(Exception(result.message))
                    }
                }
            }

            // 2. 转换为本地实体
            val entities = allFavorites.map { response ->
                com.wuxianggujun.tinaide.database.user.FavoriteEntity(
                    id = response.favoriteId,
                    pluginId = response.pluginId,
                    name = response.name,
                    description = response.description,
                    iconUrl = response.iconUrl,
                    category = response.category,
                    tags = response.tags?.let { JsonSerializer.encode(it) },
                    latestVersion = response.latestVersion,
                    addedAt = response.addedAt,
                    synced = true // 从服务器来的数据标记为已同步
                )
            }

            // 3. 清空本地数据库并插入服务器数据
            favoriteDao.deleteAllFavorites()
            if (entities.isNotEmpty()) {
                favoriteDao.insertFavorites(entities)
            }

            Result.success(Unit)
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "syncFavoritesFromServer exception")
            Result.failure(e)
        }
    }

    /**
     * 同步收藏到服务器
     */
    private suspend fun syncFavoriteToServer(pluginId: String, isAdd: Boolean) {
        try {
            val result = if (isAdd) {
                apiClient.addFavorite(pluginId)
            } else {
                apiClient.removeFavorite(pluginId)
            }

            when (result) {
                is ApiResult.Success -> {
                    // 标记为已同步
                    val entity = favoriteDao.getFavoriteByPluginId(pluginId)
                    entity?.let { favoriteDao.markAsSynced(it.id) }
                }
                is ApiResult.Error -> {
                    Timber.tag(TAG).w("Sync favorite to server failed: %s", result.message)
                }
                is ApiResult.NetworkError -> {
                    Timber.tag(TAG).w("Sync favorite to server network error: %s", result.message)
                }
            }
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "syncFavoriteToServer exception")
        }
    }

    // ===== 下载历史相关 =====

    override suspend fun getDownloadHistory(page: Int, pageSize: Int): Result<DownloadHistoryResponse> = try {
        val offset = (page - 1) * pageSize
        val entities = downloadHistoryDao.getDownloadsPaged(pageSize, offset)
        val total = downloadHistoryDao.getDownloadsCount()

        val items = entities.map { it.toDownloadHistoryItem() }
        Result.success(
            DownloadHistoryResponse(
                items = items,
                total = total,
                page = page,
                pageSize = pageSize
            )
        )
    } catch (e: Exception) {
        Result.failure(e)
    }

    override suspend fun addDownloadHistory(item: DownloadHistoryItem): Result<Unit> = try {
        val entity = item.toDownloadHistoryEntity()
        downloadHistoryDao.insertDownload(entity)
        Result.success(Unit)
    } catch (e: Exception) {
        Result.failure(e)
    }

    override suspend fun removeDownloadHistory(id: String): Result<Unit> = try {
        downloadHistoryDao.deleteDownloadById(id)
        Result.success(Unit)
    } catch (e: Exception) {
        Result.failure(e)
    }

    override suspend fun clearDownloadHistory(): Result<Unit> = try {
        downloadHistoryDao.deleteAllDownloads()
        Result.success(Unit)
    } catch (e: Exception) {
        Result.failure(e)
    }

    // ===== Entity <-> Domain Model 转换 =====

    private fun com.wuxianggujun.tinaide.database.user.FavoriteEntity.toFavoritePlugin(): FavoritePlugin = FavoritePlugin(
        id = id,
        pluginId = pluginId,
        name = name,
        description = description,
        iconUrl = iconUrl,
        category = category,
        tags = tags?.let { JsonSerializer.decodeOrNull<List<String>>(it) ?: emptyList() },
        latestVersion = latestVersion,
        addedAt = addedAt,
        synced = synced
    )

    private fun FavoritePlugin.toFavoriteEntity(): com.wuxianggujun.tinaide.database.user.FavoriteEntity = com.wuxianggujun.tinaide.database.user.FavoriteEntity(
        id = id,
        pluginId = pluginId,
        name = name,
        description = description,
        iconUrl = iconUrl,
        category = category,
        tags = tags?.let { JsonSerializer.encode(it) },
        latestVersion = latestVersion,
        addedAt = addedAt,
        synced = synced
    )

    private fun com.wuxianggujun.tinaide.database.user.DownloadHistoryEntity.toDownloadHistoryItem(): DownloadHistoryItem = DownloadHistoryItem(
        id = id,
        itemType = itemType,
        itemId = itemId,
        version = version,
        downloadedAt = downloadedAt,
        fileSize = fileSize,
        synced = synced
    )

    private fun DownloadHistoryItem.toDownloadHistoryEntity(): com.wuxianggujun.tinaide.database.user.DownloadHistoryEntity = com.wuxianggujun.tinaide.database.user.DownloadHistoryEntity(
        id = id,
        itemType = itemType,
        itemId = itemId,
        version = version,
        downloadedAt = downloadedAt,
        fileSize = fileSize,
        synced = synced
    )
}
