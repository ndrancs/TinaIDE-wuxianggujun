package com.wuxianggujun.tinaide.core.nativebridge

import androidx.annotation.Keep

/**
 * 运行共享库后的结果
 *
 * @property returnCode 原生入口函数的返回码
 * @property output 子进程捕获到的标准输出/错误
 */
@Keep
data class RunExecutionResult(
    val returnCode: Int,
    val output: String
)
