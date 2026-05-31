package com.wuxianggujun.tinaide.ui.compose.components

import android.graphics.BitmapFactory
import android.util.Base64
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BrokenImage
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.SubcomposeAsyncImage
import coil3.request.ImageRequest
import coil3.request.crossfade
import com.wuxianggujun.tinaide.ai.api.ChatMessage
import com.wuxianggujun.tinaide.ai.api.ChatRole
import com.wuxianggujun.tinaide.core.i18n.Strings
import com.wuxianggujun.tinaide.core.i18n.str
import com.wuxianggujun.tinaide.ui.compose.components.markdown.MarkdownBlock
import com.wuxianggujun.tinaide.ui.compose.components.markdown.extractThinkingTitle

/**
 * 聊天消息气泡组件
 */
@Composable
fun ChatMessageBubble(
    message: ChatMessage,
    onCopyCode: (String) -> Unit,
    onInsertCode: (String) -> Unit,
    modifier: Modifier = Modifier,
    onExecuteToolCall: ((com.wuxianggujun.tinaide.ai.api.ToolCall) -> Unit)? = null,
    onCancelToolCall: ((com.wuxianggujun.tinaide.ai.api.ToolCall) -> Unit)? = null,
    showTokenUsage: Boolean = false
) {
    val isUser = message.role == ChatRole.USER
    val toolCalls = remember(message.id, message.toolCalls) { message.toolCalls.orEmpty() }
    val hasToolCalls = toolCalls.isNotEmpty()
    val hasReasoning = remember(message.id, message.reasoningContent) {
        !message.reasoningContent.isNullOrBlank()
    }
    val usage = message.usage
    val context = LocalContext.current

    val imageUrls = remember(message.id, message.contentParts) {
        message.contentParts
            ?.asSequence()
            ?.filter { it.type == "image_url" }
            ?.mapNotNull { it.imageUrl?.url }
            ?.filter { it.isNotBlank() }
            ?.toList()
            .orEmpty()
    }

    // 检查消息内容是否包含代码块
    val hasCodeBlock = remember(message.id, message.content) {
        message.content.contains("```")
    }

    // 判断是否需要较大宽度：有图片、代码块或工具调用时使用较大宽度
    val needsLargeWidth = imageUrls.isNotEmpty() || hasCodeBlock || hasToolCalls

    val showToolCallsSection = !isUser && hasToolCalls
    val showReasoningSection = !isUser && hasReasoning
    val showUsage = !isUser && usage != null && showTokenUsage

    val textColor = if (isUser) {
        MaterialTheme.colorScheme.onPrimaryContainer
    } else {
        MaterialTheme.colorScheme.onSurface
    }

    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start
    ) {
        val contentModifier = Modifier
            .widthIn(max = if (needsLargeWidth) 360.dp else 280.dp)
            .wrapContentWidth()

        // 消息内容
        @Composable
        fun MessageContent() {
            Column {
                if (imageUrls.isNotEmpty()) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        imageUrls.forEach { url ->
                            // 处理 data URL 和普通 URL
                            if (url.startsWith("data:image/")) {
                                // Data URL - 解码并显示
                                val bitmap = remember(url) {
                                    try {
                                        val base64Data = url.substringAfter("base64,")
                                        val imageBytes = Base64.decode(base64Data, Base64.DEFAULT)
                                        BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size)
                                    } catch (e: Exception) {
                                        null
                                    }
                                }

                                if (bitmap != null) {
                                    Image(
                                        bitmap = bitmap.asImageBitmap(),
                                        contentDescription = stringResource(Strings.ai_image_content_description),
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .heightIn(max = 200.dp)
                                            .clip(RoundedCornerShape(10.dp)),
                                        contentScale = ContentScale.Fit
                                    )
                                } else {
                                    // 显示错误图标
                                    Surface(
                                        color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.3f),
                                        shape = RoundedCornerShape(10.dp),
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .height(120.dp)
                                    ) {
                                        Column(
                                            modifier = Modifier.fillMaxSize(),
                                            horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally,
                                            verticalArrangement = Arrangement.Center
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.BrokenImage,
                                                contentDescription = stringResource(Strings.ai_image_load_failed),
                                                tint = MaterialTheme.colorScheme.error,
                                                modifier = Modifier.size(48.dp)
                                            )
                                            Spacer(modifier = Modifier.height(8.dp))
                                            Text(
                                                text = stringResource(Strings.ai_image_load_failed),
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.error
                                            )
                                        }
                                    }
                                }
                            } else {
                                // 普通 URL - 使用 Coil 加载
                                SubcomposeAsyncImage(
                                    model = ImageRequest.Builder(context)
                                        .data(url)
                                        .crossfade(true)
                                        .build(),
                                    contentDescription = stringResource(Strings.ai_image_content_description),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .heightIn(max = 200.dp)
                                        .clip(RoundedCornerShape(10.dp)),
                                    error = {
                                        Surface(
                                            color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.3f),
                                            shape = RoundedCornerShape(10.dp),
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .height(120.dp)
                                        ) {
                                            Column(
                                                modifier = Modifier.fillMaxSize(),
                                                horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally,
                                                verticalArrangement = Arrangement.Center
                                            ) {
                                                Icon(
                                                    imageVector = Icons.Default.BrokenImage,
                                                    contentDescription = stringResource(Strings.ai_image_load_failed),
                                                    tint = MaterialTheme.colorScheme.error,
                                                    modifier = Modifier.size(48.dp)
                                                )
                                                Spacer(modifier = Modifier.height(8.dp))
                                                Text(
                                                    text = stringResource(Strings.ai_image_load_failed),
                                                    style = MaterialTheme.typography.bodySmall,
                                                    color = MaterialTheme.colorScheme.error
                                                )
                                            }
                                        }
                                    }
                                )
                            }
                        }
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                }

                if (showReasoningSection) {
                    Spacer(modifier = Modifier.height(8.dp))
                    ReasoningSection(
                        messageId = message.id,
                        reasoning = message.reasoningContent.orEmpty(),
                        onCopyCode = onCopyCode,
                        onInsertCode = onInsertCode,
                        color = textColor
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                }

                if (message.content.isNotBlank()) {
                    MarkdownBlock(
                        content = message.content,
                        onCodeCopy = onCopyCode,
                        onCodeInsert = onInsertCode,
                        modifier = Modifier.wrapContentWidth(),
                    )
                }

                if (showToolCallsSection) {
                    Spacer(modifier = Modifier.height(8.dp))
                    ToolCallsSection(
                        messageId = message.id,
                        toolCalls = toolCalls,
                        onCopyCode = onCopyCode,
                        onInsertCode = onInsertCode,
                        onExecuteToolCall = onExecuteToolCall,
                        onCancelToolCall = onCancelToolCall,
                        color = textColor,
                        isStreaming = false
                    )
                }

                if (showUsage) {
                    Spacer(modifier = Modifier.height(8.dp))
                    UsageRow(
                        usage = usage!!,
                        color = textColor
                    )
                }
            }
        }

        // 只有用户消息使用 Surface 卡片背景
        if (isUser) {
            Surface(
                color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f),
                shape = RoundedCornerShape(
                    topStart = 16.dp,
                    topEnd = 16.dp,
                    bottomStart = 16.dp,
                    bottomEnd = 4.dp
                ),
                modifier = contentModifier
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    MessageContent()
                }
            }
        } else {
            // AI 消息不使用 Surface，直接显示内容
            Column(modifier = contentModifier) {
                MessageContent()
            }
        }
    }
}

@Composable
internal fun UsageRow(
    usage: com.wuxianggujun.tinaide.ai.api.ChatUsage,
    color: Color,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
    ) {
        Text(
            text = stringResource(Strings.ai_token_usage_title),
            style = MaterialTheme.typography.labelSmall,
            color = color.copy(alpha = 0.7f),
            fontWeight = FontWeight.Medium
        )
        Text(
            text = stringResource(
                Strings.ai_token_usage_detail,
                usage.totalTokens,
                usage.promptTokens,
                usage.completionTokens
            ),
            style = MaterialTheme.typography.labelSmall,
            color = color.copy(alpha = 0.6f)
        )
    }
}

@Composable
internal fun ToolCallsSection(
    messageId: String,
    toolCalls: List<com.wuxianggujun.tinaide.ai.api.ToolCall>,
    onCopyCode: (String) -> Unit,
    onInsertCode: (String) -> Unit,
    onExecuteToolCall: ((com.wuxianggujun.tinaide.ai.api.ToolCall) -> Unit)?,
    onCancelToolCall: ((com.wuxianggujun.tinaide.ai.api.ToolCall) -> Unit)? = null,
    color: Color,
    isStreaming: Boolean = false,
) {
    // 如果工具调用数量 > 2，默认折叠，只显示前2个
    val shouldShowToggle = toolCalls.size > 2
    var expanded by remember(messageId) { mutableStateOf(false) }

    // 找到第一个待执行的工具调用索引
    val firstPendingIndex = remember(toolCalls) {
        toolCalls.indexOfFirst { it.executionStatus == com.wuxianggujun.tinaide.ai.api.ToolExecutionStatus.PENDING }
    }

    // 决定显示哪些工具
    val displayedToolCalls = remember(toolCalls, expanded) {
        if (shouldShowToggle && !expanded) {
            toolCalls.take(2) // 只显示前2个
        } else {
            toolCalls // 显示全部
        }
    }

    val context = androidx.compose.ui.platform.LocalContext.current

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        displayedToolCalls.forEachIndexed { idx, call ->
            val toolName = call.function?.name?.takeIf { it.isNotBlank() } ?: Strings.ai_search_unknown_placeholder.str()
            val name = com.wuxianggujun.tinaide.ai.tools.ToolRegistry.getTool(toolName)?.getFriendlyName(context) ?: toolName
            val args = call.function?.arguments?.trim().orEmpty()
            val status = call.executionStatus
            var showDetailsDialog by remember(call.id) { mutableStateOf(false) }

            // 工具调用卡片，带浅色背景
            Surface(
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
                    ) {
                        Row(
                            modifier = Modifier.weight(1f),
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
                        ) {
                            Text(
                                text = name,
                                style = MaterialTheme.typography.titleSmall,
                                color = color
                            )
                            // 状态指示器和文字
                            when (status) {
                                com.wuxianggujun.tinaide.ai.api.ToolExecutionStatus.EXECUTING -> {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(12.dp),
                                        strokeWidth = 1.5.dp,
                                        color = color
                                    )
                                    Text(
                                        text = stringResource(Strings.ai_tool_status_executing),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = color.copy(alpha = 0.7f)
                                    )
                                }
                                com.wuxianggujun.tinaide.ai.api.ToolExecutionStatus.SUCCESS -> {
                                    Icon(
                                        imageVector = Icons.Default.CheckCircle,
                                        contentDescription = stringResource(Strings.ai_tool_status_success),
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(14.dp)
                                    )
                                    Text(
                                        text = stringResource(Strings.ai_tool_status_success),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                }
                                com.wuxianggujun.tinaide.ai.api.ToolExecutionStatus.FAILED -> {
                                    Icon(
                                        imageVector = Icons.Default.Error,
                                        contentDescription = stringResource(Strings.ai_tool_status_failed),
                                        tint = MaterialTheme.colorScheme.error,
                                        modifier = Modifier.size(14.dp)
                                    )
                                    Text(
                                        text = stringResource(Strings.ai_tool_status_failed),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.error
                                    )
                                }
                                com.wuxianggujun.tinaide.ai.api.ToolExecutionStatus.CANCELLED -> {
                                    Icon(
                                        imageVector = Icons.Default.Error,
                                        contentDescription = stringResource(Strings.ai_tool_status_cancelled),
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                                        modifier = Modifier.size(14.dp)
                                    )
                                    Text(
                                        text = stringResource(Strings.ai_tool_status_cancelled),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                                    )
                                }
                                else -> {}
                            }
                        }

                        Row(
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
                        ) {
                            // 流式输出时只显示加载图标
                            if (isStreaming) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(16.dp),
                                    strokeWidth = 2.dp,
                                    color = color
                                )
                            } else {
                                // 查看详情图标按钮（眼睛图标）- 显示参数和结果
                                if (args.isNotBlank()) {
                                    IconButton(
                                        onClick = { showDetailsDialog = true },
                                        modifier = Modifier.size(32.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Visibility,
                                            contentDescription = stringResource(Strings.ai_tool_show_details),
                                            tint = color,
                                            modifier = Modifier.size(20.dp)
                                        )
                                    }
                                }

                                // 待执行状态：只有第一个待执行的工具显示执行按钮
                                if (status == com.wuxianggujun.tinaide.ai.api.ToolExecutionStatus.PENDING && onExecuteToolCall != null) {
                                    // 只有第一个待执行的工具显示执行按钮
                                    if (idx == firstPendingIndex) {
                                        // 执行按钮
                                        TextButton(
                                            onClick = { onExecuteToolCall(call) },
                                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)
                                        ) {
                                            Text(stringResource(Strings.ai_execute))
                                        }

                                        // 取消按钮（放在最后）
                                        if (onCancelToolCall != null) {
                                            TextButton(
                                                onClick = { onCancelToolCall(call) },
                                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)
                                            ) {
                                                Text(
                                                    text = stringResource(Strings.ai_cancel),
                                                    color = MaterialTheme.colorScheme.error
                                                )
                                            }
                                        }
                                    } else {
                                        // 后续待执行的工具显示"等待中"状态
                                        Text(
                                            text = stringResource(Strings.ai_waiting),
                                            style = MaterialTheme.typography.labelSmall,
                                            color = color.copy(alpha = 0.6f)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // 详情对话框（显示参数和结果）
            if (showDetailsDialog) {
                ToolDetailsDialog(
                    toolCall = call,
                    onDismiss = { showDetailsDialog = false },
                    onCopy = onCopyCode
                )
            }
        }

        // 展开/收起按钮（居中显示）
        if (shouldShowToggle) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center
            ) {
                TextButton(
                    onClick = { expanded = !expanded },
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
                ) {
                    Text(
                        text = if (expanded) {
                            stringResource(Strings.ai_toggle_tool_calls_hide)
                        } else {
                            stringResource(Strings.ai_toggle_tool_calls_show, toolCalls.size - 2)
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = color
                    )
                }
            }
        }
    }
}

@Composable
internal fun ReasoningSection(
    messageId: String,
    reasoning: String,
    onCopyCode: (String) -> Unit,
    onInsertCode: (String) -> Unit,
    color: Color,
) {
    var expanded by remember(messageId) { mutableStateOf(false) }
    val thinkingTitle = remember(reasoning) { reasoning.extractThinkingTitle() }

    TextButton(
        onClick = { expanded = !expanded },
        contentPadding = PaddingValues(horizontal = 4.dp, vertical = 2.dp)
    ) {
        Icon(
            imageVector = if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
            contentDescription = null,
            modifier = Modifier.size(16.dp),
            tint = color
        )
        Spacer(modifier = Modifier.width(4.dp))
        Text(
            text = thinkingTitle
                ?: stringResource(if (expanded) Strings.ai_toggle_reasoning_hide else Strings.ai_toggle_reasoning_show),
            style = MaterialTheme.typography.labelSmall,
            color = color,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }

    if (expanded) {
        MarkdownBlock(
            content = reasoning,
            onCodeCopy = onCopyCode,
            onCodeInsert = onInsertCode,
        )
    } else if (reasoning.length > 50) {
        // 收起时显示渐变淡出预览
        Box(
            modifier = Modifier
                .heightIn(max = 56.dp)
                .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
                .drawWithContent {
                    drawContent()
                    drawRect(
                        brush = Brush.verticalGradient(
                            0.3f to Color.Black,
                            1f to Color.Transparent,
                        ),
                        blendMode = BlendMode.DstIn,
                    )
                }
        ) {
            Text(
                text = reasoning.take(200),
                style = MaterialTheme.typography.bodySmall,
                color = color.copy(alpha = 0.6f),
            )
        }
    }
}

/**
 * 工具详情对话框（显示参数和执行结果）
 */
@Composable
private fun ToolDetailsDialog(
    toolCall: com.wuxianggujun.tinaide.ai.api.ToolCall,
    onDismiss: () -> Unit,
    onCopy: (String) -> Unit
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val toolName = toolCall.function?.name ?: Strings.ai_search_unknown_placeholder.str()
    val name = com.wuxianggujun.tinaide.ai.tools.ToolRegistry.getTool(toolName)?.getFriendlyName(context) ?: toolName
    val args = toolCall.function?.arguments?.trim().orEmpty()
    val status = toolCall.executionStatus
    val isSuccess = status == com.wuxianggujun.tinaide.ai.api.ToolExecutionStatus.SUCCESS
    val isCancelled = status == com.wuxianggujun.tinaide.ai.api.ToolExecutionStatus.CANCELLED
    val result = if (isSuccess) toolCall.executionResult else toolCall.executionError
    val hasResult = status == com.wuxianggujun.tinaide.ai.api.ToolExecutionStatus.SUCCESS ||
        status == com.wuxianggujun.tinaide.ai.api.ToolExecutionStatus.FAILED ||
        status == com.wuxianggujun.tinaide.ai.api.ToolExecutionStatus.CANCELLED
    val statusLabel = when (status) {
        com.wuxianggujun.tinaide.ai.api.ToolExecutionStatus.SUCCESS ->
            stringResource(Strings.ai_tool_status_success)
        com.wuxianggujun.tinaide.ai.api.ToolExecutionStatus.FAILED ->
            stringResource(Strings.ai_tool_status_failed)
        com.wuxianggujun.tinaide.ai.api.ToolExecutionStatus.CANCELLED ->
            stringResource(Strings.ai_tool_status_cancelled)
        else -> null
    }
    val statusLabelColor = when (status) {
        com.wuxianggujun.tinaide.ai.api.ToolExecutionStatus.SUCCESS ->
            MaterialTheme.colorScheme.primary
        com.wuxianggujun.tinaide.ai.api.ToolExecutionStatus.FAILED ->
            MaterialTheme.colorScheme.error
        com.wuxianggujun.tinaide.ai.api.ToolExecutionStatus.CANCELLED ->
            MaterialTheme.colorScheme.onSurfaceVariant
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }

    // 折叠状态
    var argsExpanded by remember { mutableStateOf(args.length <= 200) }
    var resultExpanded by remember { mutableStateOf((result?.length ?: 0) <= 200) }

    TinaAlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
            ) {
                when (status) {
                    com.wuxianggujun.tinaide.ai.api.ToolExecutionStatus.SUCCESS -> {
                        Icon(
                            imageVector = Icons.Default.CheckCircle,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(24.dp)
                        )
                    }
                    com.wuxianggujun.tinaide.ai.api.ToolExecutionStatus.FAILED -> {
                        Icon(
                            imageVector = Icons.Default.Error,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(24.dp)
                        )
                    }
                    com.wuxianggujun.tinaide.ai.api.ToolExecutionStatus.CANCELLED -> {
                        Icon(
                            imageVector = Icons.Default.Error,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                            modifier = Modifier.size(24.dp)
                        )
                    }
                    else -> {}
                }
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    TinaDialogTitleText(
                        title = name,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    statusLabel?.let { label ->
                        Text(
                            text = label,
                            style = MaterialTheme.typography.labelMedium,
                            color = statusLabelColor
                        )
                    }
                }
            }
        },
        text = {
            TinaDialogContentColumn {
                TinaDialogCard(
                    contentModifier = Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState()),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.14f)
                ) {
                    toolCall.id?.let { id ->
                        CollapsibleSection(
                            title = stringResource(Strings.ai_tool_id),
                            content = id,
                            isExpanded = true,
                            onExpandChange = {},
                            showExpandButton = false,
                            onCopy = { onCopy(id) },
                            backgroundColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                        )
                    }

                    if (args.isNotBlank()) {
                        CollapsibleSection(
                            title = stringResource(Strings.ai_tool_params),
                            content = args,
                            isExpanded = argsExpanded,
                            onExpandChange = { argsExpanded = it },
                            showExpandButton = args.length > 200,
                            onCopy = { onCopy(args) },
                            backgroundColor = MaterialTheme.colorScheme.surfaceVariant
                        )
                    }

                    if (hasResult && result != null) {
                        val resultTitle = when {
                            isSuccess -> stringResource(Strings.ai_tool_result_label)
                            isCancelled -> stringResource(Strings.ai_tool_cancelled_label)
                            else -> stringResource(Strings.ai_tool_error_label)
                        }

                        val resultBackgroundColor = when {
                            isSuccess -> MaterialTheme.colorScheme.tertiaryContainer
                            isCancelled -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                            else -> MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.3f)
                        }

                        val resultTextColor = when {
                            isSuccess -> MaterialTheme.colorScheme.onTertiaryContainer
                            isCancelled -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                            else -> MaterialTheme.colorScheme.error
                        }

                        val resultTitleColor = when {
                            isSuccess -> MaterialTheme.colorScheme.primary
                            isCancelled -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                            else -> MaterialTheme.colorScheme.error
                        }

                        CollapsibleSection(
                            title = resultTitle,
                            content = result,
                            isExpanded = resultExpanded,
                            onExpandChange = { resultExpanded = it },
                            showExpandButton = result.length > 200,
                            onCopy = { onCopy(result) },
                            backgroundColor = resultBackgroundColor,
                            textColor = resultTextColor,
                            titleColor = resultTitleColor
                        )
                    }
                }
            }
        },
        confirmButton = {
            TinaTextButton(
                text = stringResource(Strings.ai_close),
                onClick = onDismiss
            )
        }
    )
}

/**
 * 可折叠的内容区域
 */
@Composable
private fun CollapsibleSection(
    title: String,
    content: String,
    isExpanded: Boolean,
    onExpandChange: (Boolean) -> Unit,
    showExpandButton: Boolean,
    onCopy: () -> Unit,
    backgroundColor: Color,
    textColor: Color = MaterialTheme.colorScheme.onSurface,
    titleColor: Color = MaterialTheme.colorScheme.onSurface
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
                color = titleColor
            )
            TinaTextButton(
                text = stringResource(Strings.ai_copy),
                onClick = onCopy,
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)
            )
        }
        Surface(
            color = backgroundColor,
            shape = RoundedCornerShape(8.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                Text(
                    text = if (isExpanded || !showExpandButton) {
                        content
                    } else {
                        content.take(200) + "..."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = textColor
                )

                if (showExpandButton) {
                    TinaTextButton(
                        text = stringResource(
                            if (isExpanded) Strings.ai_collapse else Strings.ai_expand
                        ),
                        onClick = { onExpandChange(!isExpanded) },
                        contentPadding = PaddingValues(0.dp),
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }
            }
        }
    }
}
