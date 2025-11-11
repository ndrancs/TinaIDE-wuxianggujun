package com.wuxianggujun.tinaide.output

import android.content.Context
import android.content.Intent
import com.wuxianggujun.tinaide.core.ServiceLocator
import com.wuxianggujun.tinaide.core.config.IConfigManager
import com.wuxianggujun.tinaide.core.get
import java.util.concurrent.CopyOnWriteArrayList

/**
 * 输出管理器实现
 */
class OutputManager(private val context: Context) : IOutputManager {
    
    private val outputBuffer = StringBuilder()
    private val listeners = CopyOnWriteArrayList<IOutputManager.OutputListener>()
    private var outputMode = IOutputManager.OutputMode.ACTIVITY
    
    init {
        // 从配置读取输出模式
        try {
            val configManager = ServiceLocator.get<IConfigManager>()
            val savedMode = configManager.get("output.mode", "ACTIVITY")
            outputMode = IOutputManager.OutputMode.valueOf(savedMode)
        } catch (e: Exception) {
            outputMode = IOutputManager.OutputMode.ACTIVITY
        }
    }
    
    override fun appendOutput(text: String) {
        outputBuffer.append(text)
        listeners.forEach { it.onOutputAppended(text) }
    }
    
    override fun clearOutput() {
        outputBuffer.clear()
        listeners.forEach { it.onOutputCleared() }
    }
    
    override fun getOutput(): String {
        return outputBuffer.toString()
    }
    
    override fun setOutputMode(mode: IOutputManager.OutputMode) {
        outputMode = mode
        // 保存到配置
        try {
            val configManager = ServiceLocator.get<IConfigManager>()
            configManager.set("output.mode", mode.name)
        } catch (e: Exception) {
            // 忽略配置保存失败
        }
    }
    
    override fun getOutputMode(): IOutputManager.OutputMode {
        return outputMode
    }
    
    override fun showOutput() {
        when (outputMode) {
            IOutputManager.OutputMode.ACTIVITY -> {
                // 启动独立的输出Activity
                val intent = Intent(context, OutputActivity::class.java)
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
            }
            IOutputManager.OutputMode.BOTTOM_PANEL -> {
                // 底部面板模式由MainActivity自己处理
                // 这里不需要做什么，MainActivity会监听输出变化
            }
        }
    }
    
    override fun addOutputListener(listener: IOutputManager.OutputListener) {
        listeners.add(listener)
    }
    
    override fun removeOutputListener(listener: IOutputManager.OutputListener) {
        listeners.remove(listener)
    }
    
    companion object {
        private const val TAG = "OutputManager"
    }
}
