package com.wuxianggujun.tinaide.tutorial.data

import androidx.annotation.StringRes
import com.wuxianggujun.tinaide.core.i18n.Strings

/**
 * 教程数据模型
 */
data class Tutorial(
    val id: String,
    @param:StringRes @get:StringRes val titleRes: Int,
    @param:StringRes @get:StringRes val descriptionRes: Int,
    val category: TutorialCategory,
    val type: TutorialType,
    val source: TutorialSource = TutorialSource.LOCAL,
    val thumbnailRes: Int? = null,
    val estimatedMinutes: Int,
    val order: Int,
    val steps: List<TutorialStep> = emptyList(),
    val contentUrl: String? = null,
    val version: Int = 1,
    val requiredAppVersion: String? = null,
)

/**
 * 教程类型
 */
enum class TutorialType {
    /** 交互式引导 - 遮罩高亮步骤式教程 */
    INTERACTIVE,

    /** 图文教程 - Markdown 内容 */
    ARTICLE,

    /** 视频教程 */
    VIDEO
}

/**
 * 教程数据来源
 */
enum class TutorialSource {
    /** 本地内置 */
    LOCAL,

    /** 远程获取 */
    REMOTE
}

/**
 * 教程分类
 */
enum class TutorialCategory(
    @param:StringRes @get:StringRes val titleRes: Int,
    @param:StringRes @get:StringRes val descriptionRes: Int,
    val order: Int
) {
    GETTING_STARTED(
        Strings.tutorial_cat_getting_started,
        Strings.tutorial_cat_getting_started_desc,
        0
    ),
    EDITOR(
        Strings.tutorial_cat_editor,
        Strings.tutorial_cat_editor_desc,
        1
    ),
    BUILD(
        Strings.tutorial_cat_build,
        Strings.tutorial_cat_build_desc,
        2
    ),
    GIT(
        Strings.tutorial_cat_git,
        Strings.tutorial_cat_git_desc,
        3
    ),
    ADVANCED(
        Strings.tutorial_cat_advanced,
        Strings.tutorial_cat_advanced_desc,
        4
    );

    companion object {
        fun fromString(value: String): TutorialCategory? = entries.find { it.name.equals(value, ignoreCase = true) }
    }
}

/**
 * 交互式教程步骤
 */
data class TutorialStep(
    val id: String,
    /** 目标组件的语义 ID，如 "run_button", "project_tab" */
    val targetId: String,
    @param:StringRes @get:StringRes val titleRes: Int,
    @param:StringRes @get:StringRes val descriptionRes: Int,
    val position: TooltipPosition = TooltipPosition.AUTO,
    val action: StepAction = StepAction.NONE,
    val highlightShape: HighlightShape = HighlightShape.ROUNDED_RECT,
    /** 高亮区域的内边距 */
    val highlightPadding: Int = 8,
)

/**
 * 步骤需要用户执行的操作
 */
enum class StepAction {
    /** 点击目标 */
    CLICK,

    /** 长按 */
    LONG_PRESS,

    /** 滑动 */
    SWIPE,

    /** 输入文本 */
    INPUT,

    /** 仅查看，无需操作 */
    NONE
}

/**
 * 提示框位置
 */
enum class TooltipPosition {
    TOP,
    BOTTOM,
    LEFT,
    RIGHT,

    /** 自动计算最佳位置 */
    AUTO
}

/**
 * 高亮形状
 */
enum class HighlightShape {
    RECTANGLE,
    CIRCLE,
    ROUNDED_RECT
}
