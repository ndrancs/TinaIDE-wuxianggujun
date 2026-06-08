package com.wuxianggujun.tinaide.data.model

import androidx.annotation.StringRes
import com.wuxianggujun.tinaide.core.i18n.Strings

/**
 * FeedbackRequest, FeedbackDeviceInfo, FeedbackResponse 已移至 core:model
 * (com.wuxianggujun.tinaide.data.model.FeedbackModels.kt)
 */

enum class FeedbackCategory(
    val value: String,
    @param:StringRes @get:StringRes val labelRes: Int
) {
    BUG("bug", Strings.feedback_category_bug),
    FEATURE("feature", Strings.feedback_category_feature),
    IMPROVEMENT("improvement", Strings.feedback_category_improvement),
    QUESTION("question", Strings.feedback_category_question),
    OTHER("other", Strings.feedback_category_other);

    companion object {
        fun fromValue(value: String): FeedbackCategory = values().find { it.value == value } ?: OTHER
    }
}
