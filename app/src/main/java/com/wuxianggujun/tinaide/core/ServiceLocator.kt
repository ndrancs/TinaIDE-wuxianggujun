package com.wuxianggujun.tinaide.core

import android.util.Log

/**
 * 服务生命周期接口
 * 实现此接口的服务可以接收生命周期回调
 */
interface ServiceLifecycle {
    /**
     * 服务初始化
     */
    fun onCreate()
    
    /**
     * 服务销毁
     */
    fun onDestroy()
}

/**
 * 轻量级依赖注入容器
 * 用于管理应用中各个服务的生命周期和依赖关系
 */
object ServiceLocator {
    private const val TAG = "ServiceLocator"
    
    private val services = mutableMapOf<Class<*>, Any>()
    private val serviceFactories = mutableMapOf<Class<*>, () -> Any>()
    private val singletonInstances = mutableMapOf<Class<*>, Any>()

    // 作用域服务映射：scopeId -> service types
    private val scopedServices = mutableMapOf<String, MutableSet<Class<*>>>()
    
    /**
     * 注册服务实例（单例模式）
     */
    fun <T : Any> register(serviceClass: Class<T>, instance: T) {
        if (services.containsKey(serviceClass)) {
            Log.w(TAG, "Service ${serviceClass.simpleName} is already registered, replacing...")
        }
        services[serviceClass] = instance
        
        // 如果服务实现了生命周期接口，调用 onCreate
        if (instance is ServiceLifecycle) {
            try {
                instance.onCreate()
            } catch (e: Exception) {
                Log.e(TAG, "Error calling onCreate for ${serviceClass.simpleName}", e)
            }
        }
    }
    
    /**
     * 注册服务工厂（延迟初始化）
     */
    fun <T : Any> registerFactory(serviceClass: Class<T>, factory: () -> T) {
        serviceFactories[serviceClass] = factory
    }
    
    /**
     * 注册单例服务工厂（延迟初始化，但只创建一次）
     */
    fun <T : Any> registerSingleton(serviceClass: Class<T>, factory: () -> T) {
        serviceFactories[serviceClass] = {
            singletonInstances.getOrPut(serviceClass) {
                factory().also { instance ->
                    if (instance is ServiceLifecycle) {
                        try {
                            instance.onCreate()
                        } catch (e: Exception) {
                            Log.e(TAG, "Error calling onCreate for ${serviceClass.simpleName}", e)
                        }
                    }
                }
            }
        }
    }
    
    /**
     * 获取服务实例
     */
    @Suppress("UNCHECKED_CAST")
    fun <T : Any> get(serviceClass: Class<T>): T {
        // 先检查已注册的实例
        services[serviceClass]?.let { return it as T }
        
        // 检查工厂
        serviceFactories[serviceClass]?.let { factory ->
            return factory() as T
        }
        
        throw IllegalStateException("Service ${serviceClass.simpleName} not registered")
    }
    
    /**
     * 尝试获取服务实例，如果不存在返回 null
     */
    @Suppress("UNCHECKED_CAST")
    fun <T : Any> getOrNull(serviceClass: Class<T>): T? {
        return try {
            get(serviceClass)
        } catch (e: IllegalStateException) {
            null
        }
    }
    
    /**
     * 检查服务是否已注册
     */
    fun <T : Any> isRegistered(serviceClass: Class<T>): Boolean {
        return services.containsKey(serviceClass) || serviceFactories.containsKey(serviceClass)
    }
    
    /**
     * 注销服务
     */
    fun <T : Any> unregister(serviceClass: Class<T>) {
        services[serviceClass]?.let { instance ->
            if (instance is ServiceLifecycle) {
                try {
                    instance.onDestroy()
                } catch (e: Exception) {
                    Log.e(TAG, "Error calling onDestroy for ${serviceClass.simpleName}", e)
                }
            }
        }
        services.remove(serviceClass)
        serviceFactories.remove(serviceClass)
        singletonInstances.remove(serviceClass)
        // 同时从所有作用域中移除该服务类型
        scopedServices.values.forEach { it.remove(serviceClass) }
    }

    /**
     * 在指定作用域内注册服务实例
     * 该服务仍然通过全局 get 获取，但会在 clearScope 时自动注销
     */
    fun <T : Any> registerScoped(scope: String, serviceClass: Class<T>, instance: T) {
        register(serviceClass, instance)
        val set = scopedServices.getOrPut(scope) { mutableSetOf() }
        set += serviceClass
    }

    /**
     * 清理指定作用域下注册的所有服务
     */
    fun clearScope(scope: String) {
        val types = scopedServices.remove(scope) ?: return
        types.forEach { type ->
            @Suppress("UNCHECKED_CAST")
            unregister(type as Class<Any>)
        }
    }
    
    /**
     * 清除所有服务（用于测试或重置）
     */
    fun clear() {
        // 调用所有服务的 onDestroy
        services.values.forEach { instance ->
            if (instance is ServiceLifecycle) {
                try {
                    instance.onDestroy()
                } catch (e: Exception) {
                    Log.e(TAG, "Error calling onDestroy during clear", e)
                }
            }
        }
        
        singletonInstances.values.forEach { instance ->
            if (instance is ServiceLifecycle) {
                try {
                    (instance as ServiceLifecycle).onDestroy()
                } catch (e: Exception) {
                    Log.e(TAG, "Error calling onDestroy during clear", e)
                }
            }
        }
        
        services.clear()
        serviceFactories.clear()
        singletonInstances.clear()
    }
    
    /**
     * 获取所有已注册的服务类型
     */
    fun getRegisteredServices(): List<Class<*>> {
        return (services.keys + serviceFactories.keys).distinct()
    }
}

/**
 * Kotlin 扩展函数，简化服务注册
 */
inline fun <reified T : Any> ServiceLocator.register(instance: T) {
    register(T::class.java, instance)
}

/**
 * Kotlin 扩展函数，简化服务工厂注册
 */
inline fun <reified T : Any> ServiceLocator.registerFactory(noinline factory: () -> T) {
    registerFactory(T::class.java, factory)
}

/**
 * Kotlin 扩展函数，简化单例服务工厂注册
 */
inline fun <reified T : Any> ServiceLocator.registerSingleton(noinline factory: () -> T) {
    registerSingleton(T::class.java, factory)
}

/**
 * Kotlin 扩展函数，简化服务获取
 */
inline fun <reified T : Any> ServiceLocator.get(): T {
    return get(T::class.java)
}

/**
 * Kotlin 扩展函数，简化服务获取（可空）
 */
inline fun <reified T : Any> ServiceLocator.getOrNull(): T? {
    return getOrNull(T::class.java)
}

/**
 * Kotlin 扩展函数，简化服务注销
 */
inline fun <reified T : Any> ServiceLocator.unregister() {
    unregister(T::class.java)
}
