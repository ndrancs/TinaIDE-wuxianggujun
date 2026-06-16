package com.wuxianggujun.tinaide.storage.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.wuxianggujun.tinaide.storage.ProjectLocation

/**
 * 项目位置实体（Room）
 *
 * 存储项目的路径映射信息
 */
@Entity(
    tableName = "project_locations",
    indices = [
        Index(value = ["project_id"], unique = true),
        Index(value = ["source_root_path"], unique = true)
    ]
)
data class ProjectLocationEntity(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "id")
    val id: Long = 0,

    @ColumnInfo(name = "project_id")
    val projectId: String,

    @ColumnInfo(name = "project_dir_name")
    val projectDirName: String,

    @ColumnInfo(name = "source_root_path")
    val sourceRootPath: String = "",

    @ColumnInfo(name = "registered")
    val registered: Long = System.currentTimeMillis()
) {
    fun toDomainModel(): ProjectLocation = ProjectLocation(
        projectId = projectId,
        projectDirName = projectDirName,
        sourceRootPath = sourceRootPath,
        registered = registered
    )

    companion object {
        const val LEGACY_PENDING_SOURCE_ROOT_PREFIX = "__legacy_pending__/"

        fun fromDomainModel(location: ProjectLocation): ProjectLocationEntity = ProjectLocationEntity(
            projectId = location.projectId,
            projectDirName = location.projectDirName,
            sourceRootPath = location.sourceRootPath,
            registered = location.registered
        )
    }
}
