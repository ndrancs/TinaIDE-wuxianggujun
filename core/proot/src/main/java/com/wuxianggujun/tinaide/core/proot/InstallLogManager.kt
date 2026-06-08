package com.wuxianggujun.tinaide.core.proot

import android.content.Context
import com.wuxianggujun.tinaide.core.proot.InstallLogEntry
import com.wuxianggujun.tinaide.core.proot.InstallLogLevel
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import timber.log.Timber

/**
 * 安装日志管理器
 *
 * 负责记录和保存 PRoot 环境安装过程中的日志
 * 日志保存到 Android/data/包名/files/logs/ 目录
 *
 * 使用专用的 InstallLogEntry 和 InstallLogLevel
 */
class InstallLogManager(context: Context) {

    private companion object {
        private const val TAG = "InstallLogManager"
        private const val LOG_DIR = "logs"
        private const val INSTALL_LOG_PREFIX = "install_"
        private const val INSTALL_LOG_SUFFIX = ".log"
        private const val MAX_LOG_LINES = 1000
        private const val MAX_LOG_FILES = 10 // 最多保留的安装日志文件数量
    }

    private val _logs = MutableStateFlow<List<InstallLogEntry>>(emptyList())
    val logs: StateFlow<List<InstallLogEntry>> = _logs.asStateFlow()

    private var logFile: File? = null
    private var logDir: File? = null

    init {
        // 使用 ProjectPaths 统一管理的安装日志目录
        val dir = com.wuxianggujun.tinaide.storage.ProjectPaths.ensureDir(
            com.wuxianggujun.tinaide.storage.ProjectPaths.getInstallLogsRoot(context)
        )
        logDir = dir

        // 清理旧的安装日志文件
        cleanOldInstallLogs(dir)

        // 创建新的日志文件（带时间戳）
        val dateFormat = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.getDefault())
        val fileName = "$INSTALL_LOG_PREFIX${dateFormat.format(Date())}$INSTALL_LOG_SUFFIX"
        logFile = File(dir, fileName)

        // 加载已有日志（如果存在）
        loadExistingLogs()
    }

    /**
     * 清理旧的安装日志文件
     */
    private fun cleanOldInstallLogs(logsDir: File) {
        try {
            val logFiles = logsDir.listFiles { file ->
                file.name.startsWith(INSTALL_LOG_PREFIX) && file.name.endsWith(INSTALL_LOG_SUFFIX)
            }?.sortedByDescending { it.lastModified() } ?: return

            if (logFiles.size >= MAX_LOG_FILES) {
                // 保留最新的 MAX_LOG_FILES - 1 个，为新文件留出空间
                logFiles.drop(MAX_LOG_FILES - 1).forEach { file ->
                    if (file.delete()) {
                        Timber.tag(TAG).d("Deleted old install log: %s", file.name)
                    }
                }
            }
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Failed to clean old install logs")
        }
    }

    /**
     * 加载已有日志
     */
    private fun loadExistingLogs() {
        logFile?.let { file ->
            if (file.exists()) {
                try {
                    val lines = file.readLines()
                    val entries = lines.mapNotNull { line ->
                        parseLogLine(line)
                    }
                    _logs.value = entries.takeLast(MAX_LOG_LINES)
                } catch (e: Exception) {
                    Timber.tag(TAG).w(e, "Failed to load existing logs")
                }
            }
        }
    }

    /**
     * 解析日志行
     */
    private fun parseLogLine(line: String): InstallLogEntry? {
        // 格式: [时间戳] [级别] 消息
        val regex = Regex("^\\[(\\d+)\\] \\[(\\w+)\\] (.+)$")
        val match = regex.find(line) ?: return null

        return try {
            val level = when (match.groupValues[2]) {
                "INFO" -> InstallLogLevel.INFO
                "SUCCESS" -> InstallLogLevel.SUCCESS
                "WARNING" -> InstallLogLevel.WARN
                "ERROR" -> InstallLogLevel.ERROR
                "COMMAND" -> InstallLogLevel.COMMAND
                else -> InstallLogLevel.INFO
            }
            InstallLogEntry.create(
                level = level,
                message = unescapeLogMessage(match.groupValues[3]),
                tag = "Install",
                timestamp = match.groupValues[1].toLong()
            )
        } catch (e: Exception) {
            Timber.tag(TAG).w(e, "Failed to parse log line: %s", line)
            null
        }
    }

    /**
     * 清空日志
     */
    fun clear() {
        _logs.value = emptyList()
        logFile?.delete()
    }

    /**
     * 记录信息日志
     */
    fun info(message: String) {
        log(InstallLogLevel.INFO, message)
    }

    /**
     * 记录成功日志
     */
    fun success(message: String) {
        log(InstallLogLevel.SUCCESS, message)
    }

    /**
     * 记录警告日志
     */
    fun warning(message: String) {
        log(InstallLogLevel.WARN, message)
    }

    /**
     * 记录错误日志
     */
    fun error(message: String) {
        log(InstallLogLevel.ERROR, message)
    }

    /**
     * 记录命令日志
     */
    fun command(message: String) {
        log(InstallLogLevel.COMMAND, "> $message")
    }

    /**
     * 记录日志
     */
    private fun log(level: InstallLogLevel, message: String) {
        val entry = InstallLogEntry.create(
            level = level,
            message = message,
            tag = "Install"
        )

        // 更新内存中的日志
        val currentLogs = _logs.value.toMutableList()
        currentLogs.add(entry)
        if (currentLogs.size > MAX_LOG_LINES) {
            currentLogs.removeAt(0)
        }
        _logs.value = currentLogs

        // 写入文件
        appendToFile(entry)
    }

    /**
     * 追加日志到文件
     */
    private fun appendToFile(entry: InstallLogEntry) {
        logFile?.let { file ->
            try {
                val levelName = when (entry.level) {
                    InstallLogLevel.INFO -> "INFO"
                    InstallLogLevel.SUCCESS -> "SUCCESS"
                    InstallLogLevel.WARN -> "WARNING"
                    InstallLogLevel.ERROR -> "ERROR"
                    InstallLogLevel.COMMAND -> "COMMAND"
                    else -> "INFO"
                }
                val message = escapeLogMessage(entry.message)
                val line = "[${entry.timestamp}] [$levelName] $message\n"
                file.appendText(line)
            } catch (e: Exception) {
                Timber.tag(TAG).e(e, "Failed to append log to file")
            }
        }
    }

    private fun escapeLogMessage(message: String): String = message
        .replace("\\", "\\\\")
        .replace("\r", "\\r")
        .replace("\n", "\\n")

    private fun unescapeLogMessage(message: String): String {
        if (!message.contains('\\')) return message

        val out = StringBuilder(message.length)
        var i = 0
        while (i < message.length) {
            val ch = message[i]
            if (ch != '\\' || i == message.lastIndex) {
                out.append(ch)
                i++
                continue
            }

            val next = message[i + 1]
            when (next) {
                'n' -> {
                    out.append('\n')
                    i += 2
                }
                'r' -> {
                    out.append('\r')
                    i += 2
                }
                '\\' -> {
                    out.append('\\')
                    i += 2
                }
                else -> {
                    out.append(ch)
                    i++
                }
            }
        }
        return out.toString()
    }

    /**
     * 获取日志文件路径
     */
    fun getLogFilePath(): String? = logFile?.absolutePath

    /**
     * 获取完整日志文本
     */
    fun getFullLogText(): String = _logs.value.joinToString("\n") { entry ->
        "[${entry.formattedTime}] ${entry.message}"
    }

    /**
     * 导出日志到指定文件
     */
    suspend fun exportLog(context: Context, fileName: String): File? = withContext(Dispatchers.IO) {
        try {
            // 使用 ProjectPaths 统一管理的安装日志目录
            val exportDir = com.wuxianggujun.tinaide.storage.ProjectPaths.ensureDir(
                com.wuxianggujun.tinaide.storage.ProjectPaths.getInstallLogsRoot(context)
            )

            val exportFile = File(exportDir, fileName)
            val content = buildString {
                appendLine("=== TinaIDE Install Log ===")
                appendLine("Export time: ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())}")
                appendLine()

                _logs.value.forEach { entry ->
                    appendLine("[${entry.formattedFullTime}] [${entry.level.displayName}] ${entry.message}")
                }

                appendLine()
                appendLine("--- End of Log ---")
            }

            exportFile.writeText(content)
            exportFile
        } catch (e: Exception) {
            Timber.tag(TAG).w(e, "exportLog failed: %s", fileName)
            null
        }
    }
}
