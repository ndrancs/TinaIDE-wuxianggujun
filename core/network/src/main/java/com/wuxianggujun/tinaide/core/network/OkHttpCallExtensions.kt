package com.wuxianggujun.tinaide.core.network

import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.Job
import okhttp3.Call
import okhttp3.Response

/**
 * 以「协程可取消」的方式执行一个 OkHttp [Call] 并返回其 [Response]。
 *
 * 直接调用 [Call.execute] 是阻塞的：协程被取消时只会置标志位，线程仍会卡在
 * connect/read 上直到超时（download 客户端为 30~60s），体感就是「点了半天没反应」。
 * 本扩展把 [Call] 的生命周期绑定到当前协程——协程一旦被取消，立即关闭底层 socket，
 * 使阻塞的 execute()/read() 立刻抛出 IOException 退出，从而实现
 * 「点击取消即断开网络连接，重新点击即重新建连」。
 *
 * 绑定通过 `Job.invokeOnCompletion { call.cancel() }` 实现，覆盖「建连 + 拿响应头 +
 * 读取响应体」整个过程：正常完成时 call 已结束，cancel() 为 no-op；取消时关闭 socket
 * 让仍在阻塞的 execute()/read() 立刻抛错退出。
 *
 * 调用方拿到 [Response] 后请自行 `use { }` 或在读取循环里 `ensureActive()`，使取消以
 * `CancellationException` 形式干净退出。注册的回调随当前协程 Job 完成自动释放。
 */
suspend fun Call.executeCancellable(): Response {
    coroutineContext[Job]?.invokeOnCompletion { cancel() }
    return execute()
}
