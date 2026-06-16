package com.wuxianggujun.tinaide.terminal.process

import android.os.ParcelFileDescriptor
import android.system.Os
import android.system.OsConstants
import com.termux.terminal.JNI
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import timber.log.Timber

/**
 * PTY 进程封装类
 *
 * 使用 Termux 的 JNI 库来创建和管理伪终端进程。
 * 这个类提供了与原有 PtyProcess 兼容的接口，用于编译系统运行程序。
 */
class PtyProcess private constructor(
    private val fd: Int,
    private val pid: Int,
    private val rows: Int,
    private val cols: Int
) : AutoCloseable {

    companion object {
        private const val TAG = "PtyProcess"

        /**
         * 创建一个新的 PTY 进程
         *
         * @param cmd 要执行的命令
         * @param args 命令参数
         * @param env 环境变量数组，格式为 "KEY=VALUE"
         * @param rows 终端行数
         * @param cols 终端列数
         * @param cwd 工作目录（可选）
         * @return PtyProcess 实例
         */
        @JvmStatic
        fun create(
            cmd: String,
            args: Array<String>,
            env: Array<String>,
            rows: Int = 24,
            cols: Int = 80,
            cwd: String? = null
        ): PtyProcess {
            val processId = IntArray(1)
            val workDir = cwd ?: System.getProperty("user.dir") ?: "/"

            Timber.tag(TAG).d("Creating PTY process: cmd=$cmd, cwd=$workDir")

            val fd = JNI.createSubprocess(
                cmd,
                workDir,
                args,
                env,
                processId,
                rows,
                cols,
                0, // cellWidth - 0 表示使用默认值
                0 // cellHeight - 0 表示使用默认值
            )

            if (fd < 0) {
                throw IOException("Failed to create subprocess, fd=$fd")
            }

            Timber.tag(TAG).d("PTY process created: fd=$fd, pid=${processId[0]}")

            return PtyProcess(fd, processId[0], rows, cols)
        }
    }

    private val closed = AtomicBoolean(false)
    private val pfd: ParcelFileDescriptor = ParcelFileDescriptor.adoptFd(fd)
    private val inputStream: FileInputStream = FileInputStream(pfd.fileDescriptor)
    private val outputStream: FileOutputStream = FileOutputStream(pfd.fileDescriptor)

    /**
     * 获取进程 ID
     */
    fun getPid(): Int = pid

    /**
     * 检查进程是否仍在运行
     */
    fun isRunning(): Boolean {
        if (closed.get()) return false

        // 检查 /proc/[pid] 目录是否存在来判断进程是否运行
        return try {
            java.io.File("/proc/$pid").exists()
        } catch (e: Exception) {
            false
        }
    }

    /**
     * 从 PTY 读取数据
     *
     * @param buffer 目标缓冲区
     * @param offset 偏移量
     * @param length 最大读取长度
     * @return 实际读取的字节数，-1 表示 EOF
     */
    fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (closed.get()) return -1

        return try {
            inputStream.read(buffer, offset, length)
        } catch (e: IOException) {
            Timber.tag(TAG).w("Read error: ${e.message}")
            -1
        }
    }

    /**
     * 向 PTY 写入数据
     *
     * @param data 要写入的数据
     */
    fun write(data: ByteArray) {
        if (closed.get()) return

        try {
            outputStream.write(data)
            outputStream.flush()
        } catch (e: IOException) {
            Timber.tag(TAG).w("Write error: ${e.message}")
        }
    }

    /**
     * 向 PTY 写入字符串
     *
     * @param text 要写入的文本
     */
    fun write(text: String) {
        write(text.toByteArray(Charsets.UTF_8))
    }

    /**
     * 设置窗口大小
     *
     * @param rows 行数
     * @param cols 列数
     */
    fun setWindowSize(rows: Int, cols: Int) {
        if (closed.get()) return

        try {
            JNI.setPtyWindowSize(fd, rows, cols, 0, 0)
        } catch (e: Exception) {
            Timber.tag(TAG).w("Failed to set window size: ${e.message}")
        }
    }

    /**
     * 等待进程结束
     *
     * @return 退出码，如果 >= 0 表示正常退出码，如果 < 0 表示被信号终止（信号值的负数）
     */
    fun waitFor(): Int = try {
        JNI.waitFor(pid)
    } catch (e: Exception) {
        Timber.tag(TAG).w("waitFor error: ${e.message}")
        -1
    }

    /**
     * 销毁进程
     */
    fun destroy() {
        if (closed.compareAndSet(false, true)) {
            Timber.tag(TAG).d("Destroy PTY process: pid=$pid")

            // 终止进程：优先尝试进程组（如果子进程链存在可一并终止），再兜底终止单进程。
            runCatching { Os.kill(-pid, OsConstants.SIGKILL) }
            runCatching { Os.kill(pid, OsConstants.SIGKILL) }
            runCatching { android.os.Process.killProcess(pid) }

            // 先关闭输入输出流
            try {
                inputStream.close()
            } catch (e: Exception) {
                // 忽略关闭错误
            }

            try {
                outputStream.close()
            } catch (e: Exception) {
                // 忽略关闭错误
            }

            // 关闭 ParcelFileDescriptor，这会自动正确地关闭底层文件描述符
            // 注意：不能直接调用 JNI.close(fd)，因为 ParcelFileDescriptor 拥有该 fd 的所有权
            // 直接关闭会导致 fdsan 错误
            try {
                pfd.close()
            } catch (e: Exception) {
                Timber.tag(TAG).w("Failed to close ParcelFileDescriptor: ${e.message}")
            }
        }
    }

    override fun close() {
        destroy()
    }
}
