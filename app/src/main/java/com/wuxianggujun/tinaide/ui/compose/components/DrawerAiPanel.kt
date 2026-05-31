package com.wuxianggujun.tinaide.ui.compose.components

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Base64
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Stop
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.crossfade
import com.wuxianggujun.tinaide.ai.api.ChatMessage
import com.wuxianggujun.tinaide.ai.api.ChatRole
import com.wuxianggujun.tinaide.ai.api.MessageContext
import com.wuxianggujun.tinaide.ai.api.ToolCall
import com.wuxianggujun.tinaide.ai.api.ToolExecutionStatus
import com.wuxianggujun.tinaide.ai.model.ToolExecutionMode
import com.wuxianggujun.tinaide.ai.repository.ConversationMeta
import com.wuxianggujun.tinaide.ai.tools.ConfirmationSeverity
import com.wuxianggujun.tinaide.ai.tools.DangerousToolConfirmation
import com.wuxianggujun.tinaide.ai.tools.ToolRegistry
import com.wuxianggujun.tinaide.ai.viewmodel.AiChatViewModel
import com.wuxianggujun.tinaide.core.i18n.Strings
import com.wuxianggujun.tinaide.core.i18n.strOr
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber

private const val TAG = "DrawerAiPanel"

/**
 * AI 面板主组件
 */
@Composable
fun DrawerAiPanel(
    viewModel: AiChatViewModel,
    onInsertCode: (String) -> Unit,
    onGetCurrentFile: () -> MessageContext.CurrentFile?,
    onGetSelectedCode: () -> MessageContext.SelectedCode?,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier
) {
    val uiState by viewModel.uiState.collectAsState()
    val config by viewModel.config.collectAsState()
    val panelConfig by viewModel.panelConfig.collectAsState()
    val streamingContent by viewModel.streamingContent.collectAsState()
    val streamingReasoningContent by viewModel.streamingReasoningContent.collectAsState()
    val streamingToolCalls by viewModel.streamingToolCalls.collectAsState()
    val streamingUsage by viewModel.streamingUsage.collectAsState()
    val showConversationList by viewModel.showConversationList.collectAsState()
    val conversationList by viewModel.conversationList.collectAsState(initial = emptyList())
    val context = LocalContext.current

    var inputText by remember { mutableStateOf("") }
    val pendingImages = remember { mutableStateListOf<PendingImage>() }
    var pendingFile by remember { mutableStateOf<MessageContext.CurrentFile?>(null) }
    var pendingCode by remember { mutableStateOf<MessageContext.SelectedCode?>(null) }
    val listState = rememberLazyListState()
    val coroutineScope = rememberCoroutineScope()
    var showRetryErrorDialog by remember { mutableStateOf(false) }

    val imagePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetMultipleContents(),
        onResult = { uris: List<Uri> ->
            if (uris.isEmpty()) return@rememberLauncherForActivityResult

            val maxImages = 4
            val remaining = (maxImages - pendingImages.size).coerceAtLeast(0)
            val picked = uris.take(remaining)
            if (picked.isEmpty()) return@rememberLauncherForActivityResult

            // 清除其他附加内容
            pendingFile = null
            pendingCode = null

            coroutineScope.launch {
                var failed = 0
                picked.forEach { uri ->
                    val dataUrl = withContext(Dispatchers.IO) {
                        encodeImageToDataUrl(
                            context = context,
                            uri = uri,
                            maxBytes = 1_500_000,
                            maxDimension = 1024
                        )
                    }
                    if (dataUrl == null) {
                        failed += 1
                    } else {
                        pendingImages.add(PendingImage(uri = uri, dataUrl = dataUrl))
                    }
                }

                if (failed > 0) {
                    Toast.makeText(
                        context,
                        Strings.ai_image_pick_failed.strOr(context),
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        }
    )

    // 危险工具确认对话框状态
    var dangerousToolConfirmation by remember { mutableStateOf<Pair<ToolCall, DangerousToolConfirmation>?>(null) }

    // 统一的工具执行处理器（供手动执行使用）
    val handleToolExecution: (com.wuxianggujun.tinaide.ai.api.ToolCall) -> Unit = remember {
        { toolCall ->
            Timber.tag(TAG).d("Manual tool execution requested: %s", toolCall.function?.name)

            // 检查是否为危险工具
            val tool = toolCall.function?.name?.let { ToolRegistry.getTool(it) }
            if (tool?.isDangerous == true) {
                // 获取确认配置
                val confirmation = tool.getDangerousConfirmation(toolCall) ?: getDefaultConfirmation(context, tool)
                dangerousToolConfirmation = Pair(toolCall, confirmation)
            } else {
                // 非危险工具，直接执行
                viewModel.executeTool(toolCall)
            }
        }
    }

    // 过滤掉 TOOL 消息（TOOL 消息不需要单独显示）
    val displayMessages = remember(uiState.messages) {
        uiState.messages.filter { it.role != ChatRole.TOOL }
    }

    // 流式消息单独处理
    val hasStreamingContent = streamingContent.isNotEmpty() ||
        streamingReasoningContent.isNotEmpty() ||
        streamingToolCalls.isNotEmpty()

    val streamingMessage = if (hasStreamingContent) {
        com.wuxianggujun.tinaide.ai.api.ChatMessage(
            id = "streaming",
            role = com.wuxianggujun.tinaide.ai.api.ChatRole.ASSISTANT,
            content = streamingContent,
            reasoningContent = streamingReasoningContent.takeIf { it.isNotBlank() },
            toolCalls = streamingToolCalls.takeIf { it.isNotEmpty() },
            usage = streamingUsage
        )
    } else {
        null
    }

    // 只在接收到新消息时滚动到底部（位置 0）
    // 不在流式更新时滚动，避免打断用户查看历史消息
    LaunchedEffect(displayMessages.size) {
        if (displayMessages.isNotEmpty()) {
            listState.scrollToItem(0)
        }
    }

    Column(modifier = modifier.fillMaxSize()) {
        // 对话列表（如果显示）
        if (showConversationList) {
            ConversationListPanel(
                conversations = conversationList,
                currentConversationId = uiState.conversationId,
                onSelectConversation = { id ->
                    viewModel.loadConversation(id)
                },
                onDeleteConversation = { id ->
                    viewModel.deleteConversation(id)
                },
                onRenameConversation = { id, newTitle ->
                    viewModel.renameConversation(id, newTitle)
                },
                onClose = {
                    viewModel.hideConversationList()
                }
            )
        } else {
            // 顶部工具栏
            AiPanelToolbar(
                modelName = config?.generation?.model ?: stringResource(Strings.ai_not_configured),
                toolExecutionMode = panelConfig.toolExecutionMode,
                isProcessing = uiState.isLoading || uiState.waitingForUserAction,
                hasMessages = uiState.messages.isNotEmpty(),
                onNewConversation = { viewModel.newConversation() },
                onSummarizeAndContinue = { viewModel.showSummarizeConfirmDialog() },
                onToggleConversationList = { viewModel.toggleConversationList() },
                onToggleToolMode = { viewModel.toggleToolExecutionMode() },
                onOpenSettings = onOpenSettings
            )
        }

        HorizontalDivider()

        // 自动重试提示
        if (uiState.isRetrying && uiState.retryMessage != null) {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(8.dp),
                color = MaterialTheme.colorScheme.errorContainer,
                shape = RoundedCornerShape(8.dp)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        modifier = Modifier.weight(1f),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp
                        )
                        Text(
                            text = uiState.retryMessage ?: "",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onErrorContainer
                        )
                    }
                    if (uiState.retryError != null) {
                        IconButton(
                            onClick = { showRetryErrorDialog = true },
                            modifier = Modifier.size(24.dp)
                        ) {
                            Text(
                                text = "?",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onErrorContainer
                            )
                        }
                    }
                }
            }
        }

        // 错误提示
        uiState.error?.let { error ->
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(8.dp),
                color = MaterialTheme.colorScheme.errorContainer,
                shape = RoundedCornerShape(8.dp)
            ) {
                Row(
                    modifier = Modifier.padding(12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = error,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier.weight(1f)
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        TextButton(onClick = { viewModel.retryLastMessage() }) {
                            Text(stringResource(Strings.ai_retry))
                        }
                        TextButton(onClick = { viewModel.clearError() }) {
                            Text(stringResource(Strings.ai_close))
                        }
                    }
                }
            }
        }

        // 总结错误提示
        uiState.summarizeError?.let { error ->
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(8.dp),
                color = MaterialTheme.colorScheme.errorContainer,
                shape = RoundedCornerShape(8.dp)
            ) {
                Row(
                    modifier = Modifier.padding(12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = error,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier.weight(1f)
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        TextButton(onClick = { viewModel.summarizeAndContinue() }) {
                            Text(stringResource(Strings.ai_retry))
                        }
                        TextButton(onClick = { viewModel.clearSummarizeError() }) {
                            Text(stringResource(Strings.ai_close))
                        }
                    }
                }
            }
        }

        // 总结确认对话框
        if (uiState.showSummarizeConfirmDialog) {
            TinaConfirmDialog(
                title = stringResource(Strings.ai_summarize_confirm_title),
                message = stringResource(Strings.ai_summarize_confirm_message),
                confirmText = stringResource(Strings.ai_confirm),
                dismissText = stringResource(Strings.ai_cancel),
                onConfirm = { viewModel.summarizeAndContinue() },
                onDismiss = { viewModel.dismissSummarizeConfirmDialog() },
                icon = { Icon(Icons.Default.Warning, contentDescription = null) }
            )
        }

        // 重试错误对话框
        if (showRetryErrorDialog && uiState.retryError != null) {
            TinaErrorDialog(
                title = stringResource(Strings.ai_retry_error_title),
                message = uiState.retryError ?: "",
                onDismiss = { showRetryErrorDialog = false }
            )
        }

        // 对话区域 - 使用 reverseLayout 翻转，最新消息在底部
        LazyColumn(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            state = listState,
            reverseLayout = true, // 翻转布局，最新消息在底部
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            if (
                uiState.messages.isEmpty() &&
                !hasStreamingContent
            ) {
                item {
                    EmptyAiChatContent()
                }
            } else {
                // 添加一个弹性空间，让消息在不够高时靠顶部
                item {
                    Spacer(modifier = Modifier.fillParentMaxHeight(0.01f))
                }

                // 加载指示器（因为 reverseLayout，这个会显示在底部）
                if (
                    uiState.isLoading &&
                    !hasStreamingContent
                ) {
                    item {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.Start
                        ) {
                            Surface(
                                color = MaterialTheme.colorScheme.surfaceVariant,
                                shape = RoundedCornerShape(16.dp),
                                modifier = Modifier.padding(8.dp)
                            ) {
                                Row(
                                    modifier = Modifier.padding(12.dp),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(16.dp),
                                        strokeWidth = 2.dp
                                    )
                                    Text(
                                        text = stringResource(Strings.ai_thinking),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }
                }

                // 因为使用了 reverseLayout，需要反向渲染
                // 先渲染流式消息（最新的）
                if (streamingMessage != null) {
                    item(key = "streaming") {
                        key("streaming-card") {
                            StreamingMessageCard(
                                message = streamingMessage,
                                showTokenUsage = panelConfig.showTokenUsage,
                                onCopyCode = { code ->
                                    copyToClipboard(context, code)
                                    Toast.makeText(context, Strings.ai_code_copied, Toast.LENGTH_SHORT).show()
                                },
                                onInsertCode = { code ->
                                    onInsertCode(code)
                                    Toast.makeText(context, Strings.ai_code_inserted, Toast.LENGTH_SHORT).show()
                                },
                                onExecuteToolCall = handleToolExecution,
                                onCancelToolCall = { toolCall ->
                                    toolCall.id?.let {
                                        viewModel.cancelTool(it, "Cancelled by user")
                                    }
                                }
                            )
                        }
                    }
                }

                // 然后反向渲染静态消息（从新到旧）
                // 因为是反向渲染，第一条消息是最新的消息
                items(
                    items = displayMessages.asReversed(),
                    key = { it.id },
                    contentType = { it.role }
                ) { message ->
                    val isLastMessage = message.id == displayMessages.lastOrNull()?.id
                    ChatMessageBubble(
                        message = message,
                        showTokenUsage = panelConfig.showTokenUsage && isLastMessage,
                        onCopyCode = { code ->
                            copyToClipboard(context, code)
                            Toast.makeText(context, Strings.ai_code_copied, Toast.LENGTH_SHORT).show()
                        },
                        onInsertCode = { code ->
                            onInsertCode(code)
                            Toast.makeText(context, Strings.ai_code_inserted, Toast.LENGTH_SHORT).show()
                        },
                        onExecuteToolCall = if (message.role == ChatRole.ASSISTANT) handleToolExecution else null,
                        onCancelToolCall = if (message.role == ChatRole.ASSISTANT) {
                            { toolCall ->
                                toolCall.id?.let {
                                    viewModel.cancelTool(it, "Cancelled by user")
                                }
                            }
                        } else {
                            null
                        }
                    )
                }
            }
        }

        HorizontalDivider()

        // 底部输入区域
        // 检查是否有工具正在执行或等待执行（查找最后一条 ASSISTANT 消息）
        val hasPendingToolExecution = uiState.messages.findLast { msg ->
            msg.role == ChatRole.ASSISTANT
        }?.let { lastAssistantMsg ->
            lastAssistantMsg.toolCalls?.any {
                it.executionStatus == com.wuxianggujun.tinaide.ai.api.ToolExecutionStatus.PENDING ||
                    it.executionStatus == com.wuxianggujun.tinaide.ai.api.ToolExecutionStatus.EXECUTING
            } == true
        } ?: false

        AiInputArea(
            inputText = inputText,
            onInputChange = { inputText = it },
            attachedImages = pendingImages.map { it.uri },
            onRemoveAttachedImage = { idx ->
                if (idx in pendingImages.indices) pendingImages.removeAt(idx)
            },
            onClearAttachedImages = { pendingImages.clear() },
            pendingFile = pendingFile,
            onRemovePendingFile = { pendingFile = null },
            pendingCode = pendingCode,
            onRemovePendingCode = { pendingCode = null },
            isLoading = uiState.isLoading,
            hasPendingToolExecution = hasPendingToolExecution,
            onSend = {
                val detail = config?.generation?.imageDetail ?: "auto"
                val images = pendingImages.map { it.dataUrl }.filter { it.isNotBlank() }

                when {
                    images.isNotEmpty() -> {
                        viewModel.sendImagesMessage(inputText, images, detail = detail)
                        pendingImages.clear()
                    }
                    pendingFile != null -> {
                        viewModel.sendMessage(inputText, pendingFile!!)
                        pendingFile = null
                    }
                    pendingCode != null -> {
                        viewModel.sendMessage(inputText, pendingCode!!)
                        pendingCode = null
                    }
                    inputText.isNotBlank() -> {
                        viewModel.sendMessage(inputText)
                    }
                }
                inputText = ""
            },
            onStop = { viewModel.stopGeneration() },
            onAttachCurrentFile = {
                onGetCurrentFile()?.let { file ->
                    pendingImages.clear()
                    pendingCode = null
                    pendingFile = file
                }
            },
            onAttachSelectedCode = {
                onGetSelectedCode()?.let { code ->
                    pendingImages.clear()
                    pendingFile = null
                    pendingCode = code
                }
            },
            onAttachImage = {
                imagePicker.launch("image/*")
            }
        )
    }

    // 显示危险工具确认对话框
    dangerousToolConfirmation?.let { (toolCall, confirmation) ->
        DangerousToolConfirmationDialog(
            confirmation = confirmation,
            onConfirm = {
                dangerousToolConfirmation = null
                viewModel.executeTool(toolCall)
            },
            onCancel = {
                dangerousToolConfirmation = null
                viewModel.cancelTool(
                    toolCallId = toolCall.id ?: "",
                    reason = "User cancelled the dangerous operation"
                )
            }
        )
    }
}

/**
 * 分组的 AI 消息卡片
 * 将连续的 assistant/tool/tool-result 消息显示在同一个卡片中
 */
/**
 * 流式消息卡片 - 使用简单的 Text 组件，避免闪烁
 */
@Composable
private fun StreamingMessageCard(
    message: ChatMessage,
    showTokenUsage: Boolean,
    onCopyCode: (String) -> Unit,
    onInsertCode: (String) -> Unit,
    onExecuteToolCall: ((ToolCall) -> Unit)?,
    onCancelToolCall: ((ToolCall) -> Unit)?,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Start
    ) {
        // AI 流式消息不使用 Surface，直接显示内容
        Column(
            modifier = Modifier
                .widthIn(min = 48.dp, max = 360.dp)
                .wrapContentWidth()
        ) {
            val textColor = MaterialTheme.colorScheme.onSurface

            // Reasoning - 放在最上面
            message.reasoningContent?.let { reasoning ->
                if (reasoning.isNotBlank()) {
                    ReasoningSection(
                        messageId = message.id,
                        reasoning = reasoning,
                        onCopyCode = onCopyCode,
                        onInsertCode = onInsertCode,
                        color = textColor
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                }
            }

            // AI 回复内容 - 使用简单的 Text，流式更新时不会闪烁
            if (message.content.isNotBlank()) {
                Column {
                    Text(
                        text = message.content,
                        style = MaterialTheme.typography.bodyMedium,
                        color = textColor
                    )
                    // 流式输出时显示加载圈 - 放在右下方
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 4.dp),
                        horizontalArrangement = Arrangement.End
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }

            // 工具调用
            message.toolCalls?.let { toolCalls ->
                if (toolCalls.isNotEmpty()) {
                    if (message.content.isNotBlank()) {
                        Spacer(modifier = Modifier.height(8.dp))
                    }
                    ToolCallsSection(
                        messageId = message.id,
                        toolCalls = toolCalls,
                        onCopyCode = onCopyCode,
                        onInsertCode = onInsertCode,
                        onExecuteToolCall = onExecuteToolCall,
                        onCancelToolCall = onCancelToolCall,
                        color = textColor,
                        isStreaming = true
                    )
                }
            }

            // Token usage
            if (message.usage != null && showTokenUsage) {
                Spacer(modifier = Modifier.height(8.dp))
                UsageRow(
                    usage = message.usage!!,
                    color = textColor
                )
            }
        }
    }
}

@Composable
private fun AiInputArea(
    inputText: String,
    onInputChange: (String) -> Unit,
    attachedImages: List<Uri>,
    onRemoveAttachedImage: (Int) -> Unit,
    onClearAttachedImages: () -> Unit,
    pendingFile: MessageContext.CurrentFile?,
    onRemovePendingFile: () -> Unit,
    pendingCode: MessageContext.SelectedCode?,
    onRemovePendingCode: () -> Unit,
    isLoading: Boolean,
    hasPendingToolExecution: Boolean,
    onSend: () -> Unit,
    onStop: () -> Unit,
    onAttachCurrentFile: () -> Unit,
    onAttachSelectedCode: () -> Unit,
    onAttachImage: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(8.dp)
    ) {
        // 附加内容显示区域
        val hasAttachments = attachedImages.isNotEmpty() || pendingFile != null || pendingCode != null

        if (hasAttachments) {
            val context = LocalContext.current
            // 附加图片
            if (attachedImages.isNotEmpty()) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = stringResource(Strings.ai_attached_images_count, attachedImages.size),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    TextButton(onClick = onClearAttachedImages, enabled = !isLoading) {
                        Text(text = stringResource(Strings.ai_clear_attachments))
                    }
                }
                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    items(attachedImages.size) { idx ->
                        val uri = attachedImages[idx]
                        Box(modifier = Modifier.size(64.dp)) {
                            AsyncImage(
                                model = ImageRequest.Builder(context)
                                    .data(uri)
                                    .crossfade(true)
                                    .build(),
                                contentDescription = "attached_image",
                                modifier = Modifier
                                    .size(64.dp)
                                    .clip(RoundedCornerShape(10.dp))
                            )
                            Surface(
                                modifier = Modifier
                                    .align(Alignment.TopEnd)
                                    .padding(2.dp),
                                shape = RoundedCornerShape(999.dp),
                                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.85f)
                            ) {
                                IconButton(
                                    onClick = { onRemoveAttachedImage(idx) },
                                    enabled = !isLoading,
                                    modifier = Modifier.size(24.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Close,
                                        contentDescription = stringResource(Strings.ai_remove_image),
                                        tint = MaterialTheme.colorScheme.error
                                    )
                                }
                            }
                        }
                    }
                }
                Spacer(modifier = Modifier.height(8.dp))
            }

            // 附加当前文件
            if (pendingFile != null) {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    color = MaterialTheme.colorScheme.secondaryContainer,
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = stringResource(Strings.ai_attached_file),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.7f)
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = pendingFile.fileName,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSecondaryContainer,
                                maxLines = 1
                            )
                            if (pendingFile.language.isNotBlank()) {
                                Text(
                                    text = pendingFile.language,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.6f)
                                )
                            }
                        }
                        IconButton(
                            onClick = onRemovePendingFile,
                            enabled = !isLoading
                        ) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = stringResource(Strings.ai_remove_attachment),
                                tint = MaterialTheme.colorScheme.error
                            )
                        }
                    }
                }
                Spacer(modifier = Modifier.height(8.dp))
            }

            // 附加选中代码
            if (pendingCode != null) {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    color = MaterialTheme.colorScheme.tertiaryContainer,
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = stringResource(Strings.ai_attached_code),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onTertiaryContainer.copy(alpha = 0.7f)
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "${pendingCode.fileName} (${pendingCode.startLine}-${pendingCode.endLine})",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onTertiaryContainer,
                                maxLines = 1
                            )
                            if (pendingCode.language.isNotBlank()) {
                                Text(
                                    text = pendingCode.language,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onTertiaryContainer.copy(alpha = 0.6f)
                                )
                            }
                        }
                        IconButton(
                            onClick = onRemovePendingCode,
                            enabled = !isLoading
                        ) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = stringResource(Strings.ai_remove_attachment),
                                tint = MaterialTheme.colorScheme.error
                            )
                        }
                    }
                }
                Spacer(modifier = Modifier.height(8.dp))
            }
        }

        // 上下文附加按钮
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            OutlinedButton(
                onClick = onAttachCurrentFile,
                modifier = Modifier.weight(1f).height(32.dp),
                enabled = !isLoading,
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)
            ) {
                Text(
                    text = stringResource(Strings.ai_attach_file),
                    style = MaterialTheme.typography.labelSmall,
                    maxLines = 1
                )
            }
            OutlinedButton(
                onClick = onAttachSelectedCode,
                modifier = Modifier.weight(1f).height(32.dp),
                enabled = !isLoading,
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)
            ) {
                Text(
                    text = stringResource(Strings.ai_attach_selection),
                    style = MaterialTheme.typography.labelSmall,
                    maxLines = 1
                )
            }
            OutlinedButton(
                onClick = onAttachImage,
                modifier = Modifier.weight(1f).height(32.dp),
                enabled = !isLoading,
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)
            ) {
                Text(
                    text = stringResource(Strings.ai_attach_image),
                    style = MaterialTheme.typography.labelSmall,
                    maxLines = 1
                )
            }
        }

        Spacer(modifier = Modifier.height(6.dp))

        // 输入框和发送按钮
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.Bottom
        ) {
            OutlinedTextField(
                value = inputText,
                onValueChange = onInputChange,
                modifier = Modifier
                    .weight(1f)
                    .defaultMinSize(minHeight = 48.dp),
                placeholder = {
                    Text(stringResource(Strings.ai_input_placeholder))
                },
                enabled = !isLoading,
                maxLines = 6
            )

            if (isLoading || hasPendingToolExecution) {
                IconButton(
                    onClick = { if (isLoading) onStop() },
                    enabled = isLoading,
                    modifier = Modifier.size(48.dp)
                ) {
                    if (isLoading) {
                        Icon(
                            imageVector = Icons.Outlined.Stop,
                            contentDescription = stringResource(Strings.ai_stop_generation),
                            tint = MaterialTheme.colorScheme.error
                        )
                    } else {
                        CircularProgressIndicator(
                            modifier = Modifier.size(24.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            } else {
                val canSend = inputText.isNotBlank() || attachedImages.isNotEmpty() || pendingFile != null || pendingCode != null
                IconButton(
                    onClick = onSend,
                    enabled = canSend,
                    modifier = Modifier.size(48.dp)
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.Send,
                        contentDescription = stringResource(Strings.ai_send),
                        tint = if (canSend) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        }
                    )
                }
            }
        }
    }
}

private data class PendingImage(
    val uri: Uri,
    val dataUrl: String
)

@Composable
private fun EmptyAiChatContent() {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(
                text = stringResource(Strings.ai_welcome_title),
                style = MaterialTheme.typography.headlineMedium
            )
            Text(
                text = stringResource(Strings.ai_welcome_subtitle),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = stringResource(Strings.ai_welcome_features),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

private fun copyToClipboard(context: Context, text: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    val clip = ClipData.newPlainText("code", text)
    clipboard.setPrimaryClip(clip)
}

private fun encodeImageToDataUrl(
    context: Context,
    uri: Uri,
    maxBytes: Int,
    maxDimension: Int,
): String? {
    return runCatching {
        val cr = context.contentResolver

        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        cr.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }

        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        val maxSide = maxOf(bounds.outWidth, bounds.outHeight)
        var sample = 1
        while (maxSide / sample > maxDimension) sample *= 2

        val opts = BitmapFactory.Options().apply {
            inSampleSize = sample.coerceAtLeast(1)
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }

        val bitmap = cr.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
            ?: return null

        val baos = ByteArrayOutputStream()
        var quality = 88
        bitmap.compress(Bitmap.CompressFormat.JPEG, quality, baos)
        while (baos.size() > maxBytes && quality > 40) {
            baos.reset()
            quality -= 10
            bitmap.compress(Bitmap.CompressFormat.JPEG, quality, baos)
        }
        bitmap.recycle()

        if (baos.size() > maxBytes) return null

        val b64 = Base64.encodeToString(baos.toByteArray(), Base64.NO_WRAP)
        "data:image/jpeg;base64,$b64"
    }.getOrNull()
}

@Composable
private fun ConversationListPanel(
    conversations: List<ConversationMeta>,
    currentConversationId: String,
    onSelectConversation: (String) -> Unit,
    onDeleteConversation: (String) -> Unit,
    onRenameConversation: (String, String) -> Unit,
    onClose: () -> Unit
) {
    Column(modifier = Modifier.fillMaxSize()) {
        // 标题栏
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp)
                .padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = stringResource(Strings.ai_conversation_history),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.weight(1f)
            )
            IconButton(onClick = onClose) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = stringResource(Strings.ai_close)
                )
            }
        }

        HorizontalDivider()

        // 对话列表
        if (conversations.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(24.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = stringResource(Strings.ai_no_conversations),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                items(conversations, key = { it.id }) { conversation ->
                    ConversationListItem(
                        conversation = conversation,
                        isSelected = conversation.id == currentConversationId,
                        onSelect = { onSelectConversation(conversation.id) },
                        onDelete = { onDeleteConversation(conversation.id) },
                        onRename = { newTitle -> onRenameConversation(conversation.id, newTitle) }
                    )
                }
            }
        }
    }
}

@Composable
private fun ConversationListItem(
    conversation: ConversationMeta,
    isSelected: Boolean,
    onSelect: () -> Unit,
    onDelete: () -> Unit,
    onRename: (String) -> Unit
) {
    val context = LocalContext.current
    var showDeleteDialog by remember { mutableStateOf(false) }
    var showRenameDialog by remember { mutableStateOf(false) }

    Surface(
        onClick = onSelect,
        modifier = Modifier.fillMaxWidth(),
        color = if (isSelected) {
            MaterialTheme.colorScheme.primaryContainer
        } else {
            MaterialTheme.colorScheme.surface
        },
        shape = RoundedCornerShape(8.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = conversation.title.ifEmpty {
                        stringResource(Strings.ai_untitled_conversation)
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = if (isSelected) FontWeight.Medium else FontWeight.Normal,
                    maxLines = 2,
                    color = if (isSelected) {
                        MaterialTheme.colorScheme.onPrimaryContainer
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    }
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = formatTimestamp(context, conversation.updatedAt),
                    style = MaterialTheme.typography.labelSmall,
                    color = if (isSelected) {
                        MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f)
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    }
                )
            }

            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                IconButton(
                    onClick = { showRenameDialog = true },
                    modifier = Modifier.size(36.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Edit,
                        contentDescription = stringResource(Strings.ai_rename),
                        modifier = Modifier.size(18.dp)
                    )
                }

                IconButton(
                    onClick = { showDeleteDialog = true },
                    modifier = Modifier.size(36.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Delete,
                        contentDescription = stringResource(Strings.ai_delete_conversation),
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        }
    }

    if (showRenameDialog) {
        var newTitle by remember { mutableStateOf(conversation.title) }
        TinaAlertDialog(
            onDismissRequest = { showRenameDialog = false },
            title = {
                TinaDialogTitleText(stringResource(Strings.ai_rename_conversation))
            },
            text = {
                TinaDialogContentColumn {
                    TinaDialogCard(
                        contentPadding = PaddingValues(14.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.14f),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        TinaTextField(
                            value = newTitle,
                            onValueChange = { newTitle = it },
                            label = stringResource(Strings.ai_conversation_title),
                            singleLine = true
                        )
                    }
                }
            },
            confirmButton = {
                TinaPrimaryButton(
                    text = stringResource(Strings.ai_rename),
                    onClick = {
                        if (newTitle.isNotBlank()) {
                            onRename(newTitle)
                            showRenameDialog = false
                        }
                    },
                    enabled = newTitle.isNotBlank()
                )
            },
            dismissButton = {
                TinaTextButton(
                    text = stringResource(Strings.ai_cancel),
                    onClick = { showRenameDialog = false }
                )
            }
        )
    }

    if (showDeleteDialog) {
        TinaConfirmDialog(
            title = stringResource(Strings.ai_delete_conversation),
            message = stringResource(Strings.ai_delete_conversation_confirm),
            confirmText = stringResource(Strings.ai_delete),
            dismissText = stringResource(Strings.ai_cancel),
            onConfirm = {
                onDelete()
                showDeleteDialog = false
            },
            onDismiss = { showDeleteDialog = false },
            isDanger = true
        )
    }
}

private fun formatTimestamp(context: Context, timestamp: Long): String {
    val now = System.currentTimeMillis()
    val diff = now - timestamp

    return when {
        diff < 60_000 -> context.getString(Strings.ai_time_just_now)
        diff < 3600_000 -> context.getString(Strings.ai_time_minutes_ago, diff / 60_000)
        diff < 86400_000 -> context.getString(Strings.ai_time_hours_ago, diff / 3600_000)
        diff < 604800_000 -> context.getString(Strings.ai_time_days_ago, diff / 86400_000)
        else -> {
            val date = java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.getDefault())
            date.format(java.util.Date(timestamp))
        }
    }
}

@Composable
private fun AiPanelToolbar(
    modelName: String,
    toolExecutionMode: ToolExecutionMode,
    isProcessing: Boolean,
    hasMessages: Boolean,
    onNewConversation: () -> Unit,
    onSummarizeAndContinue: () -> Unit,
    onToggleConversationList: () -> Unit,
    onToggleToolMode: () -> Unit,
    onOpenSettings: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(56.dp)
            .padding(horizontal = 12.dp)
    ) {
        // 主要内容行（标题和按钮）
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.CenterStart),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            // 标题和工具模式按钮
            Row(
                modifier = Modifier.weight(1f),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = stringResource(Strings.ai_assistant),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Medium
                )
                Surface(
                    color = if (toolExecutionMode == ToolExecutionMode.AUTO) {
                        MaterialTheme.colorScheme.primaryContainer
                    } else {
                        MaterialTheme.colorScheme.tertiaryContainer
                    },
                    shape = RoundedCornerShape(4.dp),
                    onClick = onToggleToolMode
                ) {
                    Text(
                        text = if (toolExecutionMode == ToolExecutionMode.AUTO) {
                            stringResource(Strings.ai_tool_mode_auto)
                        } else {
                            stringResource(Strings.ai_tool_mode_manual)
                        },
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 1.dp),
                        color = if (toolExecutionMode == ToolExecutionMode.AUTO) {
                            MaterialTheme.colorScheme.onPrimaryContainer
                        } else {
                            MaterialTheme.colorScheme.onTertiaryContainer
                        }
                    )
                }
            }

            IconButton(
                onClick = onToggleConversationList,
                enabled = !isProcessing
            ) {
                Icon(
                    imageVector = Icons.Default.History,
                    contentDescription = stringResource(Strings.ai_conversation_history),
                    modifier = Modifier.size(20.dp)
                )
            }

            // 新建对话下拉菜单
            Box {
                var expanded by remember { mutableStateOf(false) }

                IconButton(
                    onClick = { expanded = true },
                    enabled = !isProcessing && hasMessages
                ) {
                    Icon(
                        imageVector = Icons.Default.Add,
                        contentDescription = stringResource(Strings.ai_new_conversation),
                        modifier = Modifier.size(20.dp)
                    )
                }

                TinaDropdownMenu(
                    expanded = expanded,
                    onDismissRequest = { expanded = false }
                ) {
                    TinaDropdownMenuItem(
                        text = { Text(stringResource(Strings.ai_new_conversation)) },
                        onClick = {
                            expanded = false
                            onNewConversation()
                        }
                    )

                    if (hasMessages) {
                        TinaDropdownMenuItem(
                            text = { Text(stringResource(com.wuxianggujun.tinaide.core.i18n.R.string.ai_summarize_and_continue)) },
                            onClick = {
                                expanded = false
                                onSummarizeAndContinue()
                            }
                        )
                    }
                }
            }

            IconButton(onClick = onOpenSettings) {
                Icon(
                    imageVector = Icons.Outlined.Settings,
                    contentDescription = stringResource(Strings.ai_settings),
                    modifier = Modifier.size(20.dp)
                )
            }
        }

        // 模型名称固定在左下角
        Text(
            text = modelName,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(bottom = 4.dp)
        )
    }
}

/**
 * 获取默认的危险工具确认配置
 */
private fun getDefaultConfirmation(context: android.content.Context, tool: com.wuxianggujun.tinaide.ai.tools.AiTool): DangerousToolConfirmation = DangerousToolConfirmation(
    title = context.getString(Strings.ai_dangerous_tool_title),
    message = context.getString(Strings.ai_dangerous_tool_message, tool.getFriendlyName(context)),
    details = tool.getDetailedDescription(),
    confirmButtonText = context.getString(Strings.btn_confirm),
    cancelButtonText = context.getString(Strings.btn_cancel),
    severity = ConfirmationSeverity.WARNING
)

/**
 * 危险工具确认对话框
 */
@Composable
private fun DangerousToolConfirmationDialog(
    confirmation: DangerousToolConfirmation,
    onConfirm: () -> Unit,
    onCancel: () -> Unit
) {
    val detailCardColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.16f)
    val criticalCardColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.4f)

    TinaAlertDialog(
        onDismissRequest = onCancel,
        title = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    imageVector = when (confirmation.severity) {
                        ConfirmationSeverity.WARNING -> Icons.Default.Warning
                        ConfirmationSeverity.DANGER -> Icons.Default.Error
                        ConfirmationSeverity.CRITICAL -> Icons.Default.ErrorOutline
                    },
                    contentDescription = null,
                    tint = when (confirmation.severity) {
                        ConfirmationSeverity.WARNING -> Color(0xFFFFA726) // Orange
                        ConfirmationSeverity.DANGER -> Color(0xFFEF5350) // Red
                        ConfirmationSeverity.CRITICAL -> Color(0xFFDC143C) // Crimson
                    },
                    modifier = Modifier.size(24.dp)
                )
                TinaDialogTitleText(confirmation.title)
            }
        },
        text = {
            TinaDialogContentColumn {
                TinaDialogMessageCard(
                    message = confirmation.message,
                    color = detailCardColor
                )

                confirmation.details?.let { details ->
                    TinaDialogCard(
                        color = detailCardColor,
                        contentPadding = PaddingValues(12.dp),
                        verticalArrangement = Arrangement.spacedBy(0.dp)
                    ) {
                        Text(
                            text = details,
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                if (confirmation.severity == ConfirmationSeverity.CRITICAL) {
                    TinaDialogCard(
                        color = criticalCardColor,
                        contentPadding = PaddingValues(12.dp),
                        verticalArrangement = Arrangement.spacedBy(0.dp)
                    ) {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Default.ErrorOutline,
                                contentDescription = null,
                                tint = Color(0xFFDC143C),
                                modifier = Modifier.size(20.dp)
                            )
                            Text(
                                text = stringResource(Strings.ai_dangerous_tool_critical_tip),
                                style = MaterialTheme.typography.bodySmall,
                                color = Color(0xFFDC143C),
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            when (confirmation.severity) {
                ConfirmationSeverity.WARNING -> TinaPrimaryButton(
                    text = confirmation.confirmButtonText,
                    onClick = onConfirm
                )
                ConfirmationSeverity.DANGER,
                ConfirmationSeverity.CRITICAL -> TinaDangerButton(
                    text = confirmation.confirmButtonText,
                    onClick = onConfirm
                )
            }
        },
        dismissButton = {
            TinaTextButton(
                text = confirmation.cancelButtonText,
                onClick = onCancel
            )
        }
    )
}
