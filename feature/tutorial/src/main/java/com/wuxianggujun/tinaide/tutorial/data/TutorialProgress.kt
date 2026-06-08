package com.wuxianggujun.tinaide.tutorial.data

import kotlinx.serialization.Serializable

/**
 * 用户教程进度
 */
@Serializable
data class TutorialProgress(
    val tutorialId: String,
    val status: ProgressStatus,
    /** 当前步骤索引（仅交互式教程） */
    val currentStepIndex: Int = 0,
    /** 完成时间戳 */
    val completedAt: Long? = null,
    /** 最后访问时间戳 */
    val lastAccessedAt: Long = System.currentTimeMillis(),
)

/**
 * 进度状态
 */
@Serializable
enum class ProgressStatus {
    /** 未开始 */
    NOT_STARTED,

    /** 进行中 */
    IN_PROGRESS,

    /** 已完成 */
    COMPLETED
}

/**
 * 教程与进度的组合数据
 */
data class TutorialWithProgress(
    val tutorial: Tutorial,
    val progress: TutorialProgress?
) {
    val status: ProgressStatus
        get() = progress?.status ?: ProgressStatus.NOT_STARTED

    val progressPercent: Int
        get() {
            if (tutorial.steps.isEmpty()) return 0
            val currentStep = progress?.currentStepIndex ?: 0
            return if (status == ProgressStatus.COMPLETED) {
                100
            } else {
                (currentStep * 100) / tutorial.steps.size
            }
        }

    val isCompleted: Boolean
        get() = status == ProgressStatus.COMPLETED

    val isInProgress: Boolean
        get() = status == ProgressStatus.IN_PROGRESS
}
