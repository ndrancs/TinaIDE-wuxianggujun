package com.wuxianggujun.tinaide.terminal.persistence

/**
 * 可序列化的终端会话快照
 *
 * 用于持久化终端会话的元数据，以便在应用重启后恢复终端状态。
 * 注意：运行中的进程状态无法保存，只能保存元数据。
 */
data class TerminalSessionSnapshot(
    /** 会话唯一标识符 */
    val id: String = "",

    /** 终端标题 */
    val title: String = "Terminal",

    /** 终端后端类型（"host" 或 "proot"） */
    val backend: String = "host",

    /** 工作目录（如果可用） */
    val workingDirectory: String? = null,

    /** 光标行位置 */
    val cursorRow: Int = 0,

    /** 光标列位置 */
    val cursorColumn: Int = 0,

    /** 终端行数 */
    val rows: Int = 24,

    /** 终端列数 */
    val columns: Int = 80,

    /** Transcript 文本内容（历史输出，可选） */
    val transcript: String? = null,

    /** Transcript 行数 */
    val transcriptLines: Int = 0,

    /** 会话创建时间戳 */
    val createdAt: Long = 0L,

    /** 快照保存时间戳 */
    val savedAt: Long = 0L
) {
    companion object {
        /** Transcript 最大长度（字符数），避免 JSON 文件过大 */
        const val MAX_TRANSCRIPT_LENGTH = 50_000

        /** 最多保存多少行历史（不含当前屏幕），避免生成 transcript 时卡顿 */
        const val MAX_TRANSCRIPT_ROWS_TO_SAVE = 300
    }
}
