package com.wuxianggujun.tinaide.core.compile.launcher

import com.wuxianggujun.tinaide.core.compile.artifact.Artifact
import java.io.File

/**
 * Launch 描述符:告知 UI 层"要启动什么、怎么启动",
 * 但不包含实际的 shell 命令组装(终端启动时 UI 调 TerminalCommandBuilder 完成)。
 *
 * 对应旧 `CompileProjectUseCase.LaunchSpec` 的语义,但迁移到新架构。
 */
sealed interface LaunchDescriptor {
    val artifact: Artifact

    /** 直接原生启动(LOG 输出模式,不常用,保留给 ExecutionOutcome.Run+LOG 走)。 */
    data class Native(
        override val artifact: Artifact,
        val outputPath: String,
    ) : LaunchDescriptor

    /** SDL 图形运行时加载 .so 运行。 */
    data class Gui(
        override val artifact: Artifact,
        val libraryPath: String,
    ) : LaunchDescriptor

    /** 启动调试会话(gdb / lldb)。 */
    data class Debug(
        override val artifact: Artifact,
        val programPath: String,
        val workingDir: String,
    ) : LaunchDescriptor

    /**
     * 在终端中运行可执行文件。
     *
     * UI 层(CompileActionsHelper)拿到后用 TerminalCommandBuilder 组装实际 shell 命令,
     * 含 sysroot LD_LIBRARY_PATH / staged copy / wait-for-enter 等策略。
     */
    data class Terminal(
        override val artifact: Artifact,
        val runnablePath: String,
        val workingDir: File,
        val args: List<String> = emptyList(),
    ) : LaunchDescriptor
}
