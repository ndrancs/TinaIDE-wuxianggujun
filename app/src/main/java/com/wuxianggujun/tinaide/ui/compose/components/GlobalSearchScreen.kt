package com.wuxianggujun.tinaide.ui.compose.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.wuxianggujun.tinaide.core.i18n.Strings
import com.wuxianggujun.tinaide.core.i18n.strOr
import com.wuxianggujun.tinaide.search.ProjectSearchResult
import com.wuxianggujun.tinaide.search.history.SearchHistoryEntry
import com.wuxianggujun.tinaide.ui.GlobalSearchViewModel
import java.io.File

/**
 * 全局搜索界面
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GlobalSearchScreen(
    viewModel: GlobalSearchViewModel,
    onNavigateBack: () -> Unit,
    onResultClick: (File, Int) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val searchQuery by viewModel.searchQuery.collectAsStateWithLifecycle()
    val searchResults by viewModel.searchResults.collectAsStateWithLifecycle()
    val isSearching by viewModel.isSearching.collectAsStateWithLifecycle()
    val caseSensitive by viewModel.caseSensitive.collectAsStateWithLifecycle()
    val useRegex by viewModel.useRegex.collectAsStateWithLifecycle()
    val wholeWord by viewModel.wholeWord.collectAsStateWithLifecycle()
    val resultCount by viewModel.resultCount.collectAsStateWithLifecycle()
    val fileCount by viewModel.fileCount.collectAsStateWithLifecycle()
    val isReplaceMode by viewModel.isReplaceMode.collectAsStateWithLifecycle()
    val replacementText by viewModel.replacementText.collectAsStateWithLifecycle()
    val selectedResults by viewModel.selectedResults.collectAsStateWithLifecycle()
    val isReplacing by viewModel.isReplacing.collectAsStateWithLifecycle()
    val replaceResult by viewModel.replaceResult.collectAsStateWithLifecycle()
    val searchHistory by viewModel.searchHistory.collectAsStateWithLifecycle()
    val searchFavorites by viewModel.searchFavorites.collectAsStateWithLifecycle()

    val focusRequester = remember { FocusRequester() }
    val snackbarHostState = remember { SnackbarHostState() }
    var showHistoryDropdown by remember { mutableStateOf(false) }

    // 显示替换结果
    LaunchedEffect(replaceResult) {
        replaceResult?.let { result ->
            val message = if (result.isSuccess) {
                Strings.search_replace_success.strOr(context, result.totalReplacements, result.successFiles)
            } else if (result.hasPartialSuccess) {
                Strings.search_replace_partial.strOr(context, result.successFiles, result.failedFiles)
            } else {
                Strings.search_replace_failed.strOr(context)
            }
            snackbarHostState.showSnackbar(message)
            viewModel.clearReplaceResult()
        }
    }

    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
    }

    Scaffold(
        topBar = {
            TinaTopBar(
                title = stringResource(Strings.global_search_title),
                onNavigateBack = onNavigateBack
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { paddingValues ->
        Column(
            modifier = modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            // 搜索输入区域
            SearchInputSection(
                query = searchQuery,
                onQueryChange = { viewModel.updateQuery(it) },
                onSearch = { viewModel.searchNow() },
                onClear = { viewModel.clearSearch() },
                caseSensitive = caseSensitive,
                onToggleCaseSensitive = { viewModel.toggleCaseSensitive() },
                useRegex = useRegex,
                onToggleRegex = { viewModel.toggleRegex() },
                wholeWord = wholeWord,
                onToggleWholeWord = { viewModel.toggleWholeWord() },
                isReplaceMode = isReplaceMode,
                onToggleReplaceMode = { viewModel.toggleReplaceMode() },
                replacementText = replacementText,
                onReplacementTextChange = { viewModel.updateReplacementText(it) },
                focusRequester = focusRequester,
                showHistoryDropdown = showHistoryDropdown,
                onToggleHistoryDropdown = { showHistoryDropdown = !showHistoryDropdown },
                searchHistory = searchHistory,
                searchFavorites = searchFavorites,
                onSelectHistoryEntry = { entry ->
                    viewModel.restoreFromHistory(entry)
                    showHistoryDropdown = false
                },
                onToggleHistoryFavorite = { viewModel.toggleHistoryFavorite(it) },
                onDeleteHistoryEntry = { viewModel.deleteHistoryEntry(it) },
                onClearHistory = { viewModel.clearNonFavoriteHistory() }
            )

            // 搜索状态/统计
            SearchStatusBar(
                isSearching = isSearching,
                resultCount = resultCount,
                fileCount = fileCount,
                query = searchQuery,
                isReplaceMode = isReplaceMode,
                selectedCount = selectedResults.size,
                onSelectAll = { viewModel.selectAllResults() },
                onDeselectAll = { viewModel.deselectAllResults() },
                replacementText = replacementText,
                isReplacing = isReplacing,
                onReplaceSelected = { viewModel.executeReplace() }
            )

            HorizontalDivider()

            // 搜索结果列表
            if (searchResults.isEmpty() && searchQuery.isNotEmpty() && !isSearching) {
                EmptySearchResult()
            } else {
                SearchResultsList(
                    results = searchResults,
                    viewModel = viewModel,
                    onResultClick = onResultClick,
                    isReplaceMode = isReplaceMode,
                    selectedResults = selectedResults,
                    replacementText = replacementText,
                    onToggleSelection = { viewModel.toggleResultSelection(it) }
                )
            }
        }
    }
}

@Composable
private fun SearchInputSection(
    query: String,
    onQueryChange: (String) -> Unit,
    onSearch: () -> Unit,
    onClear: () -> Unit,
    caseSensitive: Boolean,
    onToggleCaseSensitive: () -> Unit,
    useRegex: Boolean,
    onToggleRegex: () -> Unit,
    wholeWord: Boolean,
    onToggleWholeWord: () -> Unit,
    isReplaceMode: Boolean,
    onToggleReplaceMode: () -> Unit,
    replacementText: String,
    onReplacementTextChange: (String) -> Unit,
    focusRequester: FocusRequester,
    showHistoryDropdown: Boolean,
    onToggleHistoryDropdown: () -> Unit,
    searchHistory: List<SearchHistoryEntry>,
    searchFavorites: List<SearchHistoryEntry>,
    onSelectHistoryEntry: (SearchHistoryEntry) -> Unit,
    onToggleHistoryFavorite: (Long) -> Unit,
    onDeleteHistoryEntry: (Long) -> Unit,
    onClearHistory: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        // 搜索输入框
        Box {
            OutlinedTextField(
                value = query,
                onValueChange = onQueryChange,
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(focusRequester),
                placeholder = { Text(stringResource(Strings.hint_search_text)) },
                leadingIcon = {
                    Icon(Icons.Default.Search, contentDescription = null)
                },
                trailingIcon = {
                    Row {
                        // 搜索历史按钮
                        IconButton(onClick = onToggleHistoryDropdown) {
                            Icon(
                                Icons.Default.History,
                                contentDescription = stringResource(Strings.search_history_title),
                                tint = if (showHistoryDropdown) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                }
                            )
                        }
                        // 切换替换模式按钮
                        IconButton(onClick = onToggleReplaceMode) {
                            Icon(
                                Icons.Default.SwapVert,
                                contentDescription = stringResource(Strings.btn_toggle_replace),
                                tint = if (isReplaceMode) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                }
                            )
                        }
                        if (query.isNotEmpty()) {
                            IconButton(onClick = onClear) {
                                Icon(Icons.Default.Clear, stringResource(Strings.action_clear))
                            }
                        }
                    }
                },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { onSearch() })
            )

            // 搜索历史下拉菜单
            SearchHistoryDropdown(
                history = searchHistory,
                favorites = searchFavorites,
                onSelectEntry = onSelectHistoryEntry,
                onToggleFavorite = onToggleHistoryFavorite,
                onDeleteEntry = onDeleteHistoryEntry,
                onClearHistory = onClearHistory,
                expanded = showHistoryDropdown,
                onDismiss = onToggleHistoryDropdown
            )
        }

        // 替换输入框（仅在替换模式下显示）
        if (isReplaceMode) {
            Spacer(modifier = Modifier.height(8.dp))
            OutlinedTextField(
                value = replacementText,
                onValueChange = onReplacementTextChange,
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text(stringResource(Strings.hint_replacement_text)) },
                leadingIcon = {
                    Icon(Icons.Default.SwapVert, contentDescription = null)
                },
                trailingIcon = {
                    if (replacementText.isNotEmpty()) {
                        IconButton(onClick = { onReplacementTextChange("") }) {
                            Icon(Icons.Default.Clear, stringResource(Strings.action_clear))
                        }
                    }
                },
                singleLine = true
            )
        }

        Spacer(modifier = Modifier.height(8.dp))

        // 搜索选项
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            FilterChip(
                selected = caseSensitive,
                onClick = onToggleCaseSensitive,
                label = { Text(stringResource(Strings.option_case_sensitive)) }
            )
            FilterChip(
                selected = useRegex,
                onClick = onToggleRegex,
                label = { Text(stringResource(Strings.option_regex)) }
            )
            FilterChip(
                selected = wholeWord,
                onClick = onToggleWholeWord,
                label = { Text(stringResource(Strings.option_whole_word)) }
            )
        }
    }
}

@Composable
private fun SearchStatusBar(
    isSearching: Boolean,
    resultCount: Int,
    fileCount: Int,
    query: String,
    isReplaceMode: Boolean,
    selectedCount: Int,
    onSelectAll: () -> Unit,
    onDeselectAll: () -> Unit,
    replacementText: String = "",
    isReplacing: Boolean = false,
    onReplaceSelected: () -> Unit = {}
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (isSearching) {
                CircularProgressIndicator(
                    modifier = Modifier.size(16.dp),
                    strokeWidth = 2.dp
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = stringResource(Strings.search_in_progress),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else if (query.isNotEmpty()) {
                Text(
                    text = stringResource(Strings.search_results_count, resultCount, fileCount),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f)
                )
            }
        }

        // 替换模式下显示选择操作按钮
        if (isReplaceMode && resultCount > 0 && !isSearching) {
            Spacer(modifier = Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = stringResource(Strings.selected_count, selectedCount),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.weight(1f))
                TextButton(onClick = onSelectAll) {
                    Text(stringResource(Strings.btn_select_all))
                }
                TextButton(onClick = onDeselectAll) {
                    Text(stringResource(Strings.btn_deselect_all))
                }
            }

            // 替换按钮
            if (selectedCount > 0 && replacementText.isNotEmpty()) {
                Spacer(modifier = Modifier.height(8.dp))
                Button(
                    onClick = onReplaceSelected,
                    enabled = !isReplacing,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    if (isReplacing) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onPrimary
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                    }
                    Text(
                        text = if (isReplacing) {
                            stringResource(Strings.replace_progress, 0, selectedCount)
                        } else {
                            stringResource(Strings.btn_replace_selected) + " ($selectedCount)"
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun EmptySearchResult() {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = stringResource(Strings.search_no_results),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun SearchResultsList(
    results: List<ProjectSearchResult>,
    viewModel: GlobalSearchViewModel,
    onResultClick: (File, Int) -> Unit,
    isReplaceMode: Boolean,
    selectedResults: Set<String>,
    replacementText: String,
    onToggleSelection: (String) -> Unit
) {
    // 按文件分组
    val groupedResults = remember(results) {
        results.groupBy { it.file }
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.navigationBars)
    ) {
        groupedResults.forEach { (file, fileResults) ->
            // 文件头
            item(key = "header_${file.absolutePath}") {
                FileHeader(
                    file = file,
                    relativePath = viewModel.getRelativePath(file),
                    matchCount = fileResults.size
                )
            }

            // 文件内的匹配结果
            items(
                items = fileResults,
                key = { "${it.file.absolutePath}_${it.lineNumber}_${it.matchStart}" }
            ) { result ->
                SearchResultItem(
                    result = result,
                    onClick = { onResultClick(result.file, result.lineNumber) },
                    isReplaceMode = isReplaceMode,
                    isSelected = selectedResults.contains(result.uniqueKey),
                    replacementText = replacementText,
                    onToggleSelection = { onToggleSelection(result.uniqueKey) }
                )
            }
        }
    }
}

@Composable
private fun FileHeader(
    file: File,
    relativePath: String,
    matchCount: Int
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = relativePath,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.primary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = "$matchCount",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun SearchResultItem(
    result: ProjectSearchResult,
    onClick: () -> Unit,
    isReplaceMode: Boolean,
    isSelected: Boolean,
    replacementText: String,
    onToggleSelection: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 6.dp),
        verticalAlignment = Alignment.Top
    ) {
        // 替换模式下显示复选框
        if (isReplaceMode) {
            Checkbox(
                checked = isSelected,
                onCheckedChange = { onToggleSelection() },
                modifier = Modifier.size(24.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
        }

        // 行号
        Text(
            text = "${result.lineNumber}",
            style = MaterialTheme.typography.bodySmall.copy(
                fontFamily = FontFamily.Monospace,
                fontSize = 12.sp
            ),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(40.dp)
        )

        // 行内容（高亮匹配部分）
        Column(modifier = Modifier.weight(1f)) {
            val highlightColor = MaterialTheme.colorScheme.primary
            val content = result.lineContent
            val start = result.matchStart.coerceIn(0, content.length)
            val end = result.matchEnd.coerceIn(start, content.length)

            // 显示上下文行（前）
            if (result.contextBefore.isNotEmpty()) {
                result.contextBefore.forEachIndexed { index, contextLine ->
                    val contextLineNumber = result.lineNumber - result.contextBefore.size + index
                    ContextLineText(
                        lineNumber = contextLineNumber,
                        content = contextLine
                    )
                }
            }

            // 原始文本（带高亮）
            val annotatedString = buildAnnotatedString {
                if (start > 0) {
                    append(content.substring(0, start))
                }
                if (start < end) {
                    withStyle(SpanStyle(color = highlightColor, background = highlightColor.copy(alpha = 0.2f))) {
                        append(content.substring(start, end))
                    }
                }
                if (end < content.length) {
                    append(content.substring(end))
                }
            }

            Text(
                text = annotatedString,
                style = MaterialTheme.typography.bodySmall.copy(
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp
                ),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )

            // 显示上下文行（后）
            if (result.contextAfter.isNotEmpty()) {
                result.contextAfter.forEachIndexed { index, contextLine ->
                    val contextLineNumber = result.lineNumber + 1 + index
                    ContextLineText(
                        lineNumber = contextLineNumber,
                        content = contextLine
                    )
                }
            }

            // 替换模式下显示替换预览
            if (isReplaceMode && replacementText.isNotEmpty() && isSelected) {
                val replacedContent = buildAnnotatedString {
                    if (start > 0) {
                        append(content.substring(0, start))
                    }
                    withStyle(
                        SpanStyle(
                            color = MaterialTheme.colorScheme.tertiary,
                            background = MaterialTheme.colorScheme.tertiary.copy(alpha = 0.2f)
                        )
                    ) {
                        append(replacementText)
                    }
                    if (end < content.length) {
                        append(content.substring(end))
                    }
                }

                Text(
                    text = "→ ",
                    style = MaterialTheme.typography.bodySmall.copy(fontSize = 10.sp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = replacedContent,
                    style = MaterialTheme.typography.bodySmall.copy(
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp
                    ),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
private fun ContextLineText(
    lineNumber: Int,
    content: String
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = "$lineNumber",
            style = MaterialTheme.typography.labelSmall.copy(
                fontFamily = FontFamily.Monospace,
                fontSize = 10.sp
            ),
            color = MaterialTheme.colorScheme.outline,
            modifier = Modifier.width(32.dp)
        )
        Text(
            text = content,
            style = MaterialTheme.typography.bodySmall.copy(
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp
            ),
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}
