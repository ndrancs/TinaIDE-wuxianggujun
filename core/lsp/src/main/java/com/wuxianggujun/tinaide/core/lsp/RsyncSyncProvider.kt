package com.wuxianggujun.tinaide.core.lsp

import android.content.Context
import com.wuxianggujun.tinaide.core.i18n.AppStrings
import com.wuxianggujun.tinaide.core.i18n.Strings
import com.wuxianggujun.tinaide.core.lang.ProjectPathFilters
import com.wuxianggujun.tinaide.core.util.NativeExecutableRunner
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import timber.log.Timber

/**
 * rsync 同步状态
 */
enum class RsyncSyncState {
    IDLE, // 空闲
    SYNCING, // 同步中
    SUCCESS, // 同步成功
    ERROR // 同步错误
}

/**
 * rsync 同步进度
 */
data class RsyncSyncProgress(
    val state: RsyncSyncState = RsyncSyncState.IDLE,
    val currentFile: String = "",
    val transferredBytes: Long = 0,
    val totalBytes: Long = 0,
    val speedBytesPerSec: Long = 0,
    val errorMessage: String? = null
) {
    val progressPercent: Float
        get() = if (totalBytes > 0) transferredBytes.toFloat() / totalBytes else 0f

    val speedFormatted: String
        get() = when {
            speedBytesPerSec < 1024 -> "$speedBytesPerSec B/s"
            speedBytesPerSec < 1024 * 1024 -> "${speedBytesPerSec / 1024} KB/s"
            else -> "${speedBytesPerSec / (1024 * 1024)} MB/s"
        }
}

/**
 * rsync 同步结果
 */
data class RsyncSyncResult(
    val success: Boolean,
    val filesTransferred: Int = 0,
    val bytesTransferred: Long = 0,
    val elapsedTimeMs: Long = 0,
    val errorMessage: String? = null
)

/**
 * rsync 同步提供者
 *
 * 使用 android-rsync 库提供的预编译 rsync 二进制文件，
 * 实现高效的增量文件同步。
 *
 * 使用方式：
 * ```kotlin
 * val provider = RsyncSyncProvider(context)
 * val result = provider.sync(
 *     localPath = "/sdcard/MyProject",
 *     remoteHost = "192.168.1.100",
 *     remoteModule = "tina-workspace"
 * )
 * ```
 *
 * PC 端需要运行 rsync daemon，配置示例：
 * ```
 * # /etc/rsyncd.conf
 * [tina-workspace]
 *     path = /tmp/tina-workspace
 *     read only = no
 *     list = yes
 *     uid = nobody
 *     gid = nogroup
 * ```
 *
 * @param context Android Context，用于获取 native library 路径
 */
class RsyncSyncProvider(private val context: Context) {

    companion object {
        private const val TAG = "RsyncSyncProvider"
        private const val RSYNC_BINARY_NAME = "librsync.so"
        private const val DEFAULT_RSYNC_PORT = 873

        /**
         * 默认排除模式
         */
        val defaultExcludePatterns = ProjectPathFilters.SYNC_IGNORE_PATTERNS
    }

    // 同步进度状态流
    private val _progressFlow = MutableStateFlow(RsyncSyncProgress())
    val progressFlow: StateFlow<RsyncSyncProgress> = _progressFlow.asStateFlow()

    // rsync 二进制路径
    private val rsyncBinaryPath: String by lazy {
        "${context.applicationInfo.nativeLibraryDir}/$RSYNC_BINARY_NAME"
    }

    private fun createRsyncProcessBuilder(
        args: List<String>,
        workingDir: File = context.cacheDir
    ): ProcessBuilder = NativeExecutableRunner.createProcessBuilder(
        executable = rsyncBinaryPath,
        args = args,
        workingDir = workingDir,
        nativeLibDir = context.applicationInfo.nativeLibraryDir,
        tmpDir = context.cacheDir.absolutePath,
        homeDir = context.filesDir.absolutePath,
        tinaExecContext = context.applicationContext
    ).redirectErrorStream(true)

    /**
     * 检查 rsync 二进制是否可用
     */
    fun isRsyncAvailable(): Boolean {
        val rsyncFile = File(rsyncBinaryPath)
        return rsyncFile.exists() && rsyncFile.canExecute()
    }

    /**
     * 获取 rsync 版本信息
     */
    suspend fun getRsyncVersion(): String? = withContext(Dispatchers.IO) {
        if (!isRsyncAvailable()) {
            return@withContext null
        }

        try {
            val process = createRsyncProcessBuilder(args = listOf("--version")).start()

            val output = process.inputStream.bufferedReader().readLine()
            process.waitFor()
            output
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Failed to get rsync version")
            null
        }
    }

    /**
     * 执行 rsync 同步
     *
     * @param localPath 本地项目路径
     * @param remoteHost 远程服务器地址
     * @param remoteModule rsync 模块名称
     * @param remotePort rsync 端口（默认 873）
     * @param delete 是否删除远程多余文件
     * @param excludePatterns 排除模式列表
     * @return 同步结果
     */
    suspend fun sync(
        localPath: String,
        remoteHost: String,
        remoteModule: String,
        remotePort: Int = DEFAULT_RSYNC_PORT,
        delete: Boolean = true,
        excludePatterns: List<String> = defaultExcludePatterns
    ): RsyncSyncResult = withContext(Dispatchers.IO) {
        if (!isRsyncAvailable()) {
            return@withContext RsyncSyncResult(
                success = false,
                errorMessage = "rsync binary not available"
            )
        }

        val startTime = System.currentTimeMillis()
        updateProgress(RsyncSyncState.SYNCING)

        try {
            // 构建 rsync 命令
            val command = buildRsyncCommand(
                localPath = localPath,
                remoteHost = remoteHost,
                remoteModule = remoteModule,
                remotePort = remotePort,
                delete = delete,
                excludePatterns = excludePatterns
            )

            Timber.tag(TAG).d("Executing rsync: ${command.joinToString(" ")}")

            // 执行 rsync
            val workingDir = File(localPath).let { path ->
                when {
                    path.isDirectory -> path
                    path.parentFile != null -> path.parentFile
                    else -> context.cacheDir
                }
            }
            val processBuilder = createRsyncProcessBuilder(
                args = command.drop(1),
                workingDir = workingDir
            )

            val process = processBuilder.start()

            // 读取输出并解析进度
            var filesTransferred = 0
            var bytesTransferred = 0L

            BufferedReader(InputStreamReader(process.inputStream)).use { reader ->
                var line: String?
                while (reader.readLine().also { line = it } != null) {
                    line?.let { outputLine ->
                        Timber.tag(TAG).v("rsync: $outputLine")
                        parseRsyncOutput(outputLine)?.let { (file, bytes) ->
                            filesTransferred++
                            bytesTransferred += bytes
                            updateProgress(
                                RsyncSyncState.SYNCING,
                                currentFile = file,
                                transferredBytes = bytesTransferred
                            )
                        }
                    }
                }
            }

            val exitCode = process.waitFor()
            val elapsedTime = System.currentTimeMillis() - startTime

            if (exitCode == 0) {
                updateProgress(RsyncSyncState.SUCCESS)
                Timber.tag(TAG).i("rsync completed: $filesTransferred files, $bytesTransferred bytes in ${elapsedTime}ms")
                RsyncSyncResult(
                    success = true,
                    filesTransferred = filesTransferred,
                    bytesTransferred = bytesTransferred,
                    elapsedTimeMs = elapsedTime
                )
            } else {
                val errorMsg = AppStrings.get(
                    Strings.rsync_error_exit_code,
                    exitCode
                )
                updateProgress(RsyncSyncState.ERROR, errorMessage = errorMsg)
                Timber.tag(TAG).e(errorMsg)
                RsyncSyncResult(
                    success = false,
                    errorMessage = errorMsg,
                    elapsedTimeMs = elapsedTime
                )
            }
        } catch (e: Exception) {
            val errorMsg = e.message ?: AppStrings.get(Strings.error_unknown)
            updateProgress(RsyncSyncState.ERROR, errorMessage = errorMsg)
            Timber.tag(TAG).e(e, "rsync sync failed")
            RsyncSyncResult(
                success = false,
                errorMessage = errorMsg,
                elapsedTimeMs = System.currentTimeMillis() - startTime
            )
        }
    }

    /**
     * 构建 rsync 命令
     */
    private fun buildRsyncCommand(
        localPath: String,
        remoteHost: String,
        remoteModule: String,
        remotePort: Int,
        delete: Boolean,
        excludePatterns: List<String>
    ): List<String> {
        val command = mutableListOf(
            rsyncBinaryPath,
            "-avz", // archive, verbose, compress
            "--progress", // 显示进度
            "--port=$remotePort" // 指定端口
        )

        if (delete) {
            command.add("--delete") // 删除远程多余文件
        }

        // 添加排除模式
        excludePatterns.forEach { pattern ->
            command.add("--exclude=$pattern")
        }

        // 源路径（确保以 / 结尾，表示同步目录内容而非目录本身）
        val sourcePath = if (localPath.endsWith("/")) localPath else "$localPath/"
        command.add(sourcePath)

        // 目标路径（rsync daemon 格式）
        command.add("$remoteHost::$remoteModule/")

        return command
    }

    /**
     * 解析 rsync 输出行
     *
     * @return Pair<文件名, 字节数> 或 null
     */
    private fun parseRsyncOutput(line: String): Pair<String, Long>? {
        // rsync 输出格式示例：
        // "          1,234 100%    1.23MB/s    0:00:00 (xfr#1, to-chk=10/20)"
        // 或简单的文件名

        // 尝试解析进度行
        val progressRegex = Regex("""^\s*([\d,]+)\s+\d+%\s+[\d.]+\w+/s""")
        val match = progressRegex.find(line)
        if (match != null) {
            val bytes = match.groupValues[1].replace(",", "").toLongOrNull() ?: 0
            return Pair("", bytes)
        }

        // 如果是文件名行（不以空格开头，不包含特殊字符）
        if (!line.startsWith(" ") && !line.contains("sending") && !line.contains("total")) {
            val trimmed = line.trim()
            if (trimmed.isNotEmpty() && !trimmed.startsWith(".")) {
                return Pair(trimmed, 0)
            }
        }

        return null
    }

    /**
     * 更新同步进度
     */
    private fun updateProgress(
        state: RsyncSyncState,
        currentFile: String = _progressFlow.value.currentFile,
        transferredBytes: Long = _progressFlow.value.transferredBytes,
        totalBytes: Long = _progressFlow.value.totalBytes,
        speedBytesPerSec: Long = _progressFlow.value.speedBytesPerSec,
        errorMessage: String? = null
    ) {
        _progressFlow.value = RsyncSyncProgress(
            state = state,
            currentFile = currentFile,
            transferredBytes = transferredBytes,
            totalBytes = totalBytes,
            speedBytesPerSec = speedBytesPerSec,
            errorMessage = errorMessage
        )
    }

    /**
     * 重置状态
     */
    fun reset() {
        _progressFlow.value = RsyncSyncProgress()
    }
}
