package com.wuxianggujun.tinaide.output

/**
 * 输出管理器接口
 */
interface IOutputManager {
    /**
     * 输出通道
     */
    enum class OutputChannel {
        BUILD,     // 构建日志
        RUN        // 程序运行输出
    }

    /**
     * 输出模式
     */
    enum class OutputMode {
        ACTIVITY,    // 独立Activity（默认，类似AIDE）
        BOTTOM_PANEL // 底部面板
    }
    
    /**
     * 追加输出内容
     */
    fun appendOutput(text: String, channel: OutputChannel = OutputChannel.RUN)
    
    /**
     * 清空输出
     */
    fun clearOutput(channel: OutputChannel = OutputChannel.RUN)
    
    /**
     * 获取当前输出内容
     */
    fun getOutput(channel: OutputChannel = OutputChannel.RUN): String
    
    /**
     * 设置输出模式
     */
    fun setOutputMode(mode: OutputMode)
    
    /**
     * 获取当前输出模式
     */
    fun getOutputMode(): OutputMode
    
    /**
     * 显示输出窗口
     */
    fun showOutput()
    
    /**
     * 添加输出监听器
     */
    fun addOutputListener(listener: OutputListener)
    
    /**
     * 移除输出监听器
     */
    fun removeOutputListener(listener: OutputListener)
    
    /**
     * 输出监听器
     */
    interface OutputListener {
        fun onOutputAppended(text: String, channel: OutputChannel)
        fun onOutputCleared(channel: OutputChannel)
    }
}
