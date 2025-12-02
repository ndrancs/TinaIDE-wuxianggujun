package com.wuxianggujun.tinaide.core.lsp

import android.util.Log
import com.wuxianggujun.tinaide.core.nativebridge.NativeCompiler
import io.github.rosemoe.sora.lsp.client.connection.StreamConnectionProvider
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.atomic.AtomicBoolean

/**
 * clangd 连接提供器
 *
 * 通过 JNI/dlopen 加载 libclangd.so 共享库来运行 clangd。
 * 这种方式可以绕过 Android 对可执行文件的限制。
 *
 * 工作原理：
 * 1. 通过 dlopen 加载 libclangd.so
 * 2. 调用导出的 clangd_main 或 clangd_run 函数
 * 3. 使用管道进行 stdin/stdout 通信
 */
class ClangdConnectionProvider(
    private val clangdPath: String,
    private val workingDir: String,
    private val extraArgs: List<String> = emptyList()
) : StreamConnectionProvider {

    companion object {
        private const val TAG = "ClangdConnectionProvider"
        private const val BUFFER_SIZE = 8192
    }

    private var jniInputStream: JniInputStream? = null
    private var jniOutputStream: JniOutputStream? = null
    private val isRunning = AtomicBoolean(false)
    
    private var _inputStream: InputStream? = null
    private var _outputStream: OutputStream? = null

    override val inputStream: InputStream
        get() = _inputStream ?: throw IllegalStateException("Connection not started")

    override val outputStream: OutputStream
        get() = _outputStream ?: throw IllegalStateException("Connection not started")

    @Throws(IOException::class)
    override fun start() {
        val clangdFile = File(clangdPath)
        if (!clangdFile.exists()) {
            throw IOException("libclangd.so not found at: $clangdPath")
        }
        
        if (!clangdPath.endsWith(".so")) {
            throw IOException("clangd must be a shared library (.so): $clangdPath")
        }

        val workDir = File(workingDir)
        if (!workDir.exists()) {
            workDir.mkdirs()
        }

        Log.i(TAG, "Starting clangd via JNI: $clangdPath")
        Log.i(TAG, "Working directory: $workingDir")
        
        val argsArray = extraArgs.toTypedArray()
        if (argsArray.isNotEmpty()) {
            Log.i(TAG, "clangd extra args: ${argsArray.joinToString()}")
        }

        val error = NativeCompiler.startClangd(clangdPath, argsArray)
        if (error.isNotEmpty()) {
            throw IOException("Failed to start clangd via JNI: $error")
        }
        
        if (!NativeCompiler.isClangdRunning()) {
            throw IOException("clangd failed to start (not running after startClangd)")
        }
        
        isRunning.set(true)
        
        // 创建 JNI 流包装器
        jniOutputStream = JniOutputStream()
        jniInputStream = JniInputStream()
        
        _outputStream = jniOutputStream
        _inputStream = jniInputStream
        
        Log.i(TAG, "clangd started successfully via JNI")
    }

    override fun close() {
        Log.i(TAG, "Closing clangd connection")
        
        try {
            jniOutputStream?.close()
        } catch (e: Exception) {
            Log.w(TAG, "Error closing JNI output stream", e)
        }
        
        try {
            jniInputStream?.close()
        } catch (e: Exception) {
            Log.w(TAG, "Error closing JNI input stream", e)
        }
        
        NativeCompiler.stopClangd()
        
        jniOutputStream = null
        jniInputStream = null
        _inputStream = null
        _outputStream = null
        isRunning.set(false)
        
        Log.i(TAG, "clangd connection closed")
    }

    /**
     * 检查 clangd 是否还在运行
     */
    fun isAlive(): Boolean {
        return isRunning.get() && NativeCompiler.isClangdRunning()
    }

    /**
     * JNI 输出流 - 写入数据到 clangd stdin
     */
    private inner class JniOutputStream : OutputStream() {
        private val closed = AtomicBoolean(false)
        
        override fun write(b: Int) {
            write(byteArrayOf(b.toByte()))
        }
        
        override fun write(b: ByteArray) {
            write(b, 0, b.size)
        }
        
        override fun write(b: ByteArray, off: Int, len: Int) {
            if (closed.get()) {
                throw IOException("Stream is closed")
            }
            if (!NativeCompiler.isClangdRunning()) {
                throw IOException("clangd is not running")
            }
            
            val data = if (off == 0 && len == b.size) {
                b
            } else {
                b.copyOfRange(off, off + len)
            }
            
            val written = NativeCompiler.writeToClangd(data)
            if (written < 0) {
                throw IOException("Failed to write to clangd")
            }
            if (written < len) {
                // 部分写入，继续写入剩余部分
                write(b, off + written, len - written)
            }
        }
        
        override fun flush() {
            // JNI 管道不需要显式 flush
        }
        
        override fun close() {
            closed.set(true)
        }
    }

    /**
     * JNI 输入流 - 从 clangd stdout 读取数据
     */
    private inner class JniInputStream : InputStream() {
        private val closed = AtomicBoolean(false)
        private var buffer: ByteArray? = null
        private var bufferPos = 0
        private var bufferLen = 0
        
        override fun read(): Int {
            val b = ByteArray(1)
            val n = read(b, 0, 1)
            return if (n <= 0) -1 else (b[0].toInt() and 0xFF)
        }
        
        override fun read(b: ByteArray): Int {
            return read(b, 0, b.size)
        }
        
        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (closed.get()) {
                throw IOException("Stream is closed")
            }
            if (len == 0) return 0
            
            // 先从缓冲区读取
            if (buffer != null && bufferPos < bufferLen) {
                val available = bufferLen - bufferPos
                val toRead = minOf(available, len)
                System.arraycopy(buffer!!, bufferPos, b, off, toRead)
                bufferPos += toRead
                if (bufferPos >= bufferLen) {
                    buffer = null
                    bufferPos = 0
                    bufferLen = 0
                }
                return toRead
            }
            
            // 从 JNI 读取新数据（带超时）
            if (!NativeCompiler.isClangdRunning()) {
                return -1  // EOF
            }
            
            val data = NativeCompiler.readFromClangdWithTimeout(BUFFER_SIZE, 100)
            if (data == null || data.isEmpty()) {
                // 没有数据可用，但 clangd 还在运行，返回 0 表示暂时没有数据
                // 注意：这可能导致忙等待，但 LSP 客户端通常会处理这种情况
                return if (NativeCompiler.isClangdRunning()) {
                    // 阻塞等待数据
                    val blockingData = NativeCompiler.readFromClangdWithTimeout(BUFFER_SIZE, 5000)
                    if (blockingData == null || blockingData.isEmpty()) {
                        if (NativeCompiler.isClangdRunning()) 0 else -1
                    } else {
                        processReadData(blockingData, b, off, len)
                    }
                } else {
                    -1  // EOF
                }
            }
            
            return processReadData(data, b, off, len)
        }
        
        private fun processReadData(data: ByteArray, b: ByteArray, off: Int, len: Int): Int {
            val toRead = minOf(data.size, len)
            System.arraycopy(data, 0, b, off, toRead)
            
            // 如果读取的数据比请求的多，缓存剩余部分
            if (data.size > len) {
                buffer = data
                bufferPos = toRead
                bufferLen = data.size
            }
            
            return toRead
        }
        
        override fun available(): Int {
            if (closed.get() || !NativeCompiler.isClangdRunning()) {
                return 0
            }
            
            // 检查缓冲区
            if (buffer != null && bufferPos < bufferLen) {
                return bufferLen - bufferPos
            }
            
            // 尝试非阻塞读取
            val data = NativeCompiler.readFromClangd(BUFFER_SIZE)
            if (data != null && data.isNotEmpty()) {
                buffer = data
                bufferPos = 0
                bufferLen = data.size
                return bufferLen
            }
            
            return 0
        }
        
        override fun close() {
            closed.set(true)
            buffer = null
            bufferPos = 0
            bufferLen = 0
        }
    }
}
