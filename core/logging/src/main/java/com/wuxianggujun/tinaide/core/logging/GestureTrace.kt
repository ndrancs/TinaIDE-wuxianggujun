package com.wuxianggujun.tinaide.core.logging

import timber.log.Timber

/**
 * Compose 手势链路追踪日志（开发者选项）。
 *
 * 目标：定位"编辑器滑动被打断 / ACTION_CANCEL"问题时，到底是哪一层在消费/拦截手势。
 * 默认关闭，避免影响性能与日志量。
 *
 * 调用方通过 [enabledProvider] 注入启用判断逻辑，消除对 Prefs 的硬依赖。
 */
object GestureTrace {

    private const val TAG = "GestureTrace"

    /**
     * 启用判断函数，由外部注入。
     * 默认始终返回 false（关闭状态）。
     */
    var enabledProvider: () -> Boolean = { false }

    fun isEnabled(): Boolean = enabledProvider()

    fun w(component: String, message: String) {
        if (!isEnabled()) return
        Timber.tag(TAG).w("[%s] %s", component, message)
    }

    fun d(component: String, message: String) {
        if (!isEnabled()) return
        Timber.tag(TAG).d("[%s] %s", component, message)
    }
}
