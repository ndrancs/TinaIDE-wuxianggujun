package com.wuxianggujun.tinaide.ui.compose.screens.main.market

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.Upload
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.wuxianggujun.tinaide.core.i18n.Strings
import com.wuxianggujun.tinaide.ui.compose.components.TinaAlertDialog
import com.wuxianggujun.tinaide.ui.compose.components.TinaDialogCard
import com.wuxianggujun.tinaide.ui.compose.components.TinaDialogContentColumn
import com.wuxianggujun.tinaide.ui.compose.components.TinaDialogMessageCard
import com.wuxianggujun.tinaide.ui.compose.components.TinaDialogTitleText
import com.wuxianggujun.tinaide.ui.compose.components.TinaOutlinedButton
import com.wuxianggujun.tinaide.ui.compose.components.TinaPrimaryButton
import com.wuxianggujun.tinaide.ui.compose.components.TinaPullToRefreshBox
import com.wuxianggujun.tinaide.ui.compose.components.TinaTextButton
import com.wuxianggujun.tinaide.ui.compose.components.TinaTextField
import org.koin.androidx.compose.koinViewModel

/**
 * 我的发布页面
 *
 * 开源版不提供账号发布能力，页面保留为统一提示入口。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MyPublishScreen(
    onNavigateBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: MyPublishViewModel = koinViewModel()
) {
    val isPublishingAvailable = false
    val myPluginsState by viewModel.myPluginsState.collectAsState()
    val uploadState by viewModel.uploadState.collectAsState()

    var showUploadPluginDialog by remember { mutableStateOf(false) }

    BackHandler(enabled = true) { onNavigateBack() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(Strings.my_publish_title)) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(android.R.string.cancel))
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface)
            )
        },
        modifier = modifier
    ) { padding ->
        if (!isPublishingAvailable) {
            Box(modifier = Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Text(stringResource(Strings.market_publish_unavailable), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else {
            TinaPullToRefreshBox(
                isRefreshing = myPluginsState.isLoading,
                onRefresh = {
                    viewModel.refresh()
                },
                enableHapticFeedback = true,
                modifier = Modifier.fillMaxSize().padding(padding)
            ) {
                LazyColumn(
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                    modifier = Modifier.fillMaxSize()
                ) {
                    // 我的插件区块
                    item {
                        SectionHeader(title = stringResource(Strings.my_publish_plugins_section), icon = Icons.Default.Extension, count = myPluginsState.plugins.size, onAddClick = { showUploadPluginDialog = true })
                    }
                    if (myPluginsState.isLoading && myPluginsState.plugins.isEmpty()) {
                        item { Box(Modifier.fillMaxWidth().height(100.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator(Modifier.size(24.dp)) } }
                    } else if (myPluginsState.plugins.isEmpty()) {
                        item { EmptySection(message = stringResource(Strings.my_publish_no_plugins), actionText = stringResource(Strings.my_publish_upload_plugin), onAction = { showUploadPluginDialog = true }) }
                    } else {
                        items(myPluginsState.plugins, key = { it.id }) { plugin -> MyPluginCard(plugin = plugin) }
                    }

                }
            }
        }
    }

    if (showUploadPluginDialog) {
        UploadPluginDialog(
            isUploading = uploadState.isUploading,
            error = uploadState.error,
            onDismiss = {
                if (!uploadState.isUploading) {
                    showUploadPluginDialog = false
                    viewModel.clearUploadError()
                }
            },
            onUpload = { uri, changelog -> viewModel.uploadPlugin(uri, changelog) { success, _ -> if (success) showUploadPluginDialog = false } }
        )
    }

}

// ── 内部辅助组件（插件相关，不属于 snippet） ──

@Composable
private fun SectionHeader(title: String, icon: androidx.compose.ui.graphics.vector.ImageVector, count: Int, onAddClick: () -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(24.dp))
            Spacer(Modifier.width(8.dp))
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            if (count > 0) {
                Spacer(Modifier.width(8.dp))
                Text("($count)", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        IconButton(onClick = onAddClick) { Icon(Icons.Default.Add, stringResource(Strings.action_add), tint = MaterialTheme.colorScheme.primary) }
    }
}

@Composable
private fun EmptySection(message: String, actionText: String, onAction: () -> Unit) {
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))) {
        Column(Modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(12.dp))
            TinaOutlinedButton(text = actionText, onClick = onAction, leadingIcon = Icons.Default.Add)
        }
    }
}

@Composable
private fun MyPluginCard(plugin: MyPluginSummary) {
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface), elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)) {
        Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Extension, null, Modifier.size(40.dp), tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(plugin.name, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(plugin.pluginId, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(4.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    plugin.latestVersion?.let { Text("v$it", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    Text("${plugin.downloadCount} ${stringResource(Strings.market_downloads)}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Spacer(Modifier.width(8.dp))
            StatusChip(status = plugin.status)
        }
    }
}

@Composable
private fun StatusChip(status: String) {
    val (bg, fg) = when (status) {
        "published" -> MaterialTheme.colorScheme.primaryContainer to MaterialTheme.colorScheme.onPrimaryContainer
        "pending" -> MaterialTheme.colorScheme.tertiaryContainer to MaterialTheme.colorScheme.onTertiaryContainer
        "draft" -> MaterialTheme.colorScheme.surfaceVariant to MaterialTheme.colorScheme.onSurfaceVariant
        "rejected" -> MaterialTheme.colorScheme.errorContainer to MaterialTheme.colorScheme.onErrorContainer
        else -> MaterialTheme.colorScheme.surfaceVariant to MaterialTheme.colorScheme.onSurfaceVariant
    }
    val text = when (status) {
        "published" -> stringResource(Strings.status_published)
        "pending" -> stringResource(Strings.status_pending)
        "draft" -> stringResource(Strings.status_draft)
        "rejected" -> stringResource(Strings.status_rejected)
        else -> status
    }
    Box(Modifier.clip(RoundedCornerShape(4.dp)).background(bg).padding(horizontal = 8.dp, vertical = 4.dp)) {
        Text(text, style = MaterialTheme.typography.labelSmall, color = fg)
    }
}

@Composable
private fun UploadPluginDialog(isUploading: Boolean, error: String?, onDismiss: () -> Unit, onUpload: (Uri, String?) -> Unit) {
    var selectedUri by remember { mutableStateOf<Uri?>(null) }
    var selectedFileName by remember { mutableStateOf<String?>(null) }
    var changelog by remember { mutableStateOf("") }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let {
            selectedUri = it
            selectedFileName = it.lastPathSegment
        }
    }

    TinaAlertDialog(
        onDismissRequest = onDismiss,
        title = {
            TinaDialogTitleText(stringResource(Strings.my_publish_upload_plugin))
        },
        text = {
            TinaDialogContentColumn {
                error?.let { uploadError ->
                    TinaDialogMessageCard(
                        message = uploadError,
                        color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.6f),
                        textColor = MaterialTheme.colorScheme.onErrorContainer
                    )
                }

                TinaDialogCard(
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.14f),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    TinaOutlinedButton(
                        text = selectedFileName ?: stringResource(Strings.my_publish_select_plugin_file),
                        onClick = { launcher.launch("application/*") },
                        enabled = !isUploading,
                        modifier = Modifier.fillMaxWidth(),
                        leadingIcon = Icons.Default.Upload
                    )
                    TinaTextField(
                        value = changelog,
                        onValueChange = { changelog = it },
                        label = stringResource(Strings.my_publish_changelog),
                        placeholder = stringResource(Strings.my_publish_changelog_hint),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(120.dp),
                        enabled = !isUploading,
                        singleLine = false
                    )
                }

                if (isUploading) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(Strings.my_publish_uploading))
                    }
                }
            }
        },
        confirmButton = {
            TinaPrimaryButton(
                text = stringResource(Strings.action_upload),
                onClick = {
                    selectedUri?.let { uri ->
                        onUpload(uri, changelog.takeIf { it.isNotBlank() })
                    }
                },
                enabled = selectedUri != null && !isUploading
            )
        },
        dismissButton = {
            TinaTextButton(
                text = stringResource(android.R.string.cancel),
                onClick = onDismiss,
                enabled = !isUploading
            )
        }
    )
}
