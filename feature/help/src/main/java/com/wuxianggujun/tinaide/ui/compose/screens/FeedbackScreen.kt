package com.wuxianggujun.tinaide.ui.compose.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.wuxianggujun.tinaide.core.i18n.Strings
import com.wuxianggujun.tinaide.data.model.FeedbackCategory
import com.wuxianggujun.tinaide.ui.compose.components.TinaPrimaryButton
import com.wuxianggujun.tinaide.ui.compose.components.TinaSpacing
import com.wuxianggujun.tinaide.ui.compose.components.TinaTextField
import com.wuxianggujun.tinaide.ui.compose.components.TinaTopBar
import com.wuxianggujun.tinaide.ui.compose.viewmodel.FeedbackViewModel
import org.koin.androidx.compose.koinViewModel

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun FeedbackScreen(
    onNavigateBack: () -> Unit
) {
    val viewModel: FeedbackViewModel = koinViewModel()
    val uiState by viewModel.uiState.collectAsState()

    // 提交成功后返回
    LaunchedEffect(uiState.submitSuccess) {
        if (uiState.submitSuccess) {
            kotlinx.coroutines.delay(1500)
            onNavigateBack()
        }
    }

    Scaffold(
        topBar = {
            TinaTopBar(
                title = stringResource(Strings.feedback_title),
                onNavigateBack = onNavigateBack
            )
        },
        snackbarHost = {
            if (uiState.submitError != null) {
                Snackbar(
                    modifier = Modifier.padding(TinaSpacing.xl),
                    action = {
                        TextButton(onClick = viewModel::dismissError) {
                            Text(stringResource(Strings.dismiss))
                        }
                    }
                ) {
                    Text(uiState.submitError ?: "")
                }
            }
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(TinaSpacing.xl)
        ) {
            // 分类选择
            Text(
                text = stringResource(Strings.feedback_category_label),
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.padding(bottom = TinaSpacing.md)
            )

            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(TinaSpacing.md),
                verticalArrangement = Arrangement.spacedBy(TinaSpacing.md),
                modifier = Modifier.padding(bottom = TinaSpacing.xl)
            ) {
                FeedbackCategory.values().forEach { category ->
                    FilterChip(
                        selected = uiState.category == category,
                        onClick = { viewModel.selectCategory(category) },
                        label = { Text(stringResource(category.labelRes)) },
                        leadingIcon = if (uiState.category == category) {
                            {
                                Icon(
                                    Icons.Default.Check,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        } else {
                            null
                        }
                    )
                }
            }

            // 标题输入
            TinaTextField(
                value = uiState.title,
                onValueChange = viewModel::updateTitle,
                label = stringResource(Strings.feedback_title_label),
                placeholder = stringResource(Strings.feedback_title_hint),
                modifier = Modifier.fillMaxWidth(),
                maxLines = 2,
                supportingText = {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        val trimmedLength = uiState.title.trim().length
                        val remaining = 5 - trimmedLength
                        if (uiState.titleError != null) {
                            Text(
                                text = uiState.titleError ?: "",
                                color = MaterialTheme.colorScheme.error
                            )
                        } else if (remaining > 0) {
                            Text(
                                text = stringResource(Strings.feedback_min_chars_hint, remaining),
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        } else {
                            Spacer(modifier = Modifier.weight(1f))
                        }
                        Text("${uiState.title.length}/100")
                    }
                },
                isError = uiState.titleError != null
            )

            Spacer(modifier = Modifier.height(TinaSpacing.xl))

            // 内容输入
            TinaTextField(
                value = uiState.content,
                onValueChange = viewModel::updateContent,
                label = stringResource(Strings.feedback_content_label),
                placeholder = stringResource(Strings.feedback_content_hint),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(200.dp),
                maxLines = 10,
                supportingText = {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        val trimmedLength = uiState.content.trim().length
                        val remaining = 10 - trimmedLength
                        if (uiState.contentError != null) {
                            Text(
                                text = uiState.contentError ?: "",
                                color = MaterialTheme.colorScheme.error
                            )
                        } else if (remaining > 0) {
                            Text(
                                text = stringResource(Strings.feedback_min_chars_hint, remaining),
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        } else {
                            Spacer(modifier = Modifier.weight(1f))
                        }
                        Text("${uiState.content.length}/5000")
                    }
                },
                isError = uiState.contentError != null
            )

            Spacer(modifier = Modifier.height(TinaSpacing.xxxl))

            // 提交按钮
            TinaPrimaryButton(
                text = if (uiState.submitSuccess) {
                    stringResource(Strings.feedback_submit_success)
                } else {
                    stringResource(Strings.feedback_submit)
                },
                onClick = viewModel::submitFeedback,
                enabled = uiState.canSubmit,
                modifier = Modifier.fillMaxWidth()
            )

            // 提示信息
            if (!uiState.isSubmitting && !uiState.submitSuccess) {
                Text(
                    text = stringResource(Strings.feedback_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 16.dp)
                )
            }
        }
    }
}
