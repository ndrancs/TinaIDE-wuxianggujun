package com.wuxianggujun.tinaide

import android.app.Application
import com.wuxianggujun.tinaide.core.crash.CrashHandler
import com.wuxianggujun.tinaide.core.ServiceLocator
import com.wuxianggujun.tinaide.core.register
import com.wuxianggujun.tinaide.core.registerSingleton
import com.wuxianggujun.tinaide.core.config.ConfigManager
import com.wuxianggujun.tinaide.core.config.IConfigManager
import com.wuxianggujun.tinaide.file.FileManager
import com.wuxianggujun.tinaide.file.IFileManager

class TinaApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        instance = this
        // 注册全局 Crash 处理器（仅日志→交还系统处理，避免 UI 卡死）
        CrashHandler.install()
        // 预注册核心服务，避免页面首次进入时出现“未注册”竞态
        // ConfigManager / FileManager 均可用 applicationContext 初始化
        if (!ServiceLocator.isRegistered(IConfigManager::class.java)) {
            ServiceLocator.register<IConfigManager>(ConfigManager(applicationContext))
        }
        if (!ServiceLocator.isRegistered(IFileManager::class.java)) {
            ServiceLocator.registerSingleton<IFileManager> { FileManager(applicationContext) }
        }
        // 统一在 Application 阶段应用主题，避免首个 Activity 因 setDefaultNightMode 触发重建
        try {
            val cfg = ServiceLocator.get<IConfigManager>()
            val themeName = cfg.get("ui.theme", "DARK")
            val mode = when (themeName) {
                "LIGHT" -> androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_NO
                "AUTO" -> androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
                else -> androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_YES
            }
            if (androidx.appcompat.app.AppCompatDelegate.getDefaultNightMode() != mode) {
                androidx.appcompat.app.AppCompatDelegate.setDefaultNightMode(mode)
            }
        } catch (_: Throwable) { }
        // 加载嵌入式编译器依赖（clang-cpp 等）
        try { com.wuxianggujun.tinaide.core.nativebridge.NativeLoader.loadIfNeeded() } catch (_: Throwable) { }
        // 后台预安装 sysroot（优先从 assets/sysroot.zip 解压，避免首个编译时等待）
        Thread {
            try { com.wuxianggujun.tinaide.core.nativebridge.SysrootInstaller.ensureInstalled(this) } catch (_: Throwable) { }
        }.start()
    }

    companion object {
        lateinit var instance: TinaApplication
            private set
    }
}
