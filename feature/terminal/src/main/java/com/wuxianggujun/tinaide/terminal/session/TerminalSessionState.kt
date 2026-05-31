package com.wuxianggujun.tinaide.terminal.session

import com.termux.terminal.TerminalSession
import com.wuxianggujun.tinaide.terminal.shell.TerminalBackend
import java.util.UUID

/**
 * 终端会话状态枚举
 */
enum class SessionStatus {
    /** 正在启动 */
    STARTING,
    /** 运行中 */
    RUNNING,
    /** 已退出 */
    EXITED,
    /** 错误 */
    ERROR
}

/**
 * 基于 Termux 的终端会话状态
 *
 * 包含单个终端会话的所有状态信息
 *
 * @param id 会话唯一标识符
 * @param title 会话标题（显示在标签页上）
 * @param session Termux 的 TerminalSession
 * @param status 会话状态
 * @param createdAt 创建时间戳
 * @param exitCode 退出码（仅在 EXITED 状态时有效）
 * @param errorMessage 错误信息（仅在 ERROR 状态时有效）
 * @param shellPid Shell 进程 PID
 */
data class TerminalSessionState(
    val id: String = UUID.randomUUID().toString(),
    val title: String = "Terminal",
    val backend: TerminalBackend = TerminalBackend.HOST,
    val session: TerminalSession? = null,
    val status: SessionStatus = SessionStatus.STARTING,
    val createdAt: Long = System.currentTimeMillis(),
    val exitCode: Int? = null,
    val errorMessage: String? = null,
    val shellPid: Int = 0,
    val runExitCode: Int? = null
) {
    /**
     * 更新会话状态
     */
    fun withStatus(newStatus: SessionStatus): TerminalSessionState {
        return copy(status = newStatus)
    }

    /**
     * 标记为已退出
     */
    fun withExited(code: Int): TerminalSessionState {
        return copy(
            status = SessionStatus.EXITED,
            exitCode = code
        )
    }

    /**
     * 标记为错误
     */
    fun withError(message: String): TerminalSessionState {
        return copy(
            status = SessionStatus.ERROR,
            errorMessage = message,
            session = null
        )
    }

    /**
     * 更新标题
     */
    fun withTitle(newTitle: String): TerminalSessionState {
        return copy(title = newTitle)
    }

    /**
     * 更新 Shell PID
     */
    fun withShellPid(pid: Int): TerminalSessionState {
        return copy(shellPid = pid)
    }

    /**
     * Run 模式下：程序正常结束后记录退出码（shell 仍在阻塞，Activity 据此切换到"等待关闭"态）。
     */
    fun withRunEnded(code: Int): TerminalSessionState {
        return copy(runExitCode = code)
    }

    /**
     * 检查会话是否可以接收输入
     */
    val canReceiveInput: Boolean
        get() = status == SessionStatus.RUNNING && session != null && session.isRunning

    /**
     * 检查会话是否已结束（退出或错误）
     */
    val isTerminated: Boolean
        get() = status == SessionStatus.EXITED || status == SessionStatus.ERROR

    /**
     * 获取终端模拟器
     */
    val emulator get() = session?.emulator

    companion object {
        /**
         * 创建一个新的会话状态
         */
        fun create(
            id: String = UUID.randomUUID().toString(),
            title: String = "Terminal",
            backend: TerminalBackend = TerminalBackend.HOST
        ): TerminalSessionState {
            return TerminalSessionState(
                id = id,
                title = title,
                backend = backend
            )
        }
    }
}
