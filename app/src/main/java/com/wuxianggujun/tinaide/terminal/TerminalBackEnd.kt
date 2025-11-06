package com.wuxianggujun.tinaide.terminal

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.util.Log
import android.view.KeyEvent
import android.view.MotionEvent
import com.termux.terminal.TerminalEmulator
import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionClient
import com.termux.view.TerminalView
import com.termux.view.TerminalViewClient
import com.wuxianggujun.tinaide.terminal.virtualkeys.SpecialButton
import com.wuxianggujun.tinaide.terminal.virtualkeys.VirtualKeysView
import java.lang.ref.WeakReference

/**
 * 终端交互后端 - 对齐 ReTerminal 逻辑：
 * - 复制/粘贴走系统剪贴板
 * - 读取虚拟按键 CTRL/ALT/SHIFT/FN 的状态
 */
class TerminalBackEnd(
    private val terminal: TerminalView
) : TerminalViewClient, TerminalSessionClient {

    private var vkRef: WeakReference<VirtualKeysView>? = null

    fun bindVirtualKeysView(view: VirtualKeysView?) {
        vkRef = if (view == null) null else WeakReference(view)
    }

    override fun onTextChanged(changedSession: TerminalSession) {
        terminal.onScreenUpdated()
    }

    override fun onTitleChanged(changedSession: TerminalSession) {}

    override fun onSessionFinished(finishedSession: TerminalSession) {}

    override fun onCopyTextToClipboard(session: TerminalSession, text: String) {
        try {
            val cm = terminal.context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("terminal", text))
        } catch (_: Throwable) {
        }
    }

    override fun onPasteTextFromClipboard(session: TerminalSession?) {
        try {
            val cm = terminal.context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = cm.primaryClip
            val text = clip?.getItemAt(0)?.coerceToText(terminal.context)?.toString()
            if (!text.isNullOrBlank() && terminal.mEmulator != null) {
                terminal.mEmulator.paste(text)
            }
        } catch (_: Throwable) {
        }
    }

    override fun setTerminalShellPid(session: TerminalSession, pid: Int) {}

    override fun onBell(session: TerminalSession) {}

    override fun onColorsChanged(session: TerminalSession) {}

    override fun onTerminalCursorStateChange(state: Boolean) {}

    override fun getTerminalCursorStyle(): Int = TerminalEmulator.DEFAULT_TERMINAL_CURSOR_STYLE

    override fun logError(tag: String?, message: String?) { Log.e(tag ?: "TERM", message ?: "") }
    override fun logWarn(tag: String?, message: String?) { Log.w(tag ?: "TERM", message ?: "") }
    override fun logInfo(tag: String?, message: String?) { Log.i(tag ?: "TERM", message ?: "") }
    override fun logDebug(tag: String?, message: String?) { Log.d(tag ?: "TERM", message ?: "") }
    override fun logVerbose(tag: String?, message: String?) { Log.v(tag ?: "TERM", message ?: "") }
    override fun logStackTraceWithMessage(tag: String?, message: String?, e: Exception?) {
        Log.e(tag ?: "TERM", message ?: "")
        e?.printStackTrace()
    }
    override fun logStackTrace(tag: String?, e: Exception?) { e?.printStackTrace() }

    // 缩放回调：保留原样
    override fun onScale(scale: Float): Float = scale

    override fun onSingleTapUp(e: MotionEvent) {}
    override fun shouldBackButtonBeMappedToEscape(): Boolean = false
    override fun shouldEnforceCharBasedInput(): Boolean = true
    override fun shouldUseCtrlSpaceWorkaround(): Boolean = true
    override fun isTerminalViewSelected(): Boolean = true
    override fun copyModeChanged(copyMode: Boolean) {}
    override fun onKeyDown(keyCode: Int, e: KeyEvent, session: TerminalSession): Boolean = false
    override fun onKeyUp(keyCode: Int, e: KeyEvent): Boolean = false
    override fun onLongPress(event: MotionEvent): Boolean = false

    // 读取虚拟按键的切换状态（对齐 ReTerminal）
    override fun readControlKey(): Boolean = vkRef?.get()?.readSpecialButton(SpecialButton.CTRL, true) == true
    override fun readAltKey(): Boolean = vkRef?.get()?.readSpecialButton(SpecialButton.ALT, true) == true
    override fun readShiftKey(): Boolean = vkRef?.get()?.readSpecialButton(SpecialButton.SHIFT, true) == true
    override fun readFnKey(): Boolean = vkRef?.get()?.readSpecialButton(SpecialButton.FN, true) == true

    override fun onCodePoint(codePoint: Int, ctrlDown: Boolean, session: TerminalSession): Boolean = false

    override fun onEmulatorSet() {
        setTerminalCursorBlinkingState(true)
    }

    private fun setTerminalCursorBlinkingState(start: Boolean) {
        if (terminal.mEmulator != null) terminal.setTerminalCursorBlinkerState(start, true)
    }
}
