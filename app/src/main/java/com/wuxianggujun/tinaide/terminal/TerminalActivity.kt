package com.wuxianggujun.tinaide.terminal

import android.graphics.Typeface
import android.os.Bundle
import android.view.ViewGroup
import android.widget.LinearLayout
import androidx.appcompat.app.AppCompatActivity
import com.termux.terminal.TerminalSession
import com.termux.view.TerminalView
import com.wuxianggujun.tinaide.terminal.virtualkeys.VirtualKeyClient
import com.wuxianggujun.tinaide.terminal.virtualkeys.VirtualKeysInfo
import com.wuxianggujun.tinaide.terminal.virtualkeys.VirtualKeysView
import com.wuxianggujun.tinaide.terminal.virtualkeys.VirtualKeysConstants
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch

/**
 * 终端界面 Activity（对齐 ReTerminal 功能）：
 * - 顶部为 TerminalView；底部增加虚拟按键栏（ESC/CTRL/ALT/方向等）
 * - 文本复制通过长按选择（TerminalView 内置文本选择与 ActionMode）
 */
class TerminalActivity : AppCompatActivity() {

    private lateinit var terminalView: TerminalView
    private lateinit var backend: TerminalBackEnd
    private var currentSession: TerminalSession? = null
    private lateinit var keysView: VirtualKeysView
    private val scope = MainScope()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 容器：垂直布局，TerminalView 占满，底部是虚拟按键栏
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }

        terminalView = TerminalView(this, null).apply {
            setTextSize(18)
            setTypeface(Typeface.MONOSPACE)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
        }
        container.addView(terminalView)

        keysView = VirtualKeysView(this, null).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
        container.addView(keysView)

        setContentView(container)

        // 后端 & 客户端绑定
        backend = TerminalBackEnd(terminalView)
        terminalView.setTerminalViewClient(backend)

        // 启动会话：优先 Alpine；缺失则先用宿主 Shell，并在后台下载
        if (TerminalSessionManager.isAlpineRuntimeReady(this)) {
            startSession(alpine = true)
        } else {
            startSession(alpine = false)
            scope.launch {
                RuntimeDownloader.setupEnvironment(
                    context = this@TerminalActivity,
                    onComplete = { runOnUiThread { restartIntoAlpine() } },
                    onError = { /* 失败则继续停留在宿主 Shell */ }
                )
            }
        }
    }

    private fun startSession(alpine: Boolean) {
        currentSession = TerminalSessionManager.createSession(
            context = this,
            client = backend,
            sessionId = if (alpine) "main" else "bootstrap",
            workingMode = if (alpine) TerminalSessionManager.WORKING_MODE_ALPINE else TerminalSessionManager.WORKING_MODE_SHELL,
            argsOverride = if (alpine) null else emptyArray(),
            debug = true
        )
        terminalView.attachSession(currentSession)
        terminalView.requestFocus()

        // 绑定虚拟按键到当前会话
        val session = currentSession ?: return
        keysView.setVirtualKeysViewClient(VirtualKeyClient(session))
        keysView.setRepetitiveKeys(com.wuxianggujun.tinaide.terminal.virtualkeys.VirtualKeysConstants.PRIMARY_REPETITIVE_KEYS)
        backend.bindVirtualKeysView(keysView)
        keysView.reload(defaultKeys())
        // 为 VirtualKeysView 设定一个合理的高度（按两行 * 48dp）
        val density = resources.displayMetrics.density
        val heightPx = (2 * 48 * density).toInt()
        keysView.layoutParams = (keysView.layoutParams as LinearLayout.LayoutParams).apply {
            height = heightPx
        }
    }

    private fun restartIntoAlpine() {
        try { currentSession?.finishIfRunning() } catch (_: Throwable) {}
        startSession(alpine = true)
    }

    private fun defaultKeys(): VirtualKeysInfo {
        // 两行常用按键：对齐 ReTerminal 的常用布局（简化版）
        val json = """
            [["ESC","TAB","CTRL","ALT","HOME","UP","END","PGUP"],
             ["/","-","LEFT","DOWN","RIGHT","PGDN","ENTER","ESC"]]
        """.trimIndent()
        return VirtualKeysInfo(
            json,
            "classic",
            VirtualKeysConstants.CONTROL_CHARS_ALIASES
        )
    }

    override fun onDestroy() {
        super.onDestroy()
        currentSession?.finishIfRunning()
    }
}
