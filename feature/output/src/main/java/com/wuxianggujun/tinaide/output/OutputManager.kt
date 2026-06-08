package com.wuxianggujun.tinaide.output

import android.content.Context
import com.wuxianggujun.tinaide.core.i18n.Strings
import com.wuxianggujun.tinaide.core.i18n.strOr
import java.util.EnumMap
import java.util.concurrent.CopyOnWriteArrayList

/**
 * 输出管理器实现
 */
class OutputManager(
    private val context: Context
) : IOutputManager {

    private val bufferLock = Any()
    private val channelBuffers = EnumMap<IOutputManager.OutputChannel, StringBuilder>(IOutputManager.OutputChannel::class.java)

    // 增加缓冲区大小到10MB，支持更多输出
    private val maxBufferChars = 10 * 1024 * 1024 // 10MB 级别的字符数
    private val listeners = CopyOnWriteArrayList<IOutputManager.OutputListener>()

    override fun appendOutput(text: String, channel: IOutputManager.OutputChannel) {
        synchronized(bufferLock) {
            val buffer = channelBuffers.getOrPut(channel) { StringBuilder() }
            buffer.append(text)
            // 当缓冲区超过限制时，保留后半部分（保留更多最新内容）
            if (buffer.length > maxBufferChars) {
                // 保留最后 75% 的内容，删除前 25%
                val keepStart = buffer.length - (maxBufferChars * 3 / 4)
                val deletedLines = buffer.substring(0, keepStart).count { it == '\n' }
                buffer.delete(0, maxOf(0, keepStart))
                // 在开头添加提示信息
                buffer.insert(0, Strings.output_truncated_lines.strOr(context, deletedLines) + "\n")
            }
        }
        listeners.forEach { it.onOutputAppended(text, channel) }
    }

    override fun clearOutput(channel: IOutputManager.OutputChannel) {
        synchronized(bufferLock) {
            channelBuffers[channel]?.setLength(0)
        }
        listeners.forEach { it.onOutputCleared(channel) }
    }

    override fun getOutput(channel: IOutputManager.OutputChannel): String {
        synchronized(bufferLock) {
            return channelBuffers[channel]?.toString() ?: ""
        }
    }

    override fun addOutputListener(listener: IOutputManager.OutputListener) {
        listeners.add(listener)
    }

    override fun removeOutputListener(listener: IOutputManager.OutputListener) {
        listeners.remove(listener)
    }
}
