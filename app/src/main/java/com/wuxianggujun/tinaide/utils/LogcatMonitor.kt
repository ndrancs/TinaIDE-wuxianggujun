package com.wuxianggujun.tinaide.utils

import android.util.Log
import com.wuxianggujun.tinaide.output.LogLevel
import com.wuxianggujun.tinaide.ui.BottomLogBuffer
import kotlinx.coroutines.*
import java.io.BufferedReader
import java.io.InputStreamReader

/**
 * Logcat 监听器
 * 
 * 功能：
 * - 自动捕获当前应用的所有 Android Log 输出
 * - 转发到底部日志面板
 * - 支持日志级别过滤
 * 
 * 使用方式：
 * ```
 * // 在 Application.onCreate() 中启动
 * LogcatMonitor.start(applicationContext.packageName)
 * 
 * // 正常使用 Android Log
 * Log.d("MyTag", "这条日志会自动显示在底部面板")
 * Log.e("MyTag", "错误日志也会自动显示")
 * ```
 */
object LogcatMonitor {
    
    private const val TAG = "LogcatMonitor"
    private var monitorJob: Job? = null
    private var isRunning = false
    
    /**
     * 启动 Logcat 监听
     * 
     * @param packageName 应用包名，用于过滤只显示本应用的日志
     */
    fun start(packageName: String) {
        if (isRunning) {
            Log.w(TAG, "LogcatMonitor already running")
            return
        }
        
        isRunning = true
        monitorJob = CoroutineScope(Dispatchers.IO).launch {
            try {
                Log.i(TAG, "Starting logcat monitor for package: $packageName")
                
                // 清空旧日志，只监听新日志
                // -c: 清空日志缓冲区
                // -v time: 显示时间戳
                // --pid=<pid>: 只显示当前进程的日志
                val pid = android.os.Process.myPid()
                
                val process = Runtime.getRuntime().exec(
                    arrayOf(
                        "logcat",
                        "-v", "time",     // 显示时间戳
                        "--pid=$pid",     // 只显示当前进程
                        "*:V"             // 显示所有级别
                    )
                )
                
                val reader = BufferedReader(InputStreamReader(process.inputStream))
                
                while (isActive && isRunning) {
                    val line = reader.readLine() ?: break
                    
                    // 解析日志行并转发到 BottomLogBuffer
                    parseAndForwardLog(line)
                }
                
                reader.close()
                process.destroy()
                
            } catch (e: Exception) {
                Log.e(TAG, "Logcat monitor error", e)
            } finally {
                isRunning = false
            }
        }
    }
    
    /**
     * 停止 Logcat 监听
     */
    fun stop() {
        isRunning = false
        monitorJob?.cancel()
        monitorJob = null
        Log.i(TAG, "Logcat monitor stopped")
    }
    
    /**
     * 解析日志行并转发到 BottomLogBuffer
     * 
     * Logcat 格式示例：
     * 12-05 10:30:45.123 D/MyTag   ( 1234): Log message here
     */
    private fun parseAndForwardLog(line: String) {
        try {
            // Logcat 格式：时间戳 级别/标签(PID): 消息
            // 正则匹配：时间戳 级别/标签: 消息
            val regex = """^(\d{2}-\d{2}\s+\d{2}:\d{2}:\d{2}\.\d{3})\s+([VDIWEF])/(.+?)\s*\(\s*\d+\):\s*(.*)$""".toRegex()
            val match = regex.find(line) ?: return
            
            val (timestamp, levelChar, tag, message) = match.destructured
            val normalizedTag = tag.trim()

            // 过滤 Tina 编译日志，避免重复显示在“构建日志”和“日志”面板
            if (normalizedTag == "Compile") {
                return
            }
            
            // 转换日志级别
            val logLevel = when (levelChar) {
                "V" -> LogLevel.VERBOSE
                "D" -> LogLevel.DEBUG
                "I" -> LogLevel.INFO
                "W" -> LogLevel.WARN
                "E" -> LogLevel.ERROR
                "F" -> LogLevel.ERROR  // Fatal -> Error
                else -> LogLevel.INFO
            }
            
            // 转发结构化日志到 BottomLogBuffer
            BottomLogBuffer.append(logLevel, timestamp.trim(), normalizedTag, message)
            
        } catch (e: Exception) {
            // 解析失败时静默忽略（避免日志循环）
        }
    }
}
