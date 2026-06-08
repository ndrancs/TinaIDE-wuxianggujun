package com.wuxianggujun.tinaide.core.compile

/**
 * 进程状态机
 *
 * 使用有限状态机模式管理进程状态转换，使状态转换更加清晰和可预测。
 *
 * **状态转换图**:
 * ```
 * IDLE ──start()──> STARTING ──started()──> RUNNING
 *                                              │
 *                   ┌──────────────────────────┘
 *                   │
 *                   ├──stop()──> STOPPING ──stopped()──> STOPPED
 *                   │                                       │
 *                   │                       ┌───────────────┘
 *                   │                       v
 *                   └──complete()────────> IDLE
 * ```
 *
 * **使用示例**:
 * ```kotlin
 * val stateMachine = ProcessStateMachine()
 *
 * // 启动进程
 * if (stateMachine.canStart()) {
 *     stateMachine.start()
 *     // ... 启动进程
 *     stateMachine.started()
 * }
 *
 * // 停止进程
 * if (stateMachine.canStop()) {
 *     stateMachine.stop()
 *     // ... 停止进程
 *     stateMachine.stopped()
 * }
 * ```
 */
class ProcessStateMachine {

    /**
     * 进程状态
     */
    enum class State {
        /** 空闲状态，没有进程运行 */
        IDLE,

        /** 正在启动进程 */
        STARTING,

        /** 进程运行中 */
        RUNNING,

        /** 正在停止进程 */
        STOPPING,

        /** 进程已停止 */
        STOPPED
    }

    /**
     * 状态转换事件
     */
    sealed class Event {
        object Start : Event()
        object Started : Event()
        object Stop : Event()
        object ForceStop : Event()
        object Stopped : Event()
        object Complete : Event()
        object Reset : Event()
    }

    /**
     * 状态转换结果
     */
    data class TransitionResult(
        val success: Boolean,
        val previousState: State,
        val currentState: State,
        val message: String? = null
    )

    @Volatile
    private var currentState: State = State.IDLE

    /**
     * 获取当前状态
     */
    fun getState(): State = currentState

    /**
     * 检查是否处于空闲状态
     */
    fun isIdle(): Boolean = currentState == State.IDLE

    /**
     * 检查是否处于运行状态
     */
    fun isRunning(): Boolean = currentState == State.RUNNING

    /**
     * 检查是否处于活跃状态（启动中、运行中或停止中）
     */
    fun isActive(): Boolean = currentState in listOf(State.STARTING, State.RUNNING, State.STOPPING)

    /**
     * 检查是否可以启动
     */
    fun canStart(): Boolean = currentState in listOf(State.IDLE, State.STOPPED)

    /**
     * 检查是否可以停止
     */
    fun canStop(): Boolean = currentState in listOf(State.RUNNING, State.STARTING)

    /**
     * 触发状态转换
     */
    @Synchronized
    fun transition(event: Event): TransitionResult {
        val previousState = currentState

        val (newState, success, message) = when (event) {
            is Event.Start -> handleStart()
            is Event.Started -> handleStarted()
            is Event.Stop -> handleStop()
            is Event.ForceStop -> handleForceStop()
            is Event.Stopped -> handleStopped()
            is Event.Complete -> handleComplete()
            is Event.Reset -> handleReset()
        }

        if (success) {
            currentState = newState
        }

        return TransitionResult(
            success = success,
            previousState = previousState,
            currentState = currentState,
            message = message
        )
    }

    // 状态转换处理方法

    private fun handleStart(): Triple<State, Boolean, String?> = when (currentState) {
        State.IDLE, State.STOPPED -> Triple(State.STARTING, true, null)
        State.STARTING -> Triple(currentState, false, "Already starting")
        State.RUNNING -> Triple(currentState, false, "Already running")
        State.STOPPING -> Triple(currentState, false, "Currently stopping")
    }

    private fun handleStarted(): Triple<State, Boolean, String?> = when (currentState) {
        State.STARTING -> Triple(State.RUNNING, true, null)
        else -> Triple(currentState, false, "Invalid transition: cannot started from $currentState")
    }

    private fun handleStop(): Triple<State, Boolean, String?> = when (currentState) {
        State.RUNNING, State.STARTING -> Triple(State.STOPPING, true, null)
        State.STOPPING -> Triple(currentState, false, "Already stopping")
        State.STOPPED -> Triple(currentState, false, "Already stopped")
        State.IDLE -> Triple(currentState, false, "Nothing to stop")
    }

    private fun handleForceStop(): Triple<State, Boolean, String?> {
        // 强制停止可以从任何状态转换到 STOPPED
        return when (currentState) {
            State.IDLE -> Triple(currentState, false, "Nothing to stop")
            else -> Triple(State.STOPPED, true, null)
        }
    }

    private fun handleStopped(): Triple<State, Boolean, String?> = when (currentState) {
        State.STOPPING, State.RUNNING, State.STARTING -> Triple(State.STOPPED, true, null)
        State.STOPPED -> Triple(currentState, false, "Already stopped")
        State.IDLE -> Triple(currentState, false, "Already idle")
    }

    private fun handleComplete(): Triple<State, Boolean, String?> = when (currentState) {
        State.RUNNING, State.STOPPED -> Triple(State.IDLE, true, null)
        else -> Triple(currentState, false, "Invalid transition: cannot complete from $currentState")
    }

    private fun handleReset(): Triple<State, Boolean, String?> {
        // 重置可以从任何状态转换到 IDLE
        return Triple(State.IDLE, true, null)
    }

    // 便捷方法

    /**
     * 开始启动进程
     */
    fun start(): TransitionResult = transition(Event.Start)

    /**
     * 进程已启动
     */
    fun started(): TransitionResult = transition(Event.Started)

    /**
     * 请求停止进程
     */
    fun stop(): TransitionResult = transition(Event.Stop)

    /**
     * 强制停止进程
     */
    fun forceStop(): TransitionResult = transition(Event.ForceStop)

    /**
     * 进程已停止
     */
    fun stopped(): TransitionResult = transition(Event.Stopped)

    /**
     * 进程完成，返回空闲状态
     */
    fun complete(): TransitionResult = transition(Event.Complete)

    /**
     * 重置状态机
     */
    fun reset(): TransitionResult = transition(Event.Reset)

    override fun toString(): String = "ProcessStateMachine(state=$currentState)"
}
