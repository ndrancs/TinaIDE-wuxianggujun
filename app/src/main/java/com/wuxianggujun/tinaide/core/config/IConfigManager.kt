package com.wuxianggujun.tinaide.core.config

/**
 * 配置管理器接口
 * 负责应用配置和用户偏好设置的持久化
 */
interface IConfigManager {
    /**
     * 获取配置值（字符串键）
     */
    fun <T> get(key: String, default: T): T

    /**
     * 获取配置值（类型安全键）
     */
    fun <T> get(key: ConfigKey<T>): T
    
    /**
     * 设置配置值（字符串键）
     */
    fun <T> set(key: String, value: T)

    /**
     * 设置配置值（类型安全键）
     */
    fun <T> set(key: ConfigKey<T>, value: T)
    
    /**
     * 删除配置项
     */
    fun remove(key: String)
    
    /**
     * 清除所有配置
     */
    fun clear()
    
    /**
     * 添加配置变更监听器
     */
    fun addListener(key: String, listener: ConfigChangeListener)
    
    /**
     * 移除配置变更监听器
     */
    fun removeListener(key: String, listener: ConfigChangeListener)
    
    /**
     * 导出配置为 JSON
     */
    fun exportConfig(): String
    
    /**
     * 从 JSON 导入配置
     */
    fun importConfig(json: String)
}

/**
 * 配置变更监听器
 */
interface ConfigChangeListener {
    fun onConfigChanged(key: String, newValue: Any?)
}
