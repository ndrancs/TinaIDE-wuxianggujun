package com.wuxianggujun.tinaide.editor.session.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.wuxianggujun.tinaide.editor.session.ProjectSessionFileSnapshot
import com.wuxianggujun.tinaide.editor.session.ProjectSessionSnapshot

/**
 * 编辑器会话实体（Room）
 *
 * 存储项目的编辑器状态：打开的文件、活动文件、更新时间
 */
@Entity(
    tableName = "editor_sessions",
    indices = [
        Index(value = ["project_path"], unique = true)
    ]
)
data class EditorSessionEntity(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "id")
    val id: Long = 0,

    @ColumnInfo(name = "project_path")
    val projectPath: String,

    @ColumnInfo(name = "active_file")
    val activeFile: String? = null,

    @ColumnInfo(name = "updated_at")
    val updatedAt: Long = System.currentTimeMillis()
) {
    companion object {
        fun fromSnapshot(projectPath: String, snapshot: ProjectSessionSnapshot): EditorSessionEntity = EditorSessionEntity(
            projectPath = projectPath,
            activeFile = snapshot.activeFile,
            updatedAt = snapshot.updatedAt
        )
    }
}

/**
 * 编辑器文件状态实体（Room）
 *
 * 存储单个文件的编辑器状态：光标位置、滚动位置
 */
@Entity(
    tableName = "editor_file_states",
    indices = [
        Index(value = ["project_path"]),
        Index(value = ["project_path", "file_path"], unique = true)
    ]
)
data class EditorFileStateEntity(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "id")
    val id: Long = 0,

    @ColumnInfo(name = "project_path")
    val projectPath: String,

    @ColumnInfo(name = "file_path")
    val filePath: String,

    @ColumnInfo(name = "cursor_line")
    val cursorLine: Int = 0,

    @ColumnInfo(name = "cursor_column")
    val cursorColumn: Int = 0,

    @ColumnInfo(name = "scroll_x")
    val scrollX: Int = 0,

    @ColumnInfo(name = "scroll_y")
    val scrollY: Int = 0
) {
    fun toDomainModel(): ProjectSessionFileSnapshot = ProjectSessionFileSnapshot(
        path = filePath,
        cursorLine = cursorLine,
        cursorColumn = cursorColumn,
        scrollX = scrollX,
        scrollY = scrollY
    )

    companion object {
        fun fromDomainModel(projectPath: String, snapshot: ProjectSessionFileSnapshot): EditorFileStateEntity = EditorFileStateEntity(
            projectPath = projectPath,
            filePath = snapshot.path,
            cursorLine = snapshot.cursorLine,
            cursorColumn = snapshot.cursorColumn,
            scrollX = snapshot.scrollX,
            scrollY = snapshot.scrollY
        )
    }
}
