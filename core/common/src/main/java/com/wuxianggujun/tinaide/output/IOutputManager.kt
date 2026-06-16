package com.wuxianggujun.tinaide.output

/**
 * 输出管理器接口
 */
interface IOutputManager {
    /**
     * 输出通道
     */
    enum class OutputChannel {
        BUILD, // 构建日志
        RUN // 程序运行输出
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
