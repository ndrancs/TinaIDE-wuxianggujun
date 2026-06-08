package com.wuxianggujun.tinaide.ui.workspace

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import com.gyf.immersionbar.ktx.immersionBar
import com.wuxianggujun.tinaide.core.i18n.Drawables
import com.wuxianggujun.tinaide.core.i18n.Strings
import com.wuxianggujun.tinaide.core.i18n.strOr
import com.wuxianggujun.tinaide.core.proot.InstallLogManager
import com.wuxianggujun.tinaide.storage.ExternalFileIntents
import com.wuxianggujun.tinaide.ui.compose.components.TinaBackHandlers
import com.wuxianggujun.tinaide.ui.compose.components.TinaCustomDialogHeader
import com.wuxianggujun.tinaide.ui.compose.components.TinaCustomDialogScaffold
import com.wuxianggujun.tinaide.ui.compose.components.TinaDialogActionRow
import com.wuxianggujun.tinaide.ui.compose.components.TinaDialogContentColumn
import com.wuxianggujun.tinaide.ui.compose.components.TinaDropdownMenu
import com.wuxianggujun.tinaide.ui.compose.components.TinaDropdownMenuDangerItem
import com.wuxianggujun.tinaide.ui.compose.components.TinaOutlinedButton
import com.wuxianggujun.tinaide.ui.compose.components.TinaOverlayPanelSurface
import com.wuxianggujun.tinaide.ui.compose.components.TinaPanelSegmentButton
import com.wuxianggujun.tinaide.ui.compose.components.TinaPrimaryButton
import com.wuxianggujun.tinaide.ui.compose.components.tinaBackAction
import com.wuxianggujun.tinaide.ui.theme.TinaIDETheme
import com.wuxianggujun.tinaide.ui.workspace.components.rememberWorkspacePainter
import com.wuxianggujun.tinaide.ui.workspace.log.ComposeInstallLogPanel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.launch
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

/**
 * 安装日志查看界面
 *
 * 显示 PRoot 环境安装过程中的详细日志
 */
class InstallLogActivity :
    ComponentActivity(),
    KoinComponent {

    private val installLogManager: InstallLogManager by inject()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 设置沉浸式状态栏（深色背景使用浅色图标）
        immersionBar {
            transparentStatusBar()
            statusBarDarkFont(false)
            navigationBarColor(android.R.color.transparent)
            navigationBarDarkIcon(false)
        }

        setContent {
            TinaIDETheme {
                InstallLogScreen(
                    installLogManager = installLogManager,
                    onBack = { finish() },
                    onCopyLog = { copyLogToClipboard() },
                    onExportLog = { exportLogFile() }
                )
            }
        }
    }

    private fun copyLogToClipboard() {
        val logText = installLogManager.getFullLogText()
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = ClipData.newPlainText("TinaIDE Install Log", logText)
        clipboard.setPrimaryClip(clip)
        Toast.makeText(this, Strings.toast_log_copied.strOr(this), Toast.LENGTH_SHORT).show()
    }

    @OptIn(kotlinx.coroutines.DelicateCoroutinesApi::class)
    private fun exportLogFile() {
        kotlinx.coroutines.GlobalScope.launch {
            val fileName = "install_log_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())}.txt"
            val file = installLogManager.exportLog(this@InstallLogActivity, fileName)

            runOnUiThread {
                if (file != null) {
                    // 使用 FileProvider 分享文件
                    try {
                        val uri = ExternalFileIntents.getShareableUri(this@InstallLogActivity, file)
                        val shareIntent = Intent(Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(Intent.EXTRA_STREAM, uri)
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        }
                        startActivity(Intent.createChooser(shareIntent, Strings.share_title_export_log.strOr(this@InstallLogActivity)))
                    } catch (e: Exception) {
                        Toast.makeText(this@InstallLogActivity, Strings.toast_log_saved.strOr(this@InstallLogActivity, file.absolutePath), Toast.LENGTH_LONG).show()
                    }
                } else {
                    Toast.makeText(this@InstallLogActivity, Strings.toast_export_failed.strOr(this@InstallLogActivity), Toast.LENGTH_SHORT).show()
                }
            }
        }
    }
}

@Composable
fun InstallLogScreen(
    installLogManager: InstallLogManager,
    onBack: () -> Unit,
    onCopyLog: () -> Unit,
    onExportLog: () -> Unit
) {
    val entries by installLogManager.logs.collectAsState()
    var showMenu by remember { mutableStateOf(false) }
    var isSearchMode by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }
    val focusRequester = remember { FocusRequester() }

    val filteredEntries = remember(entries, searchQuery) {
        if (searchQuery.isBlank()) {
            entries
        } else {
            entries.filter { entry ->
                entry.message.contains(searchQuery, ignoreCase = true) ||
                    entry.tag?.contains(searchQuery, ignoreCase = true) == true
            }
        }
    }

    LaunchedEffect(isSearchMode) {
        if (isSearchMode) {
            focusRequester.requestFocus()
        }
    }

    val surfaceColor = MaterialTheme.colorScheme.surface
    val inputFieldColor = MaterialTheme.colorScheme.surfaceContainerHigh

    val fileTextPainter = rememberWorkspacePainter(Drawables.ic_file_text)
    val uploadPainter = rememberWorkspacePainter(Drawables.ic_upload)

    fun closeSearch() {
        isSearchMode = false
        searchQuery = ""
    }

    val handleBack = {
        if (isSearchMode) {
            closeSearch()
        } else {
            onBack()
        }
    }

    TinaBackHandlers(
        tinaBackAction(enabled = isSearchMode, onBack = ::closeSearch)
    )

    Scaffold(containerColor = MaterialTheme.colorScheme.background) { paddingValues ->
        TinaCustomDialogScaffold(
            modifier = Modifier
                .padding(paddingValues)
                .fillMaxSize()
                .padding(12.dp),
            header = {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    TinaCustomDialogHeader(
                        title = stringResource(Strings.title_run_log),
                        leadingContent = {
                            InstallLogActionButton(
                                onClick = handleBack,
                                modifier = Modifier.size(36.dp),
                                contentPadding = PaddingValues(0.dp)
                            ) {
                                Icon(
                                    painter = rememberWorkspacePainter(Drawables.ic_arrow_back),
                                    contentDescription = stringResource(Strings.content_desc_back),
                                    tint = MaterialTheme.colorScheme.onSurface
                                )
                            }
                        },
                        trailingContent = {
                            InstallLogHeaderActions(
                                isSearchMode = isSearchMode,
                                showMenu = showMenu,
                                onShowMenuChange = { showMenu = it },
                                onEnterSearch = {
                                    showMenu = false
                                    isSearchMode = true
                                },
                                onClearLogs = { installLogManager.clear() },
                                surfaceColor = surfaceColor
                            )
                        }
                    )
                    HorizontalDivider()

                    if (isSearchMode) {
                        InstallLogSearchPanel(
                            query = searchQuery,
                            onQueryChange = { searchQuery = it },
                            onClear = { searchQuery = "" },
                            focusRequester = focusRequester,
                            inputFieldColor = inputFieldColor
                        )
                    }

                    if (isSearchMode && searchQuery.isNotEmpty()) {
                        InstallLogSectionSurface(
                            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                            verticalArrangement = Arrangement.spacedBy(0.dp)
                        ) {
                            Text(
                                text = stringResource(
                                    Strings.search_result_count,
                                    filteredEntries.size,
                                    entries.size
                                ),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            },
            footer = {
                Column(modifier = Modifier.fillMaxWidth()) {
                    HorizontalDivider()
                    TinaDialogActionRow(
                        modifier = Modifier
                            .padding(top = 16.dp)
                            .windowInsetsPadding(WindowInsets.navigationBars),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        TinaOutlinedButton(
                            text = stringResource(Strings.btn_copy_log),
                            onClick = onCopyLog,
                            modifier = Modifier
                                .weight(1f)
                                .height(48.dp),
                            icon = fileTextPainter
                        )
                        TinaPrimaryButton(
                            text = stringResource(Strings.btn_export_file),
                            onClick = onExportLog,
                            modifier = Modifier
                                .weight(1f)
                                .height(48.dp),
                            icon = uploadPainter
                        )
                    }
                }
            }
        ) {
            if (filteredEntries.isEmpty()) {
                InstallLogEmptyState(
                    modifier = Modifier.fillMaxSize(),
                    isSearchMode = isSearchMode,
                    hasQuery = searchQuery.isNotEmpty()
                )
            } else {
                TinaOverlayPanelSurface(
                    modifier = Modifier.fillMaxSize(),
                    shape = RoundedCornerShape(20.dp),
                    containerColor = MaterialTheme.colorScheme.surface,
                    tonalElevation = 0.dp,
                    shadowElevation = 0.dp
                ) {
                    ComposeInstallLogPanel(
                        entries = filteredEntries,
                        modifier = Modifier.fillMaxSize(),
                        backgroundColor = Color.Transparent,
                        fontSizeSp = 13f,
                        autoScroll = !isSearchMode
                    )
                }
            }
        }
    }
}

@Composable
private fun RowScope.InstallLogHeaderActions(
    isSearchMode: Boolean,
    showMenu: Boolean,
    onShowMenuChange: (Boolean) -> Unit,
    onEnterSearch: () -> Unit,
    onClearLogs: () -> Unit,
    surfaceColor: Color
) {
    if (isSearchMode) {
        return
    }

    InstallLogActionButton(
        onClick = onEnterSearch,
        modifier = Modifier.size(36.dp),
        contentPadding = PaddingValues(0.dp)
    ) {
        Icon(
            painter = rememberWorkspacePainter(Drawables.ic_search),
            contentDescription = stringResource(Strings.action_search),
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }

    Box {
        InstallLogActionButton(
            onClick = { onShowMenuChange(true) },
            modifier = Modifier.size(36.dp),
            contentPadding = PaddingValues(0.dp)
        ) {
            Icon(
                painter = rememberWorkspacePainter(Drawables.ic_more_vert),
                contentDescription = stringResource(Strings.action_more),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        TinaDropdownMenu(
            expanded = showMenu,
            onDismissRequest = { onShowMenuChange(false) },
            modifier = Modifier.background(surfaceColor)
        ) {
            TinaDropdownMenuDangerItem(
                text = {
                    Text(stringResource(Strings.action_clear_log))
                },
                onClick = {
                    onShowMenuChange(false)
                    onClearLogs()
                }
            )
        }
    }
}

@Composable
private fun InstallLogSearchPanel(
    query: String,
    onQueryChange: (String) -> Unit,
    onClear: () -> Unit,
    focusRequester: FocusRequester,
    inputFieldColor: Color
) {
    InstallLogSectionSurface(
        contentPadding = PaddingValues(12.dp),
        verticalArrangement = Arrangement.spacedBy(0.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(44.dp)
                    .background(
                        color = inputFieldColor,
                        shape = RoundedCornerShape(12.dp)
                    )
                    .padding(horizontal = 14.dp),
                contentAlignment = Alignment.CenterStart
            ) {
                if (query.isEmpty()) {
                    Text(
                        text = stringResource(Strings.hint_search_log),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                BasicTextField(
                    value = query,
                    onValueChange = onQueryChange,
                    textStyle = TextStyle(
                        color = MaterialTheme.colorScheme.onSurface,
                        fontSize = MaterialTheme.typography.bodyMedium.fontSize
                    ),
                    singleLine = true,
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(focusRequester)
                )
            }

            if (query.isNotEmpty()) {
                InstallLogActionButton(
                    onClick = onClear,
                    modifier = Modifier.size(36.dp),
                    contentPadding = PaddingValues(0.dp)
                ) {
                    Icon(
                        painter = rememberWorkspacePainter(Drawables.ic_close),
                        contentDescription = stringResource(Strings.action_clear),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
private fun InstallLogEmptyState(
    modifier: Modifier = Modifier,
    isSearchMode: Boolean,
    hasQuery: Boolean
) {
    TinaOverlayPanelSurface(
        modifier = modifier,
        shape = RoundedCornerShape(20.dp),
        containerColor = MaterialTheme.colorScheme.surface,
        tonalElevation = 0.dp,
        shadowElevation = 0.dp
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(20.dp),
            contentAlignment = Alignment.Center
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Icon(
                    painter = rememberWorkspacePainter(Drawables.ic_file_text),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f),
                    modifier = Modifier.size(56.dp)
                )
                Text(
                    text = if (isSearchMode && hasQuery) {
                        stringResource(Strings.empty_no_search_results)
                    } else {
                        stringResource(Strings.empty_no_logs)
                    },
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun InstallLogActionButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(horizontal = 10.dp, vertical = 8.dp),
    content: @Composable BoxScope.() -> Unit
) {
    TinaPanelSegmentButton(
        onClick = onClick,
        modifier = modifier,
        minHeight = 36.dp,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        contentPadding = contentPadding,
        contentAlignment = Alignment.Center,
        content = content
    )
}

@Composable
private fun InstallLogSectionSurface(
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(16.dp),
    verticalArrangement: Arrangement.Vertical = Arrangement.spacedBy(8.dp),
    content: @Composable ColumnScope.() -> Unit
) {
    TinaOverlayPanelSurface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        containerColor = MaterialTheme.colorScheme.surface,
        tonalElevation = 0.dp,
        shadowElevation = 0.dp
    ) {
        TinaDialogContentColumn(
            modifier = Modifier.padding(contentPadding),
            verticalArrangement = verticalArrangement,
            content = content
        )
    }
}
