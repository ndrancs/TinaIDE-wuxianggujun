package com.wuxianggujun.tinaide.database.impl

import com.wuxianggujun.tinaide.core.serialization.JsonSerializer
import com.wuxianggujun.tinaide.core.user.DownloadHistoryItem
import com.wuxianggujun.tinaide.core.user.DownloadHistoryResponse
import com.wuxianggujun.tinaide.core.user.FavoritePlugin
import com.wuxianggujun.tinaide.core.user.FavoritesResponse
import com.wuxianggujun.tinaide.core.user.UserContentRepository
import com.wuxianggujun.tinaide.database.user.DownloadHistoryDao
import com.wuxianggujun.tinaide.database.user.DownloadHistoryEntity
import com.wuxianggujun.tinaide.database.user.FavoriteDao
import com.wuxianggujun.tinaide.database.user.FavoriteEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json

/**
 * 本地用户内容仓库实现（基于 Room）
 *
 * 架构说明：
 * - 实现 IUserContentRepository 接口（定义在 core:common）
 * - 使用 Room 数据库进行本地持久化
 * - 负责 Entity 与 Domain Model 之间的转换
 * - 遵循依赖倒置原则（DIP）
 */
class LocalUserContentRepository(
    private val favoriteDao: FavoriteDao,
    private val downloadHistoryDao: DownloadHistoryDao,
    private val json: Json = JsonSerializer.default
) : UserContentRepository {

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
        val entity = plugin.toFavoriteEntity()
        favoriteDao.insertFavorite(entity)
        Result.success(Unit)
    } catch (e: Exception) {
        Result.failure(e)
    }

    override suspend fun removeFavorite(pluginId: String): Result<Unit> = try {
        favoriteDao.deleteFavoriteByPluginId(pluginId)
        Result.success(Unit)
    } catch (e: Exception) {
        Result.failure(e)
    }

    override suspend fun isFavorite(pluginId: String): Boolean = try {
        favoriteDao.getFavoriteByPluginId(pluginId) != null
    } catch (e: Exception) {
        false
    }

    override suspend fun syncFavoritesFromServer(): Result<Unit> {
        // LocalUserContentRepository 是纯本地实现，不支持服务器同步
        return Result.failure(UnsupportedOperationException("Local repository does not support server sync"))
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
        // 需要先查询实体才能删除（Room @Delete 需要实体对象）
        val entities = downloadHistoryDao.getAllDownloads()
        val entity = entities.find { it.id == id }
        if (entity != null) {
            downloadHistoryDao.deleteDownload(entity)
        }
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

    private fun FavoriteEntity.toFavoritePlugin(): FavoritePlugin = FavoritePlugin(
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

    private fun FavoritePlugin.toFavoriteEntity(): FavoriteEntity = FavoriteEntity(
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

    private fun DownloadHistoryEntity.toDownloadHistoryItem(): DownloadHistoryItem = DownloadHistoryItem(
        id = id,
        itemType = itemType,
        itemId = itemId,
        version = version,
        downloadedAt = downloadedAt,
        fileSize = fileSize,
        synced = synced
    )

    private fun DownloadHistoryItem.toDownloadHistoryEntity(): DownloadHistoryEntity = DownloadHistoryEntity(
        id = id,
        itemType = itemType,
        itemId = itemId,
        version = version,
        fileSize = fileSize,
        downloadedAt = downloadedAt,
        synced = synced
    )
}
