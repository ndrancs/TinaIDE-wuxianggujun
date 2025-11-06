package com.wuxianggujun.tinaide.terminal

import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionClient

/**
 * 终端会话服务 - 基于 ReTerminal 参考实现
 * 基于 Service 的会话管理（可选）
 * 注意：生产环境请补齐前台通知与生命周期管理
 */
class TerminalService : Service() {

    private val sessions = HashMap<String, TerminalSession>()

    inner class SessionBinder : Binder() {
        fun getService(): TerminalService = this@TerminalService

        fun createSession(
            id: String,
            client: TerminalSessionClient,
            workingMode: Int = TerminalSessionManager.WORKING_MODE_ALPINE
        ): TerminalSession {
            val session = TerminalSessionManager.createSession(
                this@TerminalService,
                client,
                id,
                workingMode = workingMode
            )
            sessions[id] = session
            return session
        }

        fun getSession(id: String): TerminalSession? = sessions[id]

        fun terminateSession(id: String) {
            sessions.remove(id)?.finishIfRunning()
        }

        fun terminateAllSessions() {
            sessions.values.forEach { it.finishIfRunning() }
            sessions.clear()
        }
    }

    private val binder = SessionBinder()

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onDestroy() {
        try { binder.terminateAllSessions() } catch (_: Throwable) {}
        super.onDestroy()
    }
}
