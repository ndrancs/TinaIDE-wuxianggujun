package com.wuxianggujun.tinaide.core.compile.launcher

import com.wuxianggujun.tinaide.core.compile.artifact.Artifact
import com.wuxianggujun.tinaide.core.compile.event.BuildEventEmitter
import com.wuxianggujun.tinaide.core.compile.strategy.BuildContext

/**
 * Launcher:把构建产物"准备好让 UI 拉起"的抽象。
 *
 * **重要**: Launcher 本身不 spawn 进程。它只:
 * 1. 校验产物可达(文件存在、kind 匹配等)
 * 2. 发射 [com.wuxianggujun.tinaide.core.compile.event.BuildEvent.Launch] 系列事件
 * 3. 返回 [LaunchOutcome.Prepared] 携带 [LaunchDescriptor],
 *    UI 层(CompileActionsHelper)再用它打开终端 / GUI / 调试会话。
 *
 * 这个边界选择的原因:真正 spawn 终端 / SDL 图形运行时 / gdbserver 依赖大量 UI 侧资源
 * (TerminalBackend / ProcessManager / OutputManager / Activity),把它们
 * 拉进 core:compile 会反转依赖层级。
 */
interface Launcher {
    suspend fun launch(
        artifact: Artifact,
        ctx: BuildContext,
        emitter: BuildEventEmitter,
    ): LaunchOutcome
}

/** Launcher 单次准备启动的结果。 */
sealed interface LaunchOutcome {
    /** 准备就绪, [descriptor] 交由上层真正拉起。 */
    data class Prepared(val descriptor: LaunchDescriptor) : LaunchOutcome

    /** 准备阶段失败(产物丢失 / kind 不匹配等)。 */
    data class Failed(val reason: String) : LaunchOutcome
}
