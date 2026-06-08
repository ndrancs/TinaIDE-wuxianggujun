package com.wuxianggujun.tinaide.terminal.persistence.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.wuxianggujun.tinaide.terminal.persistence.ProjectTerminalState
import com.wuxianggujun.tinaide.terminal.persistence.TerminalSessionSnapshot

/**
 * 终端状态实体（Room）
 *
 * 存储项目的终端状态：活动会话、更新时间
 */
@Entity(
    tableName = "terminal_states",
    indices = [
        Index(value = ["project_path"], unique = true)
    ]
)
data class TerminalStateEntity(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "id")
    val id: Long = 0,

    @ColumnInfo(name = "project_path")
    val projectPath: String,

    @ColumnInfo(name = "active_session_id")
    val activeSessionId: String? = null,

    @ColumnInfo(name = "updated_at")
    val updatedAt: Long = System.currentTimeMillis()
) {
    companion object {
        fun fromSnapshot(projectPath: String, snapshot: ProjectTerminalState): TerminalStateEntity = TerminalStateEntity(
            projectPath = projectPath,
            activeSessionId = snapshot.activeSessionId,
            updatedAt = snapshot.updatedAt
        )
    }
}

/**
 * 终端会话实体（Room）
 *
 * 存储单个终端会话的状态：标题、后端类型、工作目录、光标位置等
 */
@Entity(
    tableName = "terminal_sessions",
    indices = [
        Index(value = ["project_path"]),
        Index(value = ["project_path", "session_id"], unique = true)
    ]
)
data class TerminalSessionEntity(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "id")
    val id: Long = 0,

    @ColumnInfo(name = "project_path")
    val projectPath: String,

    @ColumnInfo(name = "session_id")
    val sessionId: String,

    @ColumnInfo(name = "title")
    val title: String = "Terminal",

    @ColumnInfo(name = "backend")
    val backend: String = "host",

    @ColumnInfo(name = "working_directory")
    val workingDirectory: String? = null,

    @ColumnInfo(name = "cursor_row")
    val cursorRow: Int = 0,

    @ColumnInfo(name = "cursor_column")
    val cursorColumn: Int = 0,

    @ColumnInfo(name = "rows")
    val rows: Int = 24,

    @ColumnInfo(name = "columns")
    val columns: Int = 80,

    @ColumnInfo(name = "transcript")
    val transcript: String? = null,

    @ColumnInfo(name = "transcript_lines")
    val transcriptLines: Int = 0,

    @ColumnInfo(name = "created_at")
    val createdAt: Long = 0L
) {
    fun toDomainModel(): TerminalSessionSnapshot = TerminalSessionSnapshot(
        id = sessionId,
        title = title,
        backend = backend,
        workingDirectory = workingDirectory,
        cursorRow = cursorRow,
        cursorColumn = cursorColumn,
        rows = rows,
        columns = columns,
        transcript = transcript,
        transcriptLines = transcriptLines,
        createdAt = createdAt
    )

    companion object {
        fun fromDomainModel(projectPath: String, snapshot: TerminalSessionSnapshot): TerminalSessionEntity = TerminalSessionEntity(
            projectPath = projectPath,
            sessionId = snapshot.id,
            title = snapshot.title,
            backend = snapshot.backend,
            workingDirectory = snapshot.workingDirectory,
            cursorRow = snapshot.cursorRow,
            cursorColumn = snapshot.cursorColumn,
            rows = snapshot.rows,
            columns = snapshot.columns,
            transcript = snapshot.transcript,
            transcriptLines = snapshot.transcriptLines,
            createdAt = snapshot.createdAt
        )
    }
}
