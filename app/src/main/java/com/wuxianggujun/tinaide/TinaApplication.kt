package com.wuxianggujun.tinaide

import android.app.Application
import android.util.Log
import com.wuxianggujun.tinaide.core.crash.CrashHandler
import com.wuxianggujun.tinaide.core.ServiceLocator
import com.wuxianggujun.tinaide.core.register
import com.wuxianggujun.tinaide.core.registerSingleton
import com.wuxianggujun.tinaide.core.config.ConfigManager
import com.wuxianggujun.tinaide.core.config.ConfigKeys
import com.wuxianggujun.tinaide.core.config.IConfigManager
import com.wuxianggujun.tinaide.core.nativebridge.SysrootInstaller
import com.wuxianggujun.tinaide.core.nativebridge.SysrootLibraryLoader
import com.wuxianggujun.tinaide.core.nativebridge.NativeLoader
import com.wuxianggujun.tinaide.file.FileManager
import com.wuxianggujun.tinaide.file.IFileManager
import com.wuxianggujun.tinaide.utils.LogcatMonitor

class TinaApplication : Application() {

    companion object {
        private const val TAG = "TinaApplication"
        
        lateinit var instance: TinaApplication
            private set
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        
        // 启动 Logcat 监听（自动捕获所有 Android Log 输出）
        LogcatMonitor.start(packageName)
        
        // 注册全局 Crash 处理器（仅日志→交还系统处理，避免 UI 卡死）
        CrashHandler.install()
        
        // 预注册核心服务，避免页面首次进入时出现"未注册"竞态
        registerCoreServices()
        
        // 统一在 Application 阶段应用主题，避免首个 Activity 因 setDefaultNightMode 触发重建
        applyTheme()
        
        // 初始化 Native 库
        initializeNativeLibraries()

    }
    
    /**
     * 注册核心服务
     */
    private fun registerCoreServices() {
        if (!ServiceLocator.isRegistered(IConfigManager::class.java)) {
            ServiceLocator.register<IConfigManager>(ConfigManager(applicationContext))
        }
        if (!ServiceLocator.isRegistered(IFileManager::class.java)) {
            ServiceLocator.registerSingleton<IFileManager> { FileManager(applicationContext) }
        }
    }
    
    /**
     * 应用主题设置
     */
    private fun applyTheme() {
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
    }
    
    /**
     * 初始化 Native 库
     *
     * 加载顺序：
     * 1. 确保 sysroot 已安装
     * 2. 加载 libc++_shared.so（C++ 运行时，所有其他库的依赖）
     * 3. 加载 LLVM/Clang 库
     * 4. 加载 native_compiler JNI 库
     */
    private fun initializeNativeLibraries() {
        // 1. 确保 sysroot 已安装
        Log.i(TAG, "Ensuring sysroot is installed...")
        try {
            SysrootInstaller.ensureInstalled(this)
            Log.i(TAG, "Sysroot ready")
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to install sysroot: ${t.message}", t)
            return
        }
        
        // 2. 使用 SysrootLibraryLoader 加载所有编译器运行时库
        Log.i(TAG, "Loading compiler runtime libraries from sysroot...")
        val loader = SysrootLibraryLoader.getInstance(this)
        
        try {
            // 首先加载 C++ 运行时（所有其他库的依赖）
            loader.loadLibrary("libc++_shared.so")
            Log.i(TAG, "libc++_shared.so loaded successfully")
        } catch (t: Throwable) {
            Log.e(TAG, "CRITICAL: Failed to load libc++_shared.so: ${t.message}", t)
            // 不要继续，因为所有其他库都会失败
            return
        }
        
        // 3. 加载 LLVM/Clang 库
        try {
            loader.loadLibraries(
                listOf("libLLVM-17.so", "libclang-cpp.so", "libclang.so"),
                preferRuntime = true,
                stopOnError = false
            )
            Log.i(TAG, "LLVM/Clang libraries loaded")
        } catch (t: Throwable) {
            Log.w(TAG, "Some LLVM/Clang libraries failed to load: ${t.message}")
        }
        
        // 4. 加载 native_compiler JNI 库
        Log.i(TAG, "Loading NativeLoader...")
        try {
            NativeLoader.loadIfNeeded()
            Log.i(TAG, "NativeLoader loaded successfully")
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to load NativeCompiler: ${t.message}", t)
        }
        
        // 打印已加载的库
        Log.i(TAG, "Loaded libraries: ${loader.getLoadedLibraries().size}")
    }

}
