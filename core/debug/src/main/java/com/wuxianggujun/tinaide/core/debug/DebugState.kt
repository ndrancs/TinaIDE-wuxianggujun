package com.wuxianggujun.tinaide.core.debug

/**
 * 调试会话状态机
 *
 * 状态转换:
 * - Idle -> Starting: 用户点击调试
 * - Starting -> Paused: 目标就绪/首次断点
 * - Starting -> Terminated: 启动失败
 * - Paused -> Running: Continue/Step
 * - Running -> Paused: 命中断点/单步完成
 * - Running -> Terminated: 程序退出/崩溃
 * - Paused -> Terminated: 用户停止
 * - * -> Idle: 会话清理完成
 */
sealed class DebugState {

    /** 空闲状态，无调试任务 */
    object Idle : DebugState()

    /** 正在启动调试会话 */
    data class Starting(
        val sessionId: String,
        val soPath: String,
        val entrySymbol: String
    ) : DebugState()

    /** 目标暂停（断点/单步/首次入口） */
    data class Paused(
        val sessionId: String,
        val reason: PauseReason,
        val location: SourceLocation? = null,
        val threadId: Int = 0
    ) : DebugState()

    /** 目标正在执行 */
    data class Running(
        val sessionId: String
    ) : DebugState()

    /** 会话已终止 */
    data class Terminated(
        val sessionId: String,
        val reason: TerminateReason,
        val exitCode: Int? = null,
        val message: String? = null
    ) : DebugState()
}

/** 暂停原因 */
enum class PauseReason {
    ENTRY, // 首次入口
    BREAKPOINT, // 命中断点
    STEP, // 单步完成
    EXCEPTION, // 异常
    SIGNAL, // 信号
    USER_REQUEST // 用户请求暂停
}

/** 终止原因 */
enum class TerminateReason {
    NORMAL_EXIT, // 正常退出
    CRASH, // 崩溃
    TIMEOUT, // 超时
    USER_STOP, // 用户停止
    ERROR // 内部错误
}

/** 源码位置 */
data class SourceLocation(
    val file: String,
    val line: Int,
    val column: Int = 0,
    val function: String? = null,
    val address: Long = 0
)

/** 断点信息 */
data class Breakpoint(
    val id: Int,
    val file: String,
    val line: Int,
    val enabled: Boolean = true,
    val condition: String? = null,
    val hitCount: Int = 0,
    val address: Long = 0,
    val verified: Boolean = false
)

/** 调用栈帧 */
data class StackFrame(
    val id: Int,
    val name: String,
    val file: String?,
    val line: Int?,
    val address: Long
)

/** 变量信息 */
data class Variable(
    val name: String,
    val value: String,
    val type: String?,
    val variablesReference: Int = 0
)
