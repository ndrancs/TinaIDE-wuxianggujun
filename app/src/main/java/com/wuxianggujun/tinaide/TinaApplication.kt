package com.wuxianggujun.tinaide

import android.app.Application
import com.wuxianggujun.tinaide.core.crash.CrashHandler
import com.wuxianggujun.tinaide.core.ServiceLocator
import com.wuxianggujun.tinaide.core.register
import com.wuxianggujun.tinaide.core.registerSingleton
import com.wuxianggujun.tinaide.core.config.ConfigManager
import com.wuxianggujun.tinaide.core.config.ConfigKeys
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
            val cfg = ServiceLocator.get(IConfigManager::class.java)
            val themeName = cfg.get(ConfigKeys.Theme)
            val mode = when (themeName) {
                "LIGHT" -> androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_NO
                "AUTO" -> androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
                else -> androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_YES
            }
            if (androidx.appcompat.app.AppCompatDelegate.getDefaultNightMode() != mode) {
                androidx.appcompat.app.AppCompatDelegate.setDefaultNightMode(mode)
            }
        } catch (_: Throwable) { }
        
        // 关键：先确保 sysroot 已安装（同步执行，避免后续加载失败）
        android.util.Log.i("TinaApplication", "Ensuring sysroot is installed...")
        try {
            com.wuxianggujun.tinaide.core.nativebridge.SysrootInstaller.ensureInstalled(this)
            android.util.Log.i("TinaApplication", "Sysroot ready")
        } catch (t: Throwable) {
            android.util.Log.e("TinaApplication", "Failed to install sysroot: ${t.message}", t)
            return
        }
        
        // 加载 libc++_shared.so（所有 native 库都依赖它）
        // 从 sysroot 加载，确保所有库使用同一个 C++ 运行时
        android.util.Log.i("TinaApplication", "Loading libc++_shared.so from sysroot...")
        try {
            val sysrootBase = java.io.File(filesDir, "sysroot")
            val abi = android.os.Build.SUPPORTED_ABIS.firstOrNull() ?: "arm64-v8a"
            val triple = when {
                abi.contains("arm64", true) -> "aarch64-linux-android"
                abi.contains("x86_64", true) -> "x86_64-linux-android"
                else -> "aarch64-linux-android"
            }
            // 优先从 runtime 目录加载（如果存在）
            val runtimePath = java.io.File(sysrootBase, "usr/lib/$triple/runtime/libc++_shared.so")
            val apiPath = java.io.File(sysrootBase, "usr/lib/$triple/28/libc++_shared.so")
            val libcxxPath = if (runtimePath.exists()) runtimePath else apiPath
            
            if (libcxxPath.exists()) {
                System.load(libcxxPath.absolutePath)
                android.util.Log.i("TinaApplication", "libc++_shared.so loaded from: ${libcxxPath.absolutePath}")
            } else {
                throw java.io.FileNotFoundException("libc++_shared.so not found at: $runtimePath or $apiPath")
            }
        } catch (t: Throwable) {
            android.util.Log.e("TinaApplication", "CRITICAL: Failed to load libc++_shared.so: ${t.message}", t)
            // 不要继续，因为所有其他库都会失败
            return
        }
        
        // 加载嵌入式编译器依赖（clang-cpp 等）
        android.util.Log.i("TinaApplication", "Loading NativeLoader...")
        try { 
            com.wuxianggujun.tinaide.core.nativebridge.NativeLoader.loadIfNeeded() 
            android.util.Log.i("TinaApplication", "NativeLoader loaded successfully")
        } catch (t: Throwable) {
            android.util.Log.e("TinaApplication", "Failed to load NativeCompiler: ${t.message}", t)
        }
    }

    companion object {
        lateinit var instance: TinaApplication
            private set
    }
}
