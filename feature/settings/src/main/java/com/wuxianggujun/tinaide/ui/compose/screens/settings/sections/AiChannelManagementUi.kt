package com.wuxianggujun.tinaide.ui.compose.screens.settings.sections

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.wuxianggujun.tinaide.core.config.ai.AiChannelConfig
import com.wuxianggujun.tinaide.core.config.ai.AiProvider
import com.wuxianggujun.tinaide.core.i18n.Strings
import com.wuxianggujun.tinaide.ui.compose.components.TinaAlertDialog
import com.wuxianggujun.tinaide.ui.compose.components.TinaDialogContentColumn
import com.wuxianggujun.tinaide.ui.compose.components.TinaDialogMessageCard
import com.wuxianggujun.tinaide.ui.compose.components.TinaDialogTitleText
import com.wuxianggujun.tinaide.ui.compose.components.TinaPrimaryButton
import com.wuxianggujun.tinaide.ui.compose.components.TinaSingleChoiceDialog
import com.wuxianggujun.tinaide.ui.compose.components.TinaTextButton

/**
 * 渠道管理对话框：列出所有 BYOK 渠道，提供添加/编辑/删除/激活操作。
 *
 * 开源版不再做会员门禁，所有 BYOK 渠道按本地配置直接管理。
 */
@Composable
internal fun AiChannelManagementDialog(
    channels: List<AiChannelConfig>,
    activeChannelId: String?,
    onDismiss: () -> Unit,
    onAdd: () -> Unit,
    onEdit: (AiChannelConfig) -> Unit,
    onSetActive: (AiChannelConfig) -> Unit,
    onDelete: (AiChannelConfig) -> Unit,
) {
    var pendingDelete by remember { mutableStateOf<AiChannelConfig?>(null) }

    TinaAlertDialog(
        onDismissRequest = onDismiss,
        title = { TinaDialogTitleText(stringResource(Strings.settings_ai_channel_management)) },
        text = {
            TinaDialogContentColumn {
                if (channels.isEmpty()) {
                    TinaDialogMessageCard(
                        message = stringResource(Strings.settings_ai_channel_list_empty)
                    )
                } else {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(320.dp)
                    ) {
                        items(items = channels, key = { it.id }) { channel ->
                            val active = channel.id == activeChannelId
                            Surface(
                                onClick = { onSetActive(channel) },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 4.dp),
                                shape = RoundedCornerShape(8.dp),
                                color = if (active) {
                                    MaterialTheme.colorScheme.primaryContainer
                                } else {
                                    MaterialTheme.colorScheme.surface
                                },
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 12.dp, vertical = 10.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    if (active) {
                                        Icon(
                                            imageVector = Icons.Outlined.CheckCircle,
                                            contentDescription = stringResource(Strings.settings_ai_channel_active),
                                            tint = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.size(20.dp),
                                        )
                                        Spacer(modifier = Modifier.size(8.dp))
                                    }
                                    Column(modifier = Modifier.weight(1f)) {
                                        val providerName = stringResource(
                                            AiSettingsSectionSupport.resolveProviderDisplayNameRes(channel.provider)
                                        )
                                        Text(
                                            text = channel.name,
                                            style = MaterialTheme.typography.titleSmall,
                                            fontWeight = FontWeight.Medium,
                                        )
                                        Spacer(modifier = Modifier.height(2.dp))
                                        Text(
                                            text = stringResource(
                                                Strings.settings_ai_channel_provider_model,
                                                providerName,
                                                channel.model,
                                            ),
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                        Text(
                                            text = channel.baseUrl,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            maxLines = 1,
                                        )
                                    }
                                    IconButton(
                                        onClick = { onEdit(channel) },
                                    ) {
                                        Icon(
                                            imageVector = Icons.Outlined.Edit,
                                            contentDescription = stringResource(Strings.settings_ai_channel_edit),
                                        )
                                    }
                                    IconButton(
                                        onClick = { pendingDelete = channel },
                                    ) {
                                        Icon(
                                            imageVector = Icons.Outlined.Delete,
                                            contentDescription = stringResource(Strings.settings_ai_channel_delete),
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))
                FilledTonalButton(
                    onClick = onAdd,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(imageVector = Icons.Outlined.Add, contentDescription = null)
                    Spacer(modifier = Modifier.size(4.dp))
                    Text(text = stringResource(Strings.settings_ai_channel_add))
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TinaTextButton(
                text = stringResource(Strings.ai_close),
                onClick = onDismiss,
            )
        },
    )

    pendingDelete?.let { target ->
        val context = LocalContext.current
        TinaAlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { TinaDialogTitleText(stringResource(Strings.settings_ai_channel_delete)) },
            text = {
                TinaDialogContentColumn {
                    TinaDialogMessageCard(
                        message = context.getString(Strings.settings_ai_channel_delete_confirm, target.name)
                    )
                }
            },
            confirmButton = {
                TinaPrimaryButton(
                    text = stringResource(Strings.settings_ai_channel_delete),
                    onClick = {
                        onDelete(target)
                        pendingDelete = null
                    },
                )
            },
            dismissButton = {
                TinaTextButton(
                    text = stringResource(Strings.ai_close),
                    onClick = { pendingDelete = null },
                )
            },
        )
    }
}

/**
 * 渠道新增/编辑对话框。
 *
 * [initial] 为 null 表示"新增"，此时 apiKey 必填；否则为"编辑"，
 * apiKey 留空表示不修改现有 key。
 */
@Composable
internal fun AiChannelEditDialog(
    initial: AiChannelConfig?,
    onDismiss: () -> Unit,
    onSave: (name: String, provider: AiProvider, baseUrl: String, model: String, apiKey: String) -> Unit,
) {
    val context = LocalContext.current
    val isEdit = initial != null

    var name by remember { mutableStateOf(initial?.name ?: "") }
    var provider by remember { mutableStateOf(initial?.provider ?: AiProvider.OPENAI) }
    var baseUrl by remember { mutableStateOf(initial?.baseUrl ?: provider.defaultBaseUrl) }
    var model by remember { mutableStateOf(initial?.model ?: provider.defaultModels.firstOrNull().orEmpty()) }
    var apiKey by remember { mutableStateOf("") }
    var showKey by remember { mutableStateOf(false) }
    var showProviderPicker by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    TinaAlertDialog(
        onDismissRequest = onDismiss,
        title = {
            TinaDialogTitleText(
                stringResource(
                    if (isEdit) Strings.settings_ai_channel_edit else Strings.settings_ai_channel_add
                )
            )
        },
        text = {
            TinaDialogContentColumn {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(Strings.settings_ai_channel_name)) },
                    placeholder = { Text(stringResource(Strings.settings_ai_channel_name_hint)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(modifier = Modifier.height(8.dp))

                Surface(
                    onClick = { showProviderPicker = true },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant,
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Column {
                            Text(
                                text = stringResource(Strings.settings_ai_provider),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Text(
                                text = stringResource(
                                    AiSettingsSectionSupport.resolveProviderDisplayNameRes(provider)
                                ),
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                    }
                }
                Spacer(modifier = Modifier.height(8.dp))

                OutlinedTextField(
                    value = baseUrl,
                    onValueChange = { baseUrl = it },
                    label = { Text(stringResource(Strings.settings_ai_base_url)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(modifier = Modifier.height(8.dp))

                OutlinedTextField(
                    value = model,
                    onValueChange = { model = it },
                    label = { Text(stringResource(Strings.settings_ai_model)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(modifier = Modifier.height(8.dp))

                OutlinedTextField(
                    value = apiKey,
                    onValueChange = { apiKey = it },
                    label = { Text(stringResource(Strings.settings_ai_api_key)) },
                    placeholder = {
                        Text(
                            text = if (isEdit) {
                                stringResource(Strings.settings_ai_channel_apikey_keep)
                            } else {
                                stringResource(Strings.settings_ai_channel_apikey_required)
                            },
                            style = MaterialTheme.typography.bodySmall,
                        )
                    },
                    singleLine = true,
                    visualTransformation = if (showKey) VisualTransformation.None else PasswordVisualTransformation(),
                    trailingIcon = {
                        IconButton(onClick = { showKey = !showKey }) {
                            Icon(
                                imageVector = if (showKey) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,
                                contentDescription = null,
                            )
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                )

                errorMessage?.let {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = it,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        },
        confirmButton = {
            TinaPrimaryButton(
                text = stringResource(Strings.settings_ai_save),
                onClick = {
                    val validation = AiSettingsSectionSupport.validateChannelInput(
                        name = name,
                        baseUrl = baseUrl,
                        model = model,
                        apiKey = apiKey,
                        apiKeyRequired = !isEdit,
                    )
                    val errorRes = when (validation) {
                        AiChannelInputValidation.Valid -> null
                        AiChannelInputValidation.NameBlank -> Strings.settings_ai_channel_error_name_blank
                        AiChannelInputValidation.BaseUrlBlank -> Strings.settings_ai_channel_error_url_blank
                        AiChannelInputValidation.BaseUrlInvalid -> Strings.settings_ai_channel_error_url_invalid
                        AiChannelInputValidation.ModelBlank -> Strings.settings_ai_channel_error_model_blank
                        AiChannelInputValidation.ApiKeyBlank -> Strings.settings_ai_channel_error_apikey_blank
                    }
                    if (errorRes != null) {
                        errorMessage = context.getString(errorRes)
                        return@TinaPrimaryButton
                    }
                    onSave(
                        name.trim(),
                        provider,
                        baseUrl.trim(),
                        model.trim(),
                        AiSettingsSectionSupport.sanitizeApiKey(apiKey),
                    )
                },
            )
        },
        dismissButton = {
            TinaTextButton(
                text = stringResource(Strings.ai_close),
                onClick = onDismiss,
            )
        },
    )

    if (showProviderPicker) {
        val providerOptions = AiSettingsSectionSupport.buildProviderOptions().map { option ->
            option.value to stringResource(option.labelRes)
        }
        TinaSingleChoiceDialog(
            title = stringResource(Strings.settings_ai_select_provider),
            options = providerOptions,
            selectedValue = provider.name,
            onSelected = { selected ->
                val picked = AiProvider.entries.firstOrNull { it.name == selected } ?: provider
                provider = picked
                // 切换 provider 时，若 baseUrl/model 仍是上一 provider 的默认值，则同步更新。
                // 这里简单地覆盖——用户可在字段内继续编辑。
                if (baseUrl.isBlank() || AiProvider.entries.any { it.defaultBaseUrl == baseUrl }) {
                    baseUrl = picked.defaultBaseUrl
                }
                if (model.isBlank() || AiProvider.entries.any { provider -> provider.defaultModels.contains(model) }) {
                    model = picked.defaultModels.firstOrNull().orEmpty()
                }
                showProviderPicker = false
            },
            onDismiss = { showProviderPicker = false },
        )
    }
}
