package com.wuxianggujun.tinaide.terminal.persistence

/**
 * 项目级别的终端状态
 *
 * 包含一个项目中所有终端会话的快照信息。
 */
data class ProjectTerminalState(
    /** 当前活动的会话 ID */
    val activeSessionId: String? = null,

    /** 所有终端会话的快照列表 */
    val sessions: List<TerminalSessionSnapshot> = emptyList(),

    /** 状态更新时间戳 */
    val updatedAt: Long = System.currentTimeMillis()
) {
    /**
     * 获取活动会话的快照
     */
    fun activeSessionSnapshot(): TerminalSessionSnapshot? = sessions.find { it.id == activeSessionId }

    /**
     * 规范化状态（移除无效数据）
     */
    fun normalized(currentTime: Long = System.currentTimeMillis()): ProjectTerminalState {
        val sanitizedSessions = sessions.filter { it.id.isNotBlank() }
        val sanitizedActiveId = activeSessionId?.takeIf { id ->
            sanitizedSessions.any { it.id == id }
        } ?: sanitizedSessions.firstOrNull()?.id
        val timestamp = if (updatedAt <= 0L) currentTime else updatedAt

        return copy(
            activeSessionId = sanitizedActiveId,
            sessions = sanitizedSessions,
            updatedAt = timestamp
        )
    }

    companion object {
        /** 创建空状态 */
        fun empty(): ProjectTerminalState = ProjectTerminalState()
    }
}
