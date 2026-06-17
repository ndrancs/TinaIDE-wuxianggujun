package com.wuxianggujun.tinaide.ui.compose.components

import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.wuxianggujun.tinaide.core.compile.BuildLogEntry
import com.wuxianggujun.tinaide.core.i18n.Strings

/**
 * 构建日志内容
 *
 * 使用纯 Compose 实现的构建日志面板，避免 AndroidView 布局冲突
 */
@Composable
fun BuildLogContent(
    logs: List<BuildLogEntry>,
    modifier: Modifier = Modifier,
    selectAllRequest: Int = 0,
    scrollToBottomRequest: Int = 0,
    @StringRes emptyMessageRes: Int = Strings.build_log_empty,
    @StringRes copyActionTitleRes: Int = Strings.btn_copy_log,
    onCopyAll: (() -> Unit)? = null,
    onClear: (() -> Unit)? = null
) {
    if (logs.isEmpty()) {
        EmptyStateContent(
            message = stringResource(emptyMessageRes),
            modifier = modifier
        )
    } else {
        ComposeBuildLogPanel(
            logs = logs,
            modifier = modifier,
            fontSizeSp = 12f,
            autoScroll = true,
            selectAllRequest = selectAllRequest,
            scrollToBottomRequest = scrollToBottomRequest,
            copyActionTitleRes = copyActionTitleRes,
            onCopyAll = onCopyAll,
            onClear = onClear
        )
    }
}
