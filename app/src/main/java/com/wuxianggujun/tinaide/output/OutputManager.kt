package com.wuxianggujun.tinaide.output

import android.content.Context
import android.content.Intent
import com.wuxianggujun.tinaide.core.ServiceLocator
import com.wuxianggujun.tinaide.core.config.IConfigManager
import com.wuxianggujun.tinaide.core.config.ConfigKeys
import com.wuxianggujun.tinaide.core.get
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList

/**
 * 输出管理器实现
 */
class OutputManager(private val context: Context) : IOutputManager {
    
    private fun getOutputFile(channel: IOutputManager.OutputChannel): File {
        val fileName = when (channel) {
            IOutputManager.OutputChannel.RUN -> "run_output.log"
            IOutputManager.OutputChannel.BUILD -> "build_output.log"
        }
        return File(context.cacheDir, fileName)
    }
    private val maxOutputSizeBytes: Long = 1024L * 1024L // 1MB
    private val listeners = CopyOnWriteArrayList<IOutputManager.OutputListener>()
    private var outputMode = IOutputManager.OutputMode.ACTIVITY
    
    init {
        // 从配置读取输出模式
        try {
            val configManager = ServiceLocator.get<IConfigManager>()
            val savedMode = configManager.get(ConfigKeys.OutputMode)
            outputMode = IOutputManager.OutputMode.valueOf(savedMode)
        } catch (e: Exception) {
            outputMode = IOutputManager.OutputMode.ACTIVITY
        }
    }
    
    override fun appendOutput(text: String, channel: IOutputManager.OutputChannel) {
        val outputFile = getOutputFile(channel)
        try {
            if (!outputFile.exists()) {
                outputFile.parentFile?.mkdirs()
                outputFile.createNewFile()
            }
            outputFile.appendText(text)
            // 控制输出文件大小，超过上限时截断为后 50%
            if (outputFile.length() > maxOutputSizeBytes) {
                trimOutputFile(outputFile)
            }
        } catch (_: Throwable) {
            // 忽略文件写入错误，仍然通知监听器
        }
        listeners.forEach { it.onOutputAppended(text, channel) }
    }
    
    override fun clearOutput(channel: IOutputManager.OutputChannel) {
        val outputFile = getOutputFile(channel)
        try {
            if (outputFile.exists()) {
                outputFile.writeText("")
            }
        } catch (_: Throwable) {
            // 忽略清理错误
        }
        listeners.forEach { it.onOutputCleared(channel) }
    }
    
    override fun getOutput(channel: IOutputManager.OutputChannel): String {
        val outputFile = getOutputFile(channel)
        return try {
            if (!outputFile.exists()) "" else outputFile.readText()
        } catch (_: Throwable) {
            ""
        }
    }

    /**
     * 截断输出文件，只保留后 50% 内容
     */
    private fun trimOutputFile(outputFile: File) {
        try {
            if (!outputFile.exists()) return
            val lines = outputFile.readLines()
            if (lines.isEmpty()) return
            val keep = lines.takeLast(lines.size / 2)
            outputFile.writeText(keep.joinToString("\n"))
        } catch (_: Throwable) {
            // 忽略截断错误，保持现状
        }
    }
    
    override fun setOutputMode(mode: IOutputManager.OutputMode) {
        outputMode = mode
        // 保存到配置
        try {
            val configManager = ServiceLocator.get<IConfigManager>()
            configManager.set(ConfigKeys.OutputMode, mode.name)
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
        
        @Volatile
        private var logTextView: LogTextView? = null
        
        fun setLogView(view: LogTextView?) {
            logTextView = view
        }
        
        fun appendLog(level: LogLevel, message: String) {
            logTextView?.appendLog(level, message)
        }
    }
}
