package com.wuxianggujun.tinaide.core.compile.action

import com.wuxianggujun.tinaide.core.compile.OutputMode
import java.io.File

/**
 * 启动意图:用户对"构建完成后做什么"的选择,与 [BuildIntent] 正交。
 *
 * 仅表达意图,不含启动细节(命令、环境变量、programPath 等由 Launcher 基于 Artifact 构造)。
 */
sealed interface LaunchIntent {
    /** 不启动,仅完成构建。 */
    data object None : LaunchIntent

    /**
     * 运行产物。
     *
     * @property outputMode 控制启动载体(TERMINAL / SDL),与 [RunConfiguration] 一致
     */
    data class Run(val outputMode: OutputMode) : LaunchIntent

    /**
     * 调试产物。具体 debug 会话参数由 DebugLauncher 内部基于 Artifact 构造。
     */
    data object Debug : LaunchIntent

    /**
     * 在终端中打开并执行产物。
     *
     * @property workingDir 可选工作目录,null 使用产物所在目录
     */
    data class Terminal(val workingDir: File? = null) : LaunchIntent
}
