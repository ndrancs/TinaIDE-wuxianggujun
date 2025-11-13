package com.wuxianggujun.tinaide.core.config

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.wuxianggujun.tinaide.core.ServiceLifecycle
import org.json.JSONObject
import java.io.File

/**
 * 配置管理器实现
 * 使用 SharedPreferences 存储简单配置，JSON 文件存储复杂配置
 */
class ConfigManager(private val context: Context) : IConfigManager, ServiceLifecycle {
    companion object {
        private const val TAG = "ConfigManager"
        private const val PREFS_NAME = "tinaide_config"
        private const val CONFIG_FILE_NAME = "config.json"
    }
    
    private val sharedPrefs: SharedPreferences by lazy {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }
    
    private val configFile: File by lazy {
        File(context.filesDir, CONFIG_FILE_NAME)
    }
    
    private val listeners = mutableMapOf<String, MutableList<ConfigChangeListener>>()
    private val jsonConfig = mutableMapOf<String, Any?>()
    
    override fun onCreate() {
        loadJsonConfig()
    }
    
    override fun onDestroy() {
        saveJsonConfig()
        listeners.clear()
    }
    
    @Suppress("UNCHECKED_CAST")
    override fun <T> get(key: String, default: T): T {
        return try {
            // 先从 JSON 配置中查找
            if (jsonConfig.containsKey(key)) {
                return (jsonConfig[key] as? T) ?: default
            }
            
            // 再从 SharedPreferences 中查找
            when (default) {
                is String -> sharedPrefs.getString(key, default) as T
                is Int -> sharedPrefs.getInt(key, default) as T
                is Long -> sharedPrefs.getLong(key, default) as T
                is Float -> sharedPrefs.getFloat(key, default) as T
                is Boolean -> sharedPrefs.getBoolean(key, default) as T
                else -> {
                    Log.w(TAG, "Unsupported type for key: $key, returning default")
                    default
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error getting config for key: $key", e)
            default
        }
    }

    override fun <T> get(key: ConfigKey<T>): T {
        return get(key.key, key.default)
    }
    
    override fun <T> set(key: String, value: T) {
        try {
            val oldValue = get(key, value)
            
            // 根据类型选择存储方式
            when (value) {
                is String, is Int, is Long, is Float, is Boolean -> {
                    // 简单类型存储到 SharedPreferences
                    with(sharedPrefs.edit()) {
                        when (value) {
                            is String -> putString(key, value)
                            is Int -> putInt(key, value)
                            is Long -> putLong(key, value)
                            is Float -> putFloat(key, value)
                            is Boolean -> putBoolean(key, value)
                        }
                        apply()
                    }
                }
                else -> {
                    // 复杂类型存储到 JSON 配置
                    jsonConfig[key] = value
                    saveJsonConfig()
                }
            }
            
            // 通知监听器
            if (oldValue != value) {
                notifyListeners(key, value)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error setting config for key: $key", e)
        }
    }

    override fun <T> set(key: ConfigKey<T>, value: T) {
        set(key.key, value)
    }
    
    override fun remove(key: String) {
        try {
            // 从 SharedPreferences 中删除
            with(sharedPrefs.edit()) {
                remove(key)
                apply()
            }
            
            // 从 JSON 配置中删除
            if (jsonConfig.remove(key) != null) {
                saveJsonConfig()
            }
            
            // 通知监听器
            notifyListeners(key, null)
        } catch (e: Exception) {
            Log.e(TAG, "Error removing config for key: $key", e)
        }
    }
    
    override fun clear() {
        try {
            // 清除 SharedPreferences
            with(sharedPrefs.edit()) {
                clear()
                apply()
            }
            
            // 清除 JSON 配置
            jsonConfig.clear()
            saveJsonConfig()
            
            // 通知所有监听器
            listeners.keys.forEach { key ->
                notifyListeners(key, null)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error clearing config", e)
        }
    }
    
    override fun addListener(key: String, listener: ConfigChangeListener) {
        listeners.getOrPut(key) { mutableListOf() }.add(listener)
    }
    
    override fun removeListener(key: String, listener: ConfigChangeListener) {
        listeners[key]?.remove(listener)
    }
    
    override fun exportConfig(): String {
        return try {
            val json = JSONObject()
            
            // 导出 SharedPreferences
            val prefsJson = JSONObject()
            sharedPrefs.all.forEach { (key, value) ->
                prefsJson.put(key, value)
            }
            json.put("preferences", prefsJson)
            
            // 导出 JSON 配置
            val jsonConfigObj = JSONObject()
            jsonConfig.forEach { (key, value) ->
                jsonConfigObj.put(key, value)
            }
            json.put("jsonConfig", jsonConfigObj)
            
            json.toString(2)
        } catch (e: Exception) {
            Log.e(TAG, "Error exporting config", e)
            "{}"
        }
    }
    
    override fun importConfig(json: String) {
        try {
            val jsonObj = JSONObject(json)
            
            // 导入 SharedPreferences
            if (jsonObj.has("preferences")) {
                val prefsJson = jsonObj.getJSONObject("preferences")
                with(sharedPrefs.edit()) {
                    prefsJson.keys().forEach { key ->
                        val value = prefsJson.get(key)
                        when (value) {
                            is String -> putString(key, value)
                            is Int -> putInt(key, value)
                            is Long -> putLong(key, value)
                            is Float -> putFloat(key, value.toFloat())
                            is Double -> putFloat(key, value.toFloat())
                            is Boolean -> putBoolean(key, value)
                        }
                    }
                    apply()
                }
            }
            
            // 导入 JSON 配置
            if (jsonObj.has("jsonConfig")) {
                val jsonConfigObj = jsonObj.getJSONObject("jsonConfig")
                jsonConfigObj.keys().forEach { key ->
                    jsonConfig[key] = jsonConfigObj.get(key)
                }
                saveJsonConfig()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error importing config", e)
        }
    }
    
    private fun loadJsonConfig() {
        try {
            if (configFile.exists()) {
                val json = JSONObject(configFile.readText())
                json.keys().forEach { key ->
                    jsonConfig[key] = json.get(key)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error loading JSON config", e)
        }
    }
    
    private fun saveJsonConfig() {
        try {
            val json = JSONObject()
            jsonConfig.forEach { (key, value) ->
                json.put(key, value)
            }
            configFile.writeText(json.toString(2))
        } catch (e: Exception) {
            Log.e(TAG, "Error saving JSON config", e)
        }
    }
    
    private fun notifyListeners(key: String, newValue: Any?) {
        listeners[key]?.forEach { listener ->
            try {
                listener.onConfigChanged(key, newValue)
            } catch (e: Exception) {
                Log.e(TAG, "Error notifying listener for key: $key", e)
            }
        }
    }
}
