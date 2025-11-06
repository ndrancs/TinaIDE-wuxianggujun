package com.wuxianggujun.tinaide

import android.app.Application
import com.wuxianggujun.tinaide.core.crash.CrashHandler

class TinaApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        instance = this
        // 注册全局 Crash 处理器（仅日志→交还系统处理，避免 UI 卡死）
        CrashHandler.install()
    }

    companion object {
        lateinit var instance: TinaApplication
            private set
    }
}
