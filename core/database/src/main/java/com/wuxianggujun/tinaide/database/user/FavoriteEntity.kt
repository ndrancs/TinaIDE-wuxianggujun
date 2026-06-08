package com.wuxianggujun.tinaide.database.user

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * 收藏数据库实体
 */
@Entity(tableName = "favorites")
data class FavoriteEntity(
    @PrimaryKey
    @ColumnInfo(name = "id")
    val id: String,

    @ColumnInfo(name = "plugin_id")
    val pluginId: String,

    @ColumnInfo(name = "name")
    val name: String,

    @ColumnInfo(name = "description")
    val description: String?,

    @ColumnInfo(name = "icon_url")
    val iconUrl: String?,

    @ColumnInfo(name = "category")
    val category: String?,

    @ColumnInfo(name = "tags")
    val tags: String?, // JSON 字符串存储

    @ColumnInfo(name = "latest_version")
    val latestVersion: String?,

    @ColumnInfo(name = "added_at")
    val addedAt: String,

    @ColumnInfo(name = "synced")
    val synced: Boolean = true
)
