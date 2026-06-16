package com.wuxianggujun.tinaide.editor.bookmark.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.wuxianggujun.tinaide.editor.bookmark.model.Bookmark

/**
 * 书签实体（Room）
 *
 * 索引策略：
 * - 按项目查询：index on project_path
 * - 按文件查询：index on (project_path, file_path)
 * - 唯一约束：(project_path, file_path, line)
 */
@Entity(
    tableName = "bookmarks",
    indices = [
        Index(value = ["project_path"]),
        Index(value = ["project_path", "file_path"]),
        Index(value = ["project_path", "file_path", "line"], unique = true)
    ]
)
data class BookmarkEntity(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "id")
    val id: Long = 0,

    @ColumnInfo(name = "project_path")
    val projectPath: String,

    @ColumnInfo(name = "file_path")
    val filePath: String,

    @ColumnInfo(name = "line")
    val line: Int,

    @ColumnInfo(name = "note")
    val note: String = "",

    @ColumnInfo(name = "created_at")
    val createdAt: Long = System.currentTimeMillis()
) {
    /**
     * 转换为领域模型
     */
    fun toDomainModel(): Bookmark = Bookmark(
        filePath = filePath,
        line = line,
        note = note,
        createdAt = createdAt
    )

    companion object {
        /**
         * 从领域模型创建实体
         */
        fun fromDomainModel(projectPath: String, bookmark: Bookmark): BookmarkEntity = BookmarkEntity(
            projectPath = projectPath,
            filePath = bookmark.filePath,
            line = bookmark.line,
            note = bookmark.note,
            createdAt = bookmark.createdAt
        )
    }
}
