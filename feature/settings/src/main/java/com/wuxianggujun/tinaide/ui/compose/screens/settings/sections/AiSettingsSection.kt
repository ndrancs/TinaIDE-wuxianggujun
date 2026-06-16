package com.wuxianggujun.tinaide.ui.compose.screens.settings.sections

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.wuxianggujun.tinaide.core.config.ai.AiChannelProvider
import com.wuxianggujun.tinaide.core.config.ai.AiConfig
import com.wuxianggujun.tinaide.core.config.ai.AiConfigProvider
import com.wuxianggujun.tinaide.core.config.ai.AiModelLoadResult
import com.wuxianggujun.tinaide.core.config.ai.AiProvider
import com.wuxianggujun.tinaide.core.config.ai.AiSettingsBridge
import com.wuxianggujun.tinaide.core.i18n.Strings
import com.wuxianggujun.tinaide.ui.compose.components.TinaAlertDialog
import com.wuxianggujun.tinaide.ui.compose.components.TinaDialogCard
import com.wuxianggujun.tinaide.ui.compose.components.TinaDialogContentColumn
import com.wuxianggujun.tinaide.ui.compose.components.TinaDialogMessageCard
import com.wuxianggujun.tinaide.ui.compose.components.TinaDialogTitleText
import com.wuxianggujun.tinaide.ui.compose.components.TinaPrimaryButton
import com.wuxianggujun.tinaide.ui.compose.components.TinaSingleChoiceDialog
import com.wuxianggujun.tinaide.ui.compose.components.TinaTextButton
import com.wuxianggujun.tinaide.ui.compose.screens.settings.components.SettingsCard
import com.wuxianggujun.tinaide.ui.compose.screens.settings.components.SettingsCategoryTitle
import com.wuxianggujun.tinaide.ui.compose.screens.settings.components.SettingsClickableItem
import com.wuxianggujun.tinaide.ui.compose.screens.settings.components.SettingsSwitchItem
import kotlinx.coroutines.launch
import org.koin.compose.koinInject
import timber.log.Timber

/**
 * AI 设置页面
 */
@Composable
internal fun AiSettingsSection() {
    val logTag = "AiSettings"
    val context = LocalContext.current
    val aiConfigProvider: AiConfigProvider = koinInject()
    val aiSettingsBridge: AiSettingsBridge = koinInject()
    val channelRepository: AiChannelProvider = koinInject()
    val config by aiConfigProvider.configFlow.collectAsState(initial = aiConfigProvider.getCurrentConfig())
    val channels by channelRepository.channelsFlow.collectAsState(initial = emptyList())
    val coroutineScope = rememberCoroutineScope()

    var customModels by remember { mutableStateOf<List<String>?>(null) }
    var customModelsLoading by remember { mutableStateOf(false) }
    var manualModelInput by remember { mutableStateOf(false) }

    // 对话框状态:sealed interface 聚合,避免同时打开两个对话框的非法状态。
    var activeDialog by remember { mutableStateOf<AiSettingsDialog>(AiSettingsDialog.None) }

    val activeChannel = channels.firstOrNull { it.id == config.activeChannelId }
    val showModelDialog = activeDialog is AiSettingsDialog.Model
    fun saveSelectedModel(selectedModel: String) {
        aiConfigProvider.saveConfig(config.copy(generation = config.generation.copy(model = selectedModel)))
        activeDialog = AiSettingsDialog.None
    }

    LaunchedEffect(showModelDialog) {
        if (!showModelDialog) {
            manualModelInput = false
        }
    }

    LaunchedEffect(showModelDialog, config.activeChannelId, activeChannel?.updatedAt) {
        if (!showModelDialog) return@LaunchedEffect

        customModelsLoading = true
        when (val result = aiSettingsBridge.loadModels(config)) {
            is AiModelLoadResult.Success -> {
                customModels = AiSettingsSectionSupport.normalizeCustomModels(result.models)
            }
            AiModelLoadResult.ConfigurationRequired -> {
                Timber.tag(logTag).w("Skipping custom model load because API key is blank")
                customModels = null
            }
            is AiModelLoadResult.Failure -> {
                Timber.tag(logTag).e("Failed to load custom models: %s", result.message)
                customModels = AiSettingsSectionSupport.resolveCustomModelFallback(
                    fallbackModels = result.fallbackModels,
                    provider = activeChannel?.provider ?: AiProvider.DEEPSEEK,
                )
            }
        }
        customModelsLoading = false
    }

    Spacer(modifier = Modifier.height(8.dp))

    // 基本设置
    SettingsCategoryTitle(stringResource(Strings.settings_cat_ai))

    SettingsCard {
        SettingsClickableItem(
            title = stringResource(Strings.settings_ai_channel_management),
            subtitle = activeChannel?.let { channel ->
                val providerName = stringResource(
                    AiSettingsSectionSupport.resolveProviderDisplayNameRes(channel.provider)
                )
                stringResource(
                    Strings.settings_ai_channel_provider_base_url,
                    providerName,
                    AiSettingsSectionSupport.resolveBaseUrlPreview(channel.baseUrl),
                )
            }
            ?: stringResource(Strings.settings_ai_channel_list_empty),
            value = activeChannel?.name ?: stringResource(Strings.settings_ai_not_configured),
            onClick = { activeDialog = AiSettingsDialog.ChannelManagement },
            showDivider = true
        )
        SettingsClickableItem(
            title = stringResource(Strings.settings_ai_model),
            value = config.generation.model.ifEmpty { "-" },
            onClick = { activeDialog = AiSettingsDialog.Model },
            showDivider = false
        )
    }

    // 高级设置
    SettingsCategoryTitle(stringResource(Strings.settings_ai_advanced))

    SettingsCard {
        SettingsSwitchItem(
            title = stringResource(Strings.settings_ai_tools),
            subtitle = stringResource(Strings.settings_ai_tools_subtitle),
            checked = config.tools.enableTools,
            onCheckedChange = { checked ->
                aiConfigProvider.saveConfig(config.copy(tools = config.tools.copy(enableTools = checked)))
            },
            showDivider = true
        )
        SettingsSwitchItem(
            title = stringResource(Strings.settings_ai_allow_dangerous_tools_auto),
            subtitle = stringResource(Strings.settings_ai_allow_dangerous_tools_auto_subtitle),
            checked = config.tools.allowDangerousToolsAuto,
            enabled = config.tools.enableTools,
            onCheckedChange = { checked ->
                aiConfigProvider.saveConfig(config.copy(tools = config.tools.copy(allowDangerousToolsAuto = checked)))
            },
            showDivider = true
        )
        SettingsSwitchItem(
            title = stringResource(Strings.settings_ai_deep_thinking),
            subtitle = stringResource(Strings.settings_ai_deep_thinking_subtitle),
            checked = config.thinking.enableDeepThinking,
            onCheckedChange = { checked ->
                aiConfigProvider.saveConfig(config.copy(thinking = config.thinking.copy(enableDeepThinking = checked)))
            },
            showDivider = true
        )
        SettingsClickableItem(
            title = stringResource(Strings.settings_ai_budget_tokens),
            value = config.thinking.budgetTokens.toString(),
            onClick = { activeDialog = AiSettingsDialog.BudgetTokens },
            showDivider = true
        )
        SettingsClickableItem(
            title = stringResource(Strings.settings_ai_image_detail),
            value = stringResource(
                AiSettingsSectionSupport.resolveImageDetailLabelRes(config.generation.imageDetail)
            ),
            onClick = { activeDialog = AiSettingsDialog.ImageDetail },
            showDivider = true
        )
        SettingsClickableItem(
            title = stringResource(Strings.settings_ai_max_tokens),
            value = config.generation.maxTokens.toString(),
            onClick = { activeDialog = AiSettingsDialog.MaxTokens },
            showDivider = true
        )
        SettingsClickableItem(
            title = stringResource(Strings.settings_ai_temperature),
            value = String.format("%.1f", config.generation.temperature),
            onClick = { activeDialog = AiSettingsDialog.Temperature },
            showDivider = true
        )
        SettingsClickableItem(
            title = stringResource(Strings.settings_ai_timeout),
            value = "${config.network.timeout}s",
            onClick = { activeDialog = AiSettingsDialog.Timeout },
            showDivider = true
        )
        SettingsClickableItem(
            title = stringResource(Strings.settings_ai_retry_count),
            value = config.network.retryCount.toString(),
            onClick = { activeDialog = AiSettingsDialog.RetryCount },
            showDivider = true
        )
        SettingsClickableItem(
            title = stringResource(Strings.settings_ai_retry_delay),
            value = "${config.network.retryDelaySeconds}s",
            onClick = { activeDialog = AiSettingsDialog.RetryDelay },
            showDivider = true
        )
        SettingsClickableItem(
            title = stringResource(Strings.settings_ai_system_prompt),
            subtitle = AiSettingsSectionSupport.resolvePromptPreview(config.prompt.systemPrompt),
            onClick = { activeDialog = AiSettingsDialog.SystemPrompt },
            showDivider = true
        )
        SettingsClickableItem(
            title = stringResource(Strings.settings_ai_summary_prompt),
            subtitle = AiSettingsSectionSupport.resolvePromptPreview(config.prompt.summaryPrompt),
            onClick = { activeDialog = AiSettingsDialog.SummaryPrompt },
            showDivider = true
        )
        SettingsClickableItem(
            title = stringResource(Strings.settings_ai_manage_tools),
            subtitle = stringResource(Strings.settings_ai_manage_tools_subtitle),
            onClick = { activeDialog = AiSettingsDialog.Tools },
            showDivider = false
        )
    }

    Spacer(modifier = Modifier.height(16.dp))

    // 系统提示词模板对话框
    if (activeDialog is AiSettingsDialog.SystemPrompt) {
        var selectedTab by remember { mutableIntStateOf(0) }
        var customPrompt by remember { mutableStateOf(config.prompt.systemPrompt) }

        TinaAlertDialog(
            onDismissRequest = { activeDialog = AiSettingsDialog.None },
            title = { TinaDialogTitleText(stringResource(Strings.settings_ai_system_prompt)) },
            text = {
                TinaDialogContentColumn {
                    // 标签页：模板 / 自定义
                    PrimaryTabRow(selectedTabIndex = selectedTab) {
                        Tab(
                            selected = selectedTab == 0,
                            onClick = { selectedTab = 0 },
                            text = { Text(stringResource(Strings.settings_ai_prompt_templates)) }
                        )
                        Tab(
                            selected = selectedTab == 1,
                            onClick = { selectedTab = 1 },
                            text = { Text(stringResource(Strings.settings_ai_prompt_custom)) }
                        )
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    when (selectedTab) {
                        0 -> {
                            // 模板列表
                            LazyColumn(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(300.dp)
                            ) {
                                items(AiConfig.SYSTEM_PROMPT_TEMPLATES.entries.toList()) { (key, template) ->
                                    val templateName = when (key) {
                                        "default" -> stringResource(Strings.settings_ai_prompt_default)
                                        "code_assistant" -> stringResource(Strings.settings_ai_prompt_code_assistant)
                                        "code_reviewer" -> stringResource(Strings.settings_ai_prompt_code_reviewer)
                                        "bug_analyzer" -> stringResource(Strings.settings_ai_prompt_bug_analyzer)
                                        "refactoring_expert" -> stringResource(Strings.settings_ai_prompt_refactoring)
                                        "documentation_writer" -> stringResource(Strings.settings_ai_prompt_documentation)
                                        else -> key
                                    }

                                    Surface(
                                        onClick = {
                                            aiConfigProvider.saveConfig(config.copy(prompt = config.prompt.copy(systemPrompt = template)))
                                            activeDialog = AiSettingsDialog.None
                                        },
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(vertical = 4.dp),
                                        shape = RoundedCornerShape(8.dp),
                                        color = if (config.prompt.systemPrompt == template) {
                                            MaterialTheme.colorScheme.primaryContainer
                                        } else {
                                            MaterialTheme.colorScheme.surface
                                        }
                                    ) {
                                        Column(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(12.dp)
                                        ) {
                                            Text(
                                                text = templateName,
                                                style = MaterialTheme.typography.titleSmall,
                                                fontWeight = FontWeight.Medium
                                            )
                                            Spacer(modifier = Modifier.height(4.dp))
                                            Text(
                                                text = template.take(80) + if (template.length > 80) "..." else "",
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                maxLines = 2
                                            )
                                        }
                                    }
                                }
                            }
                        }
                        1 -> {
                            // 自定义输入
                            OutlinedTextField(
                                value = customPrompt,
                                onValueChange = { customPrompt = it },
                                label = { Text(stringResource(Strings.settings_ai_system_prompt)) },
                                minLines = 10,
                                maxLines = 15,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(300.dp)
                            )
                        }
                    }
                }
            },
            confirmButton = {
                if (selectedTab == 1) {
                    TinaPrimaryButton(
                        text = stringResource(Strings.settings_ai_save),
                        onClick = {
                            aiConfigProvider.saveConfig(config.copy(prompt = config.prompt.copy(systemPrompt = customPrompt)))
                            activeDialog = AiSettingsDialog.None
                        }
                    )
                }
            },
            dismissButton = {
                TinaTextButton(
                    text = stringResource(Strings.ai_close),
                    onClick = { activeDialog = AiSettingsDialog.None }
                )
            }
        )
    }

    Spacer(modifier = Modifier.height(16.dp))

    // 模型选择对话框
    if (showModelDialog) {
        when (
            val dialogSpec = if (manualModelInput) {
                AiSettingsModelDialogSpec.ManualInput(config.generation.model)
            } else {
                AiSettingsSectionSupport.resolveModelDialogSpec(
                    provider = activeChannel?.provider ?: AiProvider.DEEPSEEK,
                    currentModel = config.generation.model,
                    customModelsLoading = customModelsLoading,
                    customModels = customModels,
                )
            }
        ) {
            AiSettingsModelDialogSpec.Loading -> {
                TinaAlertDialog(
                    onDismissRequest = { activeDialog = AiSettingsDialog.None },
                    title = { TinaDialogTitleText(stringResource(Strings.settings_ai_model)) },
                    text = {
                        TinaDialogContentColumn {
                            TinaDialogMessageCard(
                                message = stringResource(Strings.settings_ai_loading_models)
                            )
                        }
                    },
                    confirmButton = {
                        TinaTextButton(
                            text = stringResource(Strings.ai_close),
                            onClick = { activeDialog = AiSettingsDialog.None }
                        )
                    }
                )
            }
            is AiSettingsModelDialogSpec.Selectable -> {
                val currentModelOption = listOf(config.generation.model).filter { it.isNotBlank() }
                val selectableModels = (currentModelOption + dialogSpec.models).distinct()
                val customModelOption = AiSettingsSectionSupport.CUSTOM_MODEL_OPTION_VALUE to
                    stringResource(Strings.settings_ai_model_custom)
                val modelOptions = selectableModels.map { model -> model to model } + customModelOption
                TinaSingleChoiceDialog(
                    title = stringResource(Strings.settings_ai_select_model),
                    options = modelOptions,
                    selectedValue = config.generation.model,
                    onSelected = { selectedModel ->
                        if (selectedModel == AiSettingsSectionSupport.CUSTOM_MODEL_OPTION_VALUE) {
                            manualModelInput = true
                        } else {
                            saveSelectedModel(selectedModel)
                        }
                    },
                    onDismiss = { activeDialog = AiSettingsDialog.None }
                )
            }
            is AiSettingsModelDialogSpec.ManualInput -> {
                Timber.tag(logTag).w("Falling back to manual model input because no model list is available")
                var customModel by remember { mutableStateOf(dialogSpec.initialValue) }
                TinaAlertDialog(
                    onDismissRequest = { activeDialog = AiSettingsDialog.None },
                    title = { TinaDialogTitleText(stringResource(Strings.settings_ai_model)) },
                    text = {
                        TinaDialogContentColumn {
                            OutlinedTextField(
                                value = customModel,
                                onValueChange = { customModel = it },
                                label = { Text(stringResource(Strings.settings_ai_model)) },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    },
                    confirmButton = {
                        TinaPrimaryButton(
                            text = stringResource(Strings.settings_ai_save),
                            onClick = {
                                saveSelectedModel(customModel)
                            }
                        )
                    },
                    dismissButton = {
                        TinaTextButton(
                            text = stringResource(Strings.ai_close),
                            onClick = { activeDialog = AiSettingsDialog.None }
                        )
                    }
                )
            }
        }
    }

    if (activeDialog is AiSettingsDialog.ImageDetail) {
        val options = listOf(
            "auto" to stringResource(Strings.settings_ai_image_detail_auto),
            "low" to stringResource(Strings.settings_ai_image_detail_low),
            "high" to stringResource(Strings.settings_ai_image_detail_high),
        )
        TinaSingleChoiceDialog(
            title = stringResource(Strings.settings_ai_image_detail),
            options = options,
            selectedValue = config.generation.imageDetail,
            onSelected = { selected ->
                aiConfigProvider.saveConfig(config.copy(generation = config.generation.copy(imageDetail = selected)))
                activeDialog = AiSettingsDialog.None
            },
            onDismiss = { activeDialog = AiSettingsDialog.None }
        )
    }

    // 渠道管理（列表）对话框
    if (activeDialog is AiSettingsDialog.ChannelManagement) {
        AiChannelManagementDialog(
            channels = channels,
            activeChannelId = config.activeChannelId,
            onDismiss = { activeDialog = AiSettingsDialog.None },
            onAdd = {
                activeDialog = AiSettingsDialog.ChannelEdit(initial = null)
            },
            onEdit = { channel ->
                activeDialog = AiSettingsDialog.ChannelEdit(initial = channel)
            },
            onSetActive = { channel ->
                val generation = config.generation.copy(
                    model = config.generation.model.ifBlank {
                        channel.provider.defaultModels.firstOrNull().orEmpty()
                    },
                )
                aiConfigProvider.saveConfig(config.copy(activeChannelId = channel.id, generation = generation))
            },
            onDelete = { channel ->
                coroutineScope.launch {
                    channelRepository.delete(channel.id)
                    if (config.activeChannelId == channel.id) {
                        aiConfigProvider.saveConfig(config.copy(activeChannelId = null))
                    }
                }
            },
        )
    }

    (activeDialog as? AiSettingsDialog.ChannelEdit)?.let { dlg ->
        AiChannelEditDialog(
            initial = dlg.initial,
            onLoadApiKey = { channelId -> channelRepository.getApiKey(channelId) },
            onDismiss = {
                activeDialog = AiSettingsDialog.None
            },
            onSave = { name, provider, baseUrl, apiKey ->
                coroutineScope.launch {
                    val existing = dlg.initial
                    val storageModel = existing?.model
                        ?.takeIf { it.isNotBlank() }
                        ?: config.generation.model.ifBlank { provider.defaultModels.firstOrNull().orEmpty() }
                    if (existing == null) {
                        val created = channelRepository.add(
                            name = name,
                            provider = provider,
                            baseUrl = baseUrl,
                            model = storageModel,
                            apiKey = apiKey,
                        )
                        // 首个新增默认激活
                        if (config.activeChannelId.isNullOrBlank()) {
                            val generation = config.generation.copy(
                                model = config.generation.model.ifBlank { storageModel },
                            )
                            aiConfigProvider.saveConfig(
                                config.copy(activeChannelId = created.id, generation = generation)
                            )
                        }
                    } else {
                        channelRepository.update(
                            id = existing.id,
                            name = name,
                            provider = provider,
                            baseUrl = baseUrl,
                            model = storageModel,
                            apiKey = apiKey,
                        )
                    }
                    activeDialog = AiSettingsDialog.None
                }
            },
        )
    }

    // Max Tokens 输入对话框
    if (activeDialog is AiSettingsDialog.MaxTokens) {
        var maxTokens by remember { mutableIntStateOf(config.generation.maxTokens) }

        TinaAlertDialog(
            onDismissRequest = { activeDialog = AiSettingsDialog.None },
            title = { TinaDialogTitleText(stringResource(Strings.settings_ai_max_tokens)) },
            text = {
                TinaDialogContentColumn {
                    OutlinedTextField(
                        value = maxTokens.toString(),
                        onValueChange = { value ->
                            maxTokens = AiSettingsSectionSupport.parseMaxTokensInput(
                                value = value,
                                currentValue = maxTokens,
                            )
                        },
                        label = { Text(stringResource(Strings.settings_ai_max_tokens)) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                TinaPrimaryButton(
                    text = stringResource(Strings.settings_ai_save),
                    onClick = {
                        aiConfigProvider.saveConfig(config.copy(generation = config.generation.copy(maxTokens = maxTokens)))
                        activeDialog = AiSettingsDialog.None
                    }
                )
            },
            dismissButton = {
                TinaTextButton(
                    text = stringResource(Strings.ai_close),
                    onClick = { activeDialog = AiSettingsDialog.None }
                )
            }
        )
    }

    // Budget Tokens 输入对话框
    if (activeDialog is AiSettingsDialog.BudgetTokens) {
        var budgetTokens by remember { mutableIntStateOf(config.thinking.budgetTokens) }

        TinaAlertDialog(
            onDismissRequest = { activeDialog = AiSettingsDialog.None },
            title = { TinaDialogTitleText(stringResource(Strings.settings_ai_budget_tokens)) },
            text = {
                TinaDialogContentColumn {
                    OutlinedTextField(
                        value = budgetTokens.toString(),
                        onValueChange = { value ->
                            budgetTokens = AiSettingsSectionSupport.parseBudgetTokensInput(
                                value = value,
                                currentValue = budgetTokens,
                            )
                        },
                        label = { Text(stringResource(Strings.settings_ai_budget_tokens)) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                TinaPrimaryButton(
                    text = stringResource(Strings.settings_ai_save),
                    onClick = {
                        aiConfigProvider.saveConfig(config.copy(thinking = config.thinking.copy(budgetTokens = budgetTokens)))
                        activeDialog = AiSettingsDialog.None
                    }
                )
            },
            dismissButton = {
                TinaTextButton(
                    text = stringResource(Strings.ai_close),
                    onClick = { activeDialog = AiSettingsDialog.None }
                )
            }
        )
    }

    // Temperature 滑块对话框
    if (activeDialog is AiSettingsDialog.Temperature) {
        var temperature by remember { mutableFloatStateOf(config.generation.temperature) }

        TinaAlertDialog(
            onDismissRequest = { activeDialog = AiSettingsDialog.None },
            title = { TinaDialogTitleText(stringResource(Strings.settings_ai_temperature)) },
            text = {
                TinaDialogContentColumn {
                    TinaDialogCard {
                        androidx.compose.foundation.layout.Column {
                            Text(
                                text = String.format("%.1f", temperature),
                                style = MaterialTheme.typography.bodyLarge,
                                modifier = Modifier.padding(bottom = 8.dp)
                            )
                            Slider(
                                value = temperature,
                                onValueChange = { temperature = it },
                                valueRange = 0f..2f,
                                steps = 19
                            )
                        }
                    }
                }
            },
            confirmButton = {
                TinaPrimaryButton(
                    text = stringResource(Strings.settings_ai_save),
                    onClick = {
                        aiConfigProvider.saveConfig(config.copy(generation = config.generation.copy(temperature = temperature)))
                        activeDialog = AiSettingsDialog.None
                    }
                )
            },
            dismissButton = {
                TinaTextButton(
                    text = stringResource(Strings.ai_close),
                    onClick = { activeDialog = AiSettingsDialog.None }
                )
            }
        )
    }

    // Timeout 输入对话框
    if (activeDialog is AiSettingsDialog.Timeout) {
        var timeout by remember { mutableIntStateOf(config.network.timeout) }

        TinaAlertDialog(
            onDismissRequest = { activeDialog = AiSettingsDialog.None },
            title = { TinaDialogTitleText(stringResource(Strings.settings_ai_timeout)) },
            text = {
                TinaDialogContentColumn {
                    OutlinedTextField(
                        value = timeout.toString(),
                        onValueChange = { value ->
                            timeout = AiSettingsSectionSupport.parseTimeoutInput(
                                value = value,
                                currentValue = timeout,
                            )
                        },
                        label = { Text(stringResource(Strings.settings_ai_timeout)) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                TinaPrimaryButton(
                    text = stringResource(Strings.settings_ai_save),
                    onClick = {
                        aiConfigProvider.saveConfig(config.copy(network = config.network.copy(timeout = timeout)))
                        activeDialog = AiSettingsDialog.None
                    }
                )
            },
            dismissButton = {
                TinaTextButton(
                    text = stringResource(Strings.ai_close),
                    onClick = { activeDialog = AiSettingsDialog.None }
                )
            }
        )
    }

    // 重试次数对话框
    if (activeDialog is AiSettingsDialog.RetryCount) {
        var retryCount by remember { mutableIntStateOf(config.network.retryCount) }

        TinaAlertDialog(
            onDismissRequest = { activeDialog = AiSettingsDialog.None },
            title = { TinaDialogTitleText(stringResource(Strings.settings_ai_retry_count)) },
            text = {
                TinaDialogContentColumn {
                    TinaDialogCard {
                        Column(modifier = Modifier.fillMaxWidth()) {
                            Text(
                                text = retryCount.toString(),
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(bottom = 8.dp)
                            )
                            Slider(
                                value = retryCount.toFloat(),
                                onValueChange = { value ->
                                    retryCount = AiSettingsSectionSupport.normalizeRetryCountSliderValue(value)
                                },
                                valueRange = 1f..10f,
                                steps = 8,
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    }
                }
            },
            confirmButton = {
                TinaPrimaryButton(
                    text = stringResource(Strings.settings_ai_save),
                    onClick = {
                        aiConfigProvider.saveConfig(config.copy(network = config.network.copy(retryCount = retryCount)))
                        activeDialog = AiSettingsDialog.None
                    }
                )
            },
            dismissButton = {
                TinaTextButton(
                    text = stringResource(Strings.ai_close),
                    onClick = { activeDialog = AiSettingsDialog.None }
                )
            }
        )
    }

    // 重试间隔对话框
    if (activeDialog is AiSettingsDialog.RetryDelay) {
        var retryDelay by remember { mutableIntStateOf(config.network.retryDelaySeconds) }

        TinaAlertDialog(
            onDismissRequest = { activeDialog = AiSettingsDialog.None },
            title = { TinaDialogTitleText(stringResource(Strings.settings_ai_retry_delay)) },
            text = {
                TinaDialogContentColumn {
                    OutlinedTextField(
                        value = retryDelay.toString(),
                        onValueChange = { value ->
                            retryDelay = AiSettingsSectionSupport.parseRetryDelayInput(
                                value = value,
                                currentValue = retryDelay,
                            )
                        },
                        label = { Text(stringResource(Strings.settings_ai_retry_delay)) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                TinaPrimaryButton(
                    text = stringResource(Strings.settings_ai_save),
                    onClick = {
                        aiConfigProvider.saveConfig(config.copy(network = config.network.copy(retryDelaySeconds = retryDelay)))
                        activeDialog = AiSettingsDialog.None
                    }
                )
            },
            dismissButton = {
                TinaTextButton(
                    text = stringResource(Strings.ai_close),
                    onClick = { activeDialog = AiSettingsDialog.None }
                )
            }
        )
    }

    // 总结提示词对话框
    if (activeDialog is AiSettingsDialog.SummaryPrompt) {
        var summaryPrompt by remember { mutableStateOf(config.prompt.summaryPrompt) }

        TinaAlertDialog(
            onDismissRequest = { activeDialog = AiSettingsDialog.None },
            title = { TinaDialogTitleText(stringResource(Strings.settings_ai_summary_prompt)) },
            text = {
                TinaDialogContentColumn {
                    OutlinedTextField(
                        value = summaryPrompt,
                        onValueChange = { summaryPrompt = it },
                        label = { Text(stringResource(Strings.settings_ai_summary_prompt)) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(200.dp),
                        maxLines = 8
                    )
                }
            },
            confirmButton = {
                TinaPrimaryButton(
                    text = stringResource(Strings.settings_ai_save),
                    onClick = {
                        aiConfigProvider.saveConfig(config.copy(prompt = config.prompt.copy(summaryPrompt = summaryPrompt)))
                        activeDialog = AiSettingsDialog.None
                    }
                )
            },
            dismissButton = {
                TinaTextButton(
                    text = stringResource(Strings.ai_close),
                    onClick = { activeDialog = AiSettingsDialog.None }
                )
            }
        )
    }

    // 工具管理对话框
    if (activeDialog is AiSettingsDialog.Tools) {
        val allTools = remember { aiSettingsBridge.getToolItems(context) }
        val toolStates = remember {
            mutableStateMapOf<String, Boolean>().apply {
                putAll(aiSettingsBridge.getToolEnabledStates())
            }
        }

        // 按分类分组工具
        val toolsByCategory = remember(allTools) {
            allTools.groupBy { it.categoryLabel }
                .toSortedMap()
        }

        TinaAlertDialog(
            onDismissRequest = { activeDialog = AiSettingsDialog.None },
            title = { TinaDialogTitleText(stringResource(Strings.settings_ai_tools_dialog_title)) },
            text = {
                TinaDialogContentColumn {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(400.dp)
                    ) {
                        toolsByCategory.forEach { (category, tools) ->
                            // 分类标题
                            item(key = "category_$category") {
                                Text(
                                    text = category,
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.padding(vertical = 8.dp, horizontal = 4.dp)
                                )
                            }

                            // 该分类下的工具
                            items(
                                items = tools,
                                key = { tool -> tool.name }
                            ) { tool ->
                                val isEnabled = toolStates[tool.name] ?: tool.enabledByDefault

                                SettingsSwitchItem(
                                    title = tool.displayName,
                                    subtitle = tool.description,
                                    checked = isEnabled,
                                    onCheckedChange = { checked ->
                                        toolStates[tool.name] = checked
                                    },
                                    showDivider = tool != tools.last()
                                )
                            }

                            // 分类之间的间距
                            if (category != toolsByCategory.keys.last()) {
                                item(key = "spacer_$category") {
                                    Spacer(modifier = Modifier.height(8.dp))
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TinaPrimaryButton(
                    text = stringResource(Strings.settings_ai_save),
                    onClick = {
                        // 保存工具状态
                        val states = toolStates.toMap()
                        aiSettingsBridge.applyToolEnabledStates(states)
                        aiSettingsBridge.persistToolEnabledStates(states)
                        activeDialog = AiSettingsDialog.None
                    }
                )
            },
            dismissButton = {
                TinaTextButton(
                    text = stringResource(Strings.ai_close),
                    onClick = { activeDialog = AiSettingsDialog.None }
                )
            }
        )
    }
}
