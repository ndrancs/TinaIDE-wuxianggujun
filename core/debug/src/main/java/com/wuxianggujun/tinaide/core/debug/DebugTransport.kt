package com.wuxianggujun.tinaide.core.debug

import android.net.LocalServerSocket
import android.net.LocalSocket
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.Closeable
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.nio.charset.StandardCharsets
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import timber.log.Timber

/**
 * 调试传输层
 *
 * 使用 Unix Domain Socket 与 Native Debug Stub 通信。
 * 协议格式: JSON 行（每条消息一行）
 */
class DebugTransport(private val socketName: String) : Closeable {

    companion object {
        private const val TAG = "DebugTransport"
    }

    private var serverSocket: LocalServerSocket? = null
    private var clientSocket: LocalSocket? = null
    private var reader: BufferedReader? = null
    private var writer: BufferedWriter? = null

    @Volatile
    private var isRunning = false

    /**
     * 启动服务端 Socket，等待 Native Stub 连接
     */
    fun start() {
        if (isRunning) return

        try {
            serverSocket = LocalServerSocket(socketName)
            isRunning = true
            Timber.tag(TAG).i("Debug transport started on $socketName")
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Failed to start server socket")
            throw e
        }
    }

    /**
     * 等待客户端连接
     */
    suspend fun waitForConnection(timeoutMs: Long): Boolean = withContext(Dispatchers.IO) {
        if (!isRunning) return@withContext false

        try {
            val result = withTimeoutOrNull(timeoutMs) {
                val socket = serverSocket?.accept()
                if (socket != null) {
                    clientSocket = socket
                    reader = BufferedReader(
                        InputStreamReader(socket.inputStream, StandardCharsets.UTF_8)
                    )
                    writer = BufferedWriter(
                        OutputStreamWriter(socket.outputStream, StandardCharsets.UTF_8)
                    )
                    Timber.tag(TAG).i("Client connected")
                    true
                } else {
                    false
                }
            }
            result ?: false
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Error waiting for connection")
            false
        }
    }

    /**
     * 发送调试命令
     */
    suspend fun sendCommand(command: DebugCommand) = withContext(Dispatchers.IO) {
        val json = serializeCommand(command)
        try {
            writer?.apply {
                write(json)
                newLine()
                flush()
            }
            Timber.tag(TAG).d("Sent: $json")
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Failed to send command")
            throw e
        }
    }

    /**
     * 接收调试响应
     */
    suspend fun receiveResponse(timeoutMs: Long): DebugResponse? = withContext(Dispatchers.IO) {
        try {
            val result = withTimeoutOrNull(timeoutMs) {
                val line = reader?.readLine()
                if (line != null) {
                    Timber.tag(TAG).d("Received: $line")
                    deserializeResponse(line)
                } else {
                    null
                }
            }
            result
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Failed to receive response")
            null
        }
    }

    /**
     * 关闭连接
     */
    override fun close() {
        isRunning = false
        try {
            reader?.close()
            writer?.close()
            clientSocket?.close()
            serverSocket?.close()
        } catch (e: Exception) {
            Timber.tag(TAG).w(e, "Error closing transport")
        }
        reader = null
        writer = null
        clientSocket = null
        serverSocket = null
        Timber.tag(TAG).i("Debug transport closed")
    }

    // === 序列化 ===

    private fun serializeCommand(command: DebugCommand): String {
        val json = JSONObject()
        when (command) {
            is DebugCommand.Initialize -> {
                json.put("cmd", "init")
            }
            is DebugCommand.Continue -> {
                json.put("cmd", "c")
            }
            is DebugCommand.StepOver -> {
                json.put("cmd", "n")
            }
            is DebugCommand.StepInto -> {
                json.put("cmd", "s")
            }
            is DebugCommand.StepOut -> {
                json.put("cmd", "finish")
            }
            is DebugCommand.Pause -> {
                json.put("cmd", "pause")
            }
            is DebugCommand.Terminate -> {
                json.put("cmd", "kill")
            }
            is DebugCommand.SetBreakpoint -> {
                json.put("cmd", "b")
                json.put("file", command.file)
                json.put("line", command.line)
                command.condition?.let { json.put("cond", it) }
            }
            is DebugCommand.RemoveBreakpoint -> {
                json.put("cmd", "d")
                json.put("addr", command.address)
            }
            is DebugCommand.GetBacktrace -> {
                json.put("cmd", "bt")
            }
            is DebugCommand.GetVariables -> {
                json.put("cmd", "locals")
                json.put("frame", command.frameId)
            }
            is DebugCommand.Evaluate -> {
                json.put("cmd", "p")
                json.put("expr", command.expression)
            }
            is DebugCommand.ReadMemory -> {
                json.put("cmd", "x")
                json.put("addr", command.address)
                json.put("len", command.length)
            }
            is DebugCommand.ReadRegisters -> {
                json.put("cmd", "regs")
            }
        }
        return json.toString()
    }

    private fun deserializeResponse(line: String): DebugResponse = try {
        val json = JSONObject(line)
        val type = json.optString("type", "")

        when (type) {
            "ready" -> {
                val loc = json.optJSONObject("location")?.let { parseLocation(it) }
                DebugResponse.Ready(loc)
            }
            "stopped" -> {
                val reason = parseStopReason(json.optString("reason", ""))
                val loc = json.optJSONObject("location")?.let { parseLocation(it) }
                val tid = json.optInt("thread", 0)
                DebugResponse.Stopped(reason, loc, tid)
            }
            "exited" -> {
                DebugResponse.Exited(json.optInt("code", 0))
            }
            "crashed" -> {
                DebugResponse.Crashed(json.optString("signal", "UNKNOWN"))
            }
            "bp_set" -> {
                DebugResponse.BreakpointSet(json.optLong("addr", 0))
            }
            "bp_removed" -> {
                DebugResponse.BreakpointRemoved(json.optBoolean("ok", false))
            }
            "backtrace" -> {
                val frames = mutableListOf<StackFrame>()
                val arr = json.optJSONArray("frames") ?: JSONArray()
                for (i in 0 until arr.length()) {
                    val f = arr.getJSONObject(i)
                    frames.add(
                        StackFrame(
                            id = f.optInt("id", i),
                            name = f.optString("name", "<unknown>"),
                            file = f.optString("file").takeIf { it.isNotBlank() },
                            line = f.optInt("line", -1).takeIf { it >= 0 },
                            address = f.optLong("addr", 0)
                        )
                    )
                }
                DebugResponse.Backtrace(frames)
            }
            "variables" -> {
                val vars = mutableListOf<Variable>()
                val arr = json.optJSONArray("vars") ?: JSONArray()
                for (i in 0 until arr.length()) {
                    val v = arr.getJSONObject(i)
                    vars.add(
                        Variable(
                            name = v.optString("name", ""),
                            value = v.optString("value", ""),
                            type = v.optString("type").takeIf { it.isNotBlank() },
                            variablesReference = v.optInt("ref", 0)
                        )
                    )
                }
                DebugResponse.Variables(vars)
            }
            "eval" -> {
                DebugResponse.EvaluateResult(
                    value = json.optString("value", ""),
                    type = json.optString("type").takeIf { it.isNotBlank() }
                )
            }
            "memory" -> {
                val hex = json.optString("data", "")
                val bytes = hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
                DebugResponse.MemoryData(bytes)
            }
            "registers" -> {
                val regs = mutableMapOf<String, Long>()
                val obj = json.optJSONObject("regs") ?: JSONObject()
                obj.keys().forEach { key ->
                    regs[key] = obj.optLong(key, 0)
                }
                DebugResponse.Registers(regs)
            }
            "error" -> {
                DebugResponse.Error(json.optString("msg", "Unknown error"))
            }
            "ack" -> {
                DebugResponse.Ack
            }
            else -> {
                DebugResponse.Error("Unknown response type: $type")
            }
        }
    } catch (e: Exception) {
        Timber.tag(TAG).e(e, "Failed to parse response: $line")
        DebugResponse.Error("Parse error: ${e.message}")
    }

    private fun parseLocation(json: JSONObject): SourceLocation = SourceLocation(
        file = json.optString("file").takeIf { it.isNotBlank() } ?: "",
        line = json.optInt("line", 0),
        column = json.optInt("col", 0),
        function = json.optString("func").takeIf { it.isNotBlank() },
        address = json.optLong("addr", 0)
    )

    private fun parseStopReason(reason: String): PauseReason = when (reason.lowercase()) {
        "entry" -> PauseReason.ENTRY
        "breakpoint", "bp" -> PauseReason.BREAKPOINT
        "step" -> PauseReason.STEP
        "exception" -> PauseReason.EXCEPTION
        "signal" -> PauseReason.SIGNAL
        "pause" -> PauseReason.USER_REQUEST
        else -> PauseReason.BREAKPOINT
    }
}
