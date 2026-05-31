package com.wuxianggujun.tinaide.ui.compose.components

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.wuxianggujun.tinaide.core.i18n.Strings
import com.wuxianggujun.tinaide.search.history.SearchHistoryEntry

/**
 * 搜索历史下拉组件
 */
@Composable
fun SearchHistoryDropdown(
    history: List<SearchHistoryEntry>,
    favorites: List<SearchHistoryEntry>,
    onSelectEntry: (SearchHistoryEntry) -> Unit,
    onToggleFavorite: (Long) -> Unit,
    onDeleteEntry: (Long) -> Unit,
    onClearHistory: () -> Unit,
    expanded: Boolean,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    TinaDropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismiss,
        modifier = modifier.widthIn(min = 300.dp, max = 400.dp)
    ) {
        // 收藏区域
        if (favorites.isNotEmpty()) {
            TinaDropdownMenuSectionHeader {
                TinaDropdownMenuSectionTitle(
                    text = stringResource(Strings.search_history_favorites),
                    color = MaterialTheme.colorScheme.primary
                )
            }
            favorites.forEach { entry ->
                SearchHistoryItem(
                    entry = entry,
                    onSelect = { onSelectEntry(entry) },
                    onToggleFavorite = { onToggleFavorite(entry.id) },
                    onDelete = { onDeleteEntry(entry.id) }
                )
            }
            TinaDropdownMenuDivider()
        }

        // 最近搜索区域
        val recentHistory = history.filter { !it.isFavorite }.take(10)
        if (recentHistory.isNotEmpty()) {
            TinaDropdownMenuSectionHeader(
                trailingContent = {
                    TextButton(
                        onClick = onClearHistory,
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)
                    ) {
                        Text(
                            text = stringResource(Strings.search_history_clear),
                            style = MaterialTheme.typography.labelSmall
                        )
                    }
                }
            ) {
                TinaDropdownMenuSectionTitle(
                    text = stringResource(Strings.search_history_recent)
                )
            }
            recentHistory.forEach { entry ->
                SearchHistoryItem(
                    entry = entry,
                    onSelect = { onSelectEntry(entry) },
                    onToggleFavorite = { onToggleFavorite(entry.id) },
                    onDelete = { onDeleteEntry(entry.id) }
                )
            }
        }

        // 空状态
        if (history.isEmpty()) {
            TinaDropdownMenuItem(
                text = {
                    Text(
                        text = stringResource(Strings.search_history_empty),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                },
                onClick = {},
                enabled = false
            )
        }
    }
}
@Composable
private fun SearchHistoryItem(
    entry: SearchHistoryEntry,
    onSelect: () -> Unit,
    onToggleFavorite: () -> Unit,
    onDelete: () -> Unit
) {
    TinaDropdownMenuItem(
        text = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Default.History,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.width(8.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = entry.query,
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (!entry.replacement.isNullOrEmpty()) {
                        Text(
                            text = "→ ${entry.replacement}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.tertiary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    // 显示搜索选项标签
                    val optionTags = buildList {
                        if (entry.options.caseSensitive) add("Aa")
                        if (entry.options.useRegex) add(".*")
                        if (entry.options.wholeWord) add("W")
                    }
                    if (optionTags.isNotEmpty()) {
                        Text(
                            text = optionTags.joinToString(" "),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.outline
                        )
                    }
                }
                IconButton(
                    onClick = onToggleFavorite,
                    modifier = Modifier.size(32.dp)
                ) {
                    Icon(
                        imageVector = if (entry.isFavorite) Icons.Default.Star else Icons.Default.StarBorder,
                        contentDescription = stringResource(
                            if (entry.isFavorite) {
                                Strings.search_history_unfavorite
                            } else {
                                Strings.search_history_favorite
                            }
                        ),
                        modifier = Modifier.size(16.dp),
                        tint = if (entry.isFavorite) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        }
                    )
                }
                IconButton(
                    onClick = onDelete,
                    modifier = Modifier.size(32.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Delete,
                        contentDescription = stringResource(Strings.search_history_delete),
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        },
        onClick = onSelect
    )
}
