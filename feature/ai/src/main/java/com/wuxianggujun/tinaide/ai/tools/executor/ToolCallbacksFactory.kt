package com.wuxianggujun.tinaide.ai.tools.executor

import com.wuxianggujun.tinaide.ai.tools.ToolExecutionContext
import com.wuxianggujun.tinaide.ai.tools.executor.code.DefaultCodeAnalysisCallbacks
import com.wuxianggujun.tinaide.ai.tools.executor.diagnostics.DefaultDiagnosticsCallbacks
import com.wuxianggujun.tinaide.ai.tools.executor.diagnostics.DiagnosticsCallbacks
import com.wuxianggujun.tinaide.ai.tools.executor.execution.DefaultExecutionCallbacks
import com.wuxianggujun.tinaide.ai.tools.executor.execution.ExecutionCallbacks
import com.wuxianggujun.tinaide.ai.tools.executor.filesystem.DefaultFileSystemCallbacks

/**
 * 上下文数据提供者接口
 * 各模块实现此接口来提供自己的数据
 */
interface ContextDataProvider {
    /**
     * 提供数据到上下文
     * @return 键值对，会被添加到 ToolExecutionContext.additionalData 中
     */
    fun provideData(): Map<String, Any>

    /**
     * 提供者的优先级，数字越小优先级越高
     * 用于解决多个提供者提供相同键时的冲突
     */
    val priority: Int get() = 100
}

/**
 * 项目信息提供者
 */
class ProjectInfoProvider(
    private val projectRoot: String,
    private val getCurrentFile: () -> String? = { null },
    private val getCurrentFileContent: () -> String? = { null }
) : ContextDataProvider {
    override fun provideData(): Map<String, Any> = buildMap {
        put("projectRoot", projectRoot)
        getCurrentFile()?.let { put("currentFile", it) }
        getCurrentFileContent()?.let { put("currentFileContent", it) }
    }

    override val priority: Int = 0 // 最高优先级
}

/**
 * 诊断回调提供者
 */
class DiagnosticsCallbacksProvider(
    private val getDiagnosticsCallbacks: () -> DiagnosticsCallbacks? = { null }
) : ContextDataProvider {
    override fun provideData(): Map<String, Any> = mapOf("diagnosticsCallbacks" to (getDiagnosticsCallbacks() ?: DefaultDiagnosticsCallbacks()))

    override val priority: Int = 30
}

/**
 * 执行回调提供者
 */
class ExecutionCallbacksProvider(
    private val getExecutionCallbacks: () -> ExecutionCallbacks? = { null }
) : ContextDataProvider {
    override fun provideData(): Map<String, Any> = mapOf("executionCallbacks" to (getExecutionCallbacks() ?: DefaultExecutionCallbacks()))

    override val priority: Int = 40
}

/**
 * 工具执行上下文注册表
 * 使用注册模式管理所有上下文数据提供者
 */
class ToolExecutionContextRegistry {
    private val providers = mutableListOf<ContextDataProvider>()

    /**
     * 注册数据提供者
     */
    fun registerProvider(provider: ContextDataProvider) {
        providers.add(provider)
        providers.sortBy { it.priority }
    }

    /**
     * 批量注册提供者
     */
    fun registerProviders(vararg providers: ContextDataProvider) {
        providers.forEach { registerProvider(it) }
    }

    /**
     * 移除提供者
     */
    fun unregisterProvider(provider: ContextDataProvider) {
        providers.remove(provider)
    }

    /**
     * 清空所有提供者
     */
    fun clear() {
        providers.clear()
    }

    /**
     * 构建工具执行上下文
     */
    fun build(): ToolExecutionContext {
        // 收集所有提供者的数据
        val allData = mutableMapOf<String, Any>()
        providers.forEach { provider ->
            allData.putAll(provider.provideData())
        }

        // 确保必需的回调存在
        ensureRequiredCallbacks(allData)

        // 提取项目信息
        val projectRoot = allData["projectRoot"] as? String ?: ""
        val currentFile = allData["currentFile"] as? String
        val currentFileContent = allData["currentFileContent"] as? String

        // 移除项目信息，其余作为 additionalData
        val additionalData = allData.toMutableMap().apply {
            remove("projectRoot")
            remove("currentFile")
            remove("currentFileContent")
        }

        return ToolExecutionContext(
            projectRoot = projectRoot,
            currentFile = currentFile,
            currentFileContent = currentFileContent,
            additionalData = additionalData
        )
    }

    private fun ensureRequiredCallbacks(data: MutableMap<String, Any>) {
        val projectRoot = data["projectRoot"] as? String ?: ""

        // 文件系统回调（必需）
        if (!data.containsKey("fileSystemCallbacks")) {
            data["fileSystemCallbacks"] = DefaultFileSystemCallbacks(projectRoot)
        }

        // 代码分析回调（必需）
        if (!data.containsKey("codeAnalysisCallbacks")) {
            data["codeAnalysisCallbacks"] = DefaultCodeAnalysisCallbacks(projectRoot)
        }

        // 诊断回调（使用默认实现）
        if (!data.containsKey("diagnosticsCallbacks")) {
            data["diagnosticsCallbacks"] = DefaultDiagnosticsCallbacks()
        }

        // 执行回调（使用默认实现）
        if (!data.containsKey("executionCallbacks")) {
            data["executionCallbacks"] = DefaultExecutionCallbacks()
        }
    }
}

/**
 * 优化后的工具回调工厂
 * 提供基于注册的 API
 */
object ToolCallbacksFactory {

    /**
     * 创建新的注册表
     */
    fun createRegistry(): ToolExecutionContextRegistry = ToolExecutionContextRegistry()

    /**
     * 快速创建基础上下文
     * @param projectRoot 项目根目录
     */
    fun createBasicContext(projectRoot: String): ToolExecutionContext {
        val registry = createRegistry()
        registry.registerProvider(ProjectInfoProvider(projectRoot))
        return registry.build()
    }

    /**
     * 获取上下文中的回调
     */
    inline fun <reified T> getCallback(context: ToolExecutionContext, key: String): T? = context.additionalData[key] as? T
}
