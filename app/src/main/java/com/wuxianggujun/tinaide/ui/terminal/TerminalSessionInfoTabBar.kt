package com.wuxianggujun.tinaide.ui.terminal

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.wuxianggujun.tinaide.core.terminal.TerminalSessionInfo
import com.wuxianggujun.tinaide.terminal.session.SessionStatus
import com.wuxianggujun.tinaide.terminal.session.TerminalSessionState
import com.wuxianggujun.tinaide.terminal.shell.TerminalBackend
import com.wuxianggujun.tinaide.terminal.ui.TerminalTabBar as FeatureTerminalTabBar

/**
 * TerminalSessionInfoTabBar 适配器
 *
 * 将 app 层的 TerminalSessionInfo 转换为 feature 层的 TerminalSessionState
 */
@Composable
fun TerminalSessionInfoTabBar(
    sessions: List<TerminalSessionInfo>,
    activeSessionId: String?,
    onTabClick: (String) -> Unit,
    onTabClose: (String) -> Unit,
    onNewTab: () -> Unit,
    modifier: Modifier = Modifier,
    onTabRename: ((String, String) -> Unit)? = null,
    onNewTabLongClick: (() -> Unit)? = null,
) {
    // 转换 TerminalSessionInfo 到 TerminalSessionState
    val sessionStates = sessions.map { info ->
        TerminalSessionState(
            id = info.id,
            title = info.title,
            backend = when (info.backend) {
                com.wuxianggujun.tinaide.core.terminal.TerminalBackend.HOST -> TerminalBackend.HOST
                com.wuxianggujun.tinaide.core.terminal.TerminalBackend.PROOT -> TerminalBackend.PROOT
            },
            session = null, // TabBar 不需要实际的 session 对象
            status = when (info.status) {
                com.wuxianggujun.tinaide.core.terminal.SessionStatus.STARTING -> SessionStatus.STARTING
                com.wuxianggujun.tinaide.core.terminal.SessionStatus.RUNNING -> SessionStatus.RUNNING
                com.wuxianggujun.tinaide.core.terminal.SessionStatus.EXITED -> SessionStatus.EXITED
                com.wuxianggujun.tinaide.core.terminal.SessionStatus.ERROR -> SessionStatus.ERROR
            },
            createdAt = info.createdAt,
            exitCode = info.exitCode,
            errorMessage = info.errorMessage,
            shellPid = info.shellPid
        )
    }

    FeatureTerminalTabBar(
        sessions = sessionStates,
        activeSessionId = activeSessionId,
        onTabClick = onTabClick,
        onTabClose = onTabClose,
        onTabRename = onTabRename,
        onNewTab = onNewTab,
        onNewTabLongClick = onNewTabLongClick,
        modifier = modifier
    )
}
