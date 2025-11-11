package com.wuxianggujun.tinaide.plugin

import android.content.Context
import com.wuxianggujun.tinaide.utils.Logger
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * 插件管理器实现
 * 单例模式，管理所有语言插件
 */
class PluginManager private constructor() : IPluginManager {
    
    private val plugins = ConcurrentHashMap<String, ILanguagePlugin>()
    private val disabledPlugins = mutableSetOf<String>()
    
    companion object {
        @Volatile
        private var instance: PluginManager? = null
        
        fun getInstance(): PluginManager {
            return instance ?: synchronized(this) {
                instance ?: PluginManager().also { instance = it }
            }
        }
    }
    
    /**
     * 初始化插件系统
     */
    fun initialize(context: Context) {
        Logger.i("PluginManager initializing...")
        
        // 注册内置插件
        registerBuiltinPlugins(context)
        
        // TODO: 扫描并加载外部插件（从 plugins 目录）
        // loadExternalPlugins(context)
        
        Logger.i("PluginManager initialized with ${plugins.size} plugins")
    }
    
    /**
     * 注册内置插件
     */
    private fun registerBuiltinPlugins(context: Context) {
        // C/C++ 插件（内置）
        val cppPlugin = CppLanguagePlugin()
        cppPlugin.initialize(context)
        registerPlugin(cppPlugin)
        
        // 可以在这里添加更多内置插件
        // val pythonPlugin = PythonLanguagePlugin()
        // pythonPlugin.initialize(context)
        // registerPlugin(pythonPlugin)
    }
    
    override fun registerPlugin(plugin: ILanguagePlugin) {
        Logger.i("Registering plugin: ${plugin.name} v${plugin.version}")
        plugins[plugin.id] = plugin
    }
    
    override fun unregisterPlugin(pluginId: String) {
        plugins.remove(pluginId)?.dispose()
        Logger.i("Unregistered plugin: $pluginId")
    }
    
    override fun getPlugin(language: String): ILanguagePlugin? {
        return plugins.values.find { plugin ->
            plugin.id.equals(language, ignoreCase = true) ||
            plugin.name.equals(language, ignoreCase = true)
        }
    }
    
    override fun getAllPlugins(): List<ILanguagePlugin> {
        return plugins.values.toList()
    }
    
    override fun getPluginForFile(file: File): ILanguagePlugin? {
        val extension = file.extension.lowercase()
        return plugins.values.find { plugin ->
            extension in plugin.supportedExtensions && isPluginEnabled(plugin.id)
        }
    }
    
    override fun enablePlugin(pluginId: String) {
        disabledPlugins.remove(pluginId)
        Logger.i("Enabled plugin: $pluginId")
    }
    
    override fun disablePlugin(pluginId: String) {
        disabledPlugins.add(pluginId)
        Logger.i("Disabled plugin: $pluginId")
    }
    
    override fun isPluginEnabled(pluginId: String): Boolean {
        return pluginId !in disabledPlugins
    }
    
    /**
     * 清理所有插件
     */
    fun shutdown() {
        Logger.i("Shutting down PluginManager...")
        plugins.values.forEach { it.dispose() }
        plugins.clear()
    }
}
