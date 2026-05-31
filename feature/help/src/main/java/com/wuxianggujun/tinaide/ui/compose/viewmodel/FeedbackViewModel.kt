package com.wuxianggujun.tinaide.ui.compose.viewmodel

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.wuxianggujun.tinaide.data.model.FeedbackCategory
import com.wuxianggujun.tinaide.data.repository.FeedbackRepository
import com.wuxianggujun.tinaide.data.repository.FeedbackResult
import com.wuxianggujun.tinaide.core.i18n.Strings
import com.wuxianggujun.tinaide.core.i18n.strOr
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import timber.log.Timber

class FeedbackViewModel(
    private val context: Context,
    private val feedbackRepository: FeedbackRepository
) : ViewModel() {
    
    private val _uiState = MutableStateFlow(FeedbackUiState())
    val uiState: StateFlow<FeedbackUiState> = _uiState.asStateFlow()
    
    // 防止重复提交：记录上次提交时间
    private var lastSubmitTime: Long = 0
    private val minSubmitInterval = 3000L // 3秒内不能重复提交
    
    fun selectCategory(category: FeedbackCategory) {
        _uiState.update { it.copy(category = category) }
    }
    
    fun updateTitle(title: String) {
        if (title.length <= MAX_TITLE_LENGTH) {
            _uiState.update { 
                it.copy(
                    title = title,
                    titleError = null
                )
            }
        }
    }
    
    fun updateContent(content: String) {
        if (content.length <= MAX_CONTENT_LENGTH) {
            _uiState.update { 
                it.copy(
                    content = content,
                    contentError = null
                )
            }
        }
    }
    
    fun submitFeedback() {
        val state = _uiState.value
        
        // 防止重复提交：检查提交间隔
        val currentTime = System.currentTimeMillis()
        if (currentTime - lastSubmitTime < minSubmitInterval) {
            val remainingSeconds = ((minSubmitInterval - (currentTime - lastSubmitTime)) / 1000) + 1
            _uiState.update {
                it.copy(
                    submitError = Strings.feedback_error_too_frequent.strOr(
                        context,
                        remainingSeconds
                    )
                )
            }
            return
        }
        
        // 验证
        val titleError = validateTitle(state.title)
        val contentError = validateContent(state.content)
        
        if (titleError != null || contentError != null) {
            _uiState.update {
                it.copy(
                    titleError = titleError,
                    contentError = contentError
                )
            }
            return
        }
        
        viewModelScope.launch {
            _uiState.update { it.copy(isSubmitting = true, submitError = null) }

            val result = feedbackRepository.submitFeedback(
                category = state.category.value,
                title = state.title.trim(),
                content = state.content.trim()
            )

            when (result) {
                is FeedbackResult.Success -> {
                    Timber.tag(TAG).d("Feedback submitted successfully: id=%s", result.response.id)

                    // 更新最后提交时间
                    lastSubmitTime = System.currentTimeMillis()

                    _uiState.update {
                        it.copy(
                            isSubmitting = false,
                            submitSuccess = true
                        )
                    }

                    // 提交成功后清空表单（延迟执行，让用户看到成功提示）
                    kotlinx.coroutines.delay(1500)
                    resetState()
                }
                is FeedbackResult.Error -> {
                    Timber.tag(TAG).e(
                        "Failed to submit feedback: code=%s, message=%s",
                        result.code,
                        result.message
                    )

                    _uiState.update {
                        it.copy(
                            isSubmitting = false,
                            submitError = result.message
                        )
                    }
                }
                is FeedbackResult.NetworkError -> {
                    Timber.tag(TAG).e("Network error while submitting feedback: %s", result.message)

                    _uiState.update {
                        it.copy(
                            isSubmitting = false,
                            submitError = Strings.feedback_error_network.strOr(context)
                        )
                    }
                }
            }
        }
    }
    
    fun resetState() {
        _uiState.value = FeedbackUiState()
    }
    
    fun dismissError() {
        _uiState.update { it.copy(submitError = null) }
    }
    
    private fun validateTitle(title: String): String? {
        val trimmed = title.trim()
        return when {
            trimmed.length < MIN_TITLE_LENGTH -> Strings.feedback_error_title_too_short.strOr(
                context,
                MIN_TITLE_LENGTH
            )
            trimmed.length > MAX_TITLE_LENGTH -> Strings.feedback_error_title_too_long.strOr(
                context,
                MAX_TITLE_LENGTH
            )
            else -> null
        }
    }
    
    private fun validateContent(content: String): String? {
        val trimmed = content.trim()
        return when {
            trimmed.length < MIN_CONTENT_LENGTH -> Strings.feedback_error_content_too_short.strOr(
                context,
                MIN_CONTENT_LENGTH
            )
            trimmed.length > MAX_CONTENT_LENGTH -> Strings.feedback_error_content_too_long.strOr(
                context,
                MAX_CONTENT_LENGTH
            )
            else -> null
        }
    }
    
    companion object {
        private const val TAG = "FeedbackViewModel"
        private const val MIN_TITLE_LENGTH = 5
        private const val MAX_TITLE_LENGTH = 100
        private const val MIN_CONTENT_LENGTH = 10
        private const val MAX_CONTENT_LENGTH = 5000
    }
    
}

data class FeedbackUiState(
    val category: FeedbackCategory = FeedbackCategory.BUG,
    val title: String = "",
    val content: String = "",
    val titleError: String? = null,
    val contentError: String? = null,
    val isSubmitting: Boolean = false,
    val submitSuccess: Boolean = false,
    val submitError: String? = null
) {
    val canSubmit: Boolean
        get() = title.trim().length >= 5 &&
                content.trim().length >= 10 &&
                titleError == null &&
                contentError == null &&
                !isSubmitting &&
                !submitSuccess // 提交成功后禁用按钮
}
