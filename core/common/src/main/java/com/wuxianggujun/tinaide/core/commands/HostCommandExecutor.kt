package com.wuxianggujun.tinaide.core.commands

import java.io.File

/**
 * 宿主命令执行器（插件/快捷键等统一入口）。
 *
 * 说明：
 * - HostCommands 只负责“白名单声明”；
 * - HostCommandExecutor 负责“实际执行逻辑（handler）”。
 */
interface HostCommandExecutor {
    fun execute(commandId: String, invocation: HostCommandInvocation = HostCommandInvocation()): Boolean
}

data class HostCommandInvocation(
    val file: File? = null,
    val isDirectory: Boolean? = null,
    val isDirty: Boolean? = null
)
