package com.wuxianggujun.tinaide.terminal.session

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionClient
import timber.log.Timber

/**
 * TinaIDE 的 TerminalSessionClient 实现
 *
 * 连接 Termux 的 TerminalSession 与 TinaIDE 的终端管理逻辑
 */
class TinaTerminalSessionClient(
    private val context: Context,
    private val onTextChanged: (TerminalSession) -> Unit = {},
    private val onTitleChanged: (TerminalSession) -> Unit = {},
    private val onSessionFinished: (TerminalSession) -> Unit = {},
    private val onBell: (TerminalSession) -> Unit = {},
    private val onColorsChanged: (TerminalSession) -> Unit = {},
    private val onCursorStateChange: (Boolean) -> Unit = {},
    private val onShellPidSet: (TerminalSession, Int) -> Unit = { _, _ -> },
    private val onCustomOsc: (TerminalSession, Int, String) -> Unit = { _, _, _ -> }
) : TerminalSessionClient {

    private val clipboardManager: ClipboardManager by lazy {
        context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    }

    override fun onTextChanged(changedSession: TerminalSession) {
        onTextChanged.invoke(changedSession)
    }

    override fun onTitleChanged(changedSession: TerminalSession) {
        onTitleChanged.invoke(changedSession)
    }

    override fun onSessionFinished(finishedSession: TerminalSession) {
        onSessionFinished.invoke(finishedSession)
    }

    override fun onCopyTextToClipboard(session: TerminalSession, text: String?) {
        if (text != null) {
            val clip = ClipData.newPlainText("Terminal", text)
            clipboardManager.setPrimaryClip(clip)
        }
    }

    override fun onPasteTextFromClipboard(session: TerminalSession?) {
        val clip = clipboardManager.primaryClip
        if (clip != null && clip.itemCount > 0) {
            val text = clip.getItemAt(0).coerceToText(context)?.toString()
            if (!text.isNullOrEmpty() && session != null) {
                session.emulator?.paste(text)
            }
        }
    }

    override fun onBell(session: TerminalSession) {
        onBell.invoke(session)
    }

    override fun onColorsChanged(session: TerminalSession) {
        onColorsChanged.invoke(session)
    }

    override fun onSessionCustomOsc(session: TerminalSession, code: Int, text: String) {
        onCustomOsc.invoke(session, code, text)
    }

    override fun onTerminalCursorStateChange(state: Boolean) {
        onCursorStateChange.invoke(state)
    }

    override fun setTerminalShellPid(session: TerminalSession, pid: Int) {
        onShellPidSet.invoke(session, pid)
    }

    override fun getTerminalCursorStyle(): Int {
        // 0 = block, 1 = underline, 2 = bar
        return 0
    }

    // Logging methods
    override fun logError(tag: String?, message: String?) {
        Timber.tag(tag ?: TAG).e(message ?: "")
    }

    override fun logWarn(tag: String?, message: String?) {
        Timber.tag(tag ?: TAG).w(message ?: "")
    }

    override fun logInfo(tag: String?, message: String?) {
        Timber.tag(tag ?: TAG).i(message ?: "")
    }

    override fun logDebug(tag: String?, message: String?) {
        Timber.tag(tag ?: TAG).d(message ?: "")
    }

    override fun logVerbose(tag: String?, message: String?) {
        Timber.tag(tag ?: TAG).v(message ?: "")
    }

    override fun logStackTraceWithMessage(tag: String?, message: String?, e: Exception?) {
        Timber.tag(tag ?: TAG).e(e, message ?: "")
    }

    override fun logStackTrace(tag: String?, e: Exception?) {
        Timber.tag(tag ?: TAG).e(e, "Exception")
    }

    companion object {
        private const val TAG = "TinaTerminalSession"
    }
}
