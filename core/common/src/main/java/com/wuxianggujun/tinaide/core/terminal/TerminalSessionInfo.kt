package com.wuxianggujun.tinaide.core.terminal

/**
 * 终端后端类型
 *
 * 架构说明：
 * - 枚举定义在 core:common 层
 * - feature:terminal 层的 TerminalBackend 需要映射到此类型
 */
enum class TerminalBackend {
    /** 宿主环境（Android 原生） */
    HOST,

    /** PRoot 环境（Linux 模拟） */
    PROOT
}

/**
 * 终端会话状态枚举
 *
 * 架构说明：
 * - 枚举定义在 core:common 层
 * - feature:terminal 层的 SessionStatus 需要映射到此类型
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
 * 终端会话状态信息
 *
 * 架构说明：
 * - 数据模型定义在 core:common 层
 * - feature:terminal 层的 TerminalSessionState 需要转换为此类型
 * - app 层通过此类型访问终端会话状态
 *
 * @param id 会话唯一标识符
 * @param title 会话标题（显示在标签页上）
 * @param backend 终端后端类型
 * @param status 会话状态
 * @param createdAt 创建时间戳
 * @param exitCode 退出码（仅在 EXITED 状态时有效）
 * @param errorMessage 错误信息（仅在 ERROR 状态时有效）
 * @param shellPid Shell 进程 PID
 * @param canReceiveInput 是否可以接收输入
 * @param isTerminated 是否已结束（退出或错误）
 */
data class TerminalSessionInfo(
    val id: String,
    val title: String,
    val backend: TerminalBackend,
    val status: SessionStatus,
    val createdAt: Long,
    val exitCode: Int? = null,
    val errorMessage: String? = null,
    val shellPid: Int = 0,
    val canReceiveInput: Boolean = false,
    val isTerminated: Boolean = false,
    /** Run 模式：程序已结束但 shell 仍阻塞等待 app 关闭时的退出码。非 Run 模式始终为 null。 */
    val runExitCode: Int? = null
)
