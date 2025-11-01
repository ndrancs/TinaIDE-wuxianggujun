package com.wuxianggujun.tinaide.core

/**
 * ServiceLocator 使用示例
 * 
 * 这个文件展示了如何使用 ServiceLocator 进行依赖管理
 */

// 示例服务接口
interface IExampleService {
    fun doSomething(): String
}

// 示例服务实现（带生命周期）
class ExampleServiceImpl : IExampleService, ServiceLifecycle {
    private var isInitialized = false
    
    override fun onCreate() {
        isInitialized = true
        println("ExampleService initialized")
    }
    
    override fun onDestroy() {
        isInitialized = false
        println("ExampleService destroyed")
    }
    
    override fun doSomething(): String {
        return if (isInitialized) {
            "Service is working!"
        } else {
            "Service not initialized"
        }
    }
}

/**
 * 使用示例
 */
fun serviceLocatorUsageExample() {
    // 方式 1: 直接注册实例
    val service = ExampleServiceImpl()
    ServiceLocator.register<IExampleService>(service)
    
    // 获取服务
    val retrievedService = ServiceLocator.get<IExampleService>()
    println(retrievedService.doSomething())
    
    // 方式 2: 使用工厂（延迟初始化）
    ServiceLocator.registerFactory<IExampleService> {
        ExampleServiceImpl()
    }
    
    // 方式 3: 使用单例工厂（只创建一次）
    ServiceLocator.registerSingleton<IExampleService> {
        ExampleServiceImpl()
    }
    
    // 检查服务是否注册
    if (ServiceLocator.isRegistered(IExampleService::class.java)) {
        println("Service is registered")
    }
    
    // 注销服务
    ServiceLocator.unregister<IExampleService>()
}
