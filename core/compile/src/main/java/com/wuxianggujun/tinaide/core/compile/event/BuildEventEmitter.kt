package com.wuxianggujun.tinaide.core.compile.event

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * 事件发射器:把 [BuildEvent] 发送能力与订阅能力显式分离,
 * 方便 Strategy 层只拿到发射能力,不碰订阅端。
 */
interface BuildEventEmitter {
    suspend fun emit(event: BuildEvent)
    fun tryEmit(event: BuildEvent): Boolean
}

/**
 * 基于 [MutableSharedFlow] 的默认实现。订阅端可通过 [events] 暴露的只读 [SharedFlow] 观察。
 *
 * 默认 buffer 64:足够 Build + Launch 两阶段的细粒度事件突发,不会 back-pressure 阻塞生产者。
 */
class SharedFlowBuildEventEmitter(
    replay: Int = 0,
    extraBufferCapacity: Int = 64,
) : BuildEventEmitter {

    private val mutableEvents = MutableSharedFlow<BuildEvent>(
        replay = replay,
        extraBufferCapacity = extraBufferCapacity,
    )

    val events: SharedFlow<BuildEvent> = mutableEvents.asSharedFlow()

    override suspend fun emit(event: BuildEvent) = mutableEvents.emit(event)

    override fun tryEmit(event: BuildEvent): Boolean = mutableEvents.tryEmit(event)
}
