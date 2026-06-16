package com.wuxianggujun.tinaide.database.user

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * 下载历史数据库实体
 */
@Entity(tableName = "download_history")
data class DownloadHistoryEntity(
    @PrimaryKey
    @ColumnInfo(name = "id")
    val id: String,

    @ColumnInfo(name = "item_type")
    val itemType: String,

    @ColumnInfo(name = "item_id")
    val itemId: String,

    @ColumnInfo(name = "version")
    val version: String?,

    @ColumnInfo(name = "file_size")
    val fileSize: Long?,

    @ColumnInfo(name = "downloaded_at")
    val downloadedAt: String,

    @ColumnInfo(name = "synced")
    val synced: Boolean = true
)
