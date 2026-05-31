package com.wuxianggujun.tinaide.terminal.ui

import android.content.Context
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.inputmethod.InputMethodManager
import com.termux.terminal.TerminalSession
import com.termux.view.TerminalView
import com.termux.view.TerminalViewClient
import timber.log.Timber

/**
 * TinaIDE 的 TerminalViewClient 实现
 * 
 * 处理终端视图的用户交互事件
 */
class TinaTerminalViewClient(
    private val context: Context,
    private val onScale: (Float) -> Float = { it },
    private val onSingleTap: (MotionEvent) -> Unit = {},
    private val onLongPress: (MotionEvent) -> Boolean = { false },
    private val onKeyDown: (Int, KeyEvent, TerminalSession?) -> Boolean = { _, _, _ -> false },
    private val onKeyUp: (Int, KeyEvent) -> Boolean = { _, _ -> false },
    private val onCodePoint: (Int, Boolean, TerminalSession?) -> Boolean = { _, _, _ -> false },
    private val onEmulatorSet: () -> Unit = {},
    private val onCopyModeChanged: (Boolean) -> Unit = {}
) : TerminalViewClient {

    private val imm: InputMethodManager by lazy {
        context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
    }

    // 修饰键状态
    var ctrlEnabled: Boolean = false
    var altEnabled: Boolean = false
    var shiftEnabled: Boolean = false
    var fnEnabled: Boolean = false

    // 是否将返回键映射为 Escape
    var backButtonMappedToEscape: Boolean = false

    // 是否强制基于字符的输入
    var enforceCharBasedInput: Boolean = false

    // 是否使用 Ctrl+Space 解决方案
    var useCtrlSpaceWorkaround: Boolean = false

    // 终端视图是否被选中
    var terminalViewSelected: Boolean = true

    override fun onScale(scale: Float): Float {
        // 字号边界与阶梯切档由上层（TerminalViewWrapper）统一处理，这里原地透传，
        // 避免这一层基于可能已过时的 currentFontSize 再做一次 coerceIn 反而把 scale
        // 限制得和实际字号错位（例如字号已缩到 8sp 但 currentFontSize 仍是 13）。
        return onScale.invoke(scale)
    }

    override fun onSingleTapUp(e: MotionEvent) {
        onSingleTap.invoke(e)
    }

    override fun shouldBackButtonBeMappedToEscape(): Boolean {
        return backButtonMappedToEscape
    }

    override fun shouldEnforceCharBasedInput(): Boolean {
        return enforceCharBasedInput
    }

    override fun shouldUseCtrlSpaceWorkaround(): Boolean {
        return useCtrlSpaceWorkaround
    }

    override fun isTerminalViewSelected(): Boolean {
        return terminalViewSelected
    }

    override fun copyModeChanged(copyMode: Boolean) {
        onCopyModeChanged.invoke(copyMode)
    }

    override fun onKeyDown(keyCode: Int, e: KeyEvent, session: TerminalSession?): Boolean {
        return onKeyDown.invoke(keyCode, e, session)
    }

    override fun onKeyUp(keyCode: Int, e: KeyEvent): Boolean {
        return onKeyUp.invoke(keyCode, e)
    }

    override fun onLongPress(event: MotionEvent): Boolean {
        return onLongPress.invoke(event)
    }

    override fun readControlKey(): Boolean {
        return ctrlEnabled
    }

    override fun readAltKey(): Boolean {
        return altEnabled
    }

    override fun readShiftKey(): Boolean {
        return shiftEnabled
    }

    override fun readFnKey(): Boolean {
        return fnEnabled
    }

    override fun onCodePoint(codePoint: Int, ctrlDown: Boolean, session: TerminalSession?): Boolean {
        return onCodePoint.invoke(codePoint, ctrlDown, session)
    }

    override fun onEmulatorSet() {
        onEmulatorSet.invoke()
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

    /**
     * 显示软键盘
     */
    fun showSoftKeyboard(view: TerminalView) {
        view.requestFocus()
        imm.showSoftInput(view, InputMethodManager.SHOW_IMPLICIT)
    }

    /**
     * 隐藏软键盘
     */
    fun hideSoftKeyboard(view: TerminalView) {
        imm.hideSoftInputFromWindow(view.windowToken, 0)
    }

    companion object {
        private const val TAG = "TinaTerminalView"
    }
}
