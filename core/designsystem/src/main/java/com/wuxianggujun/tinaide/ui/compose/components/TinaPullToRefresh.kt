package com.wuxianggujun.tinaide.ui.compose.components

import androidx.compose.foundation.layout.BoxScope
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.PullToRefreshState
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback

/**
 * 带触觉反馈的下拉刷新组件
 *
 * 相比原生 PullToRefreshBox 的增强:
 * - 下拉到阈值时触发轻微震动反馈
 * - 释放触发刷新时触发确认震动
 * - 刷新完成时触发成功震动
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TinaPullToRefreshBox(
    isRefreshing: Boolean,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier,
    state: PullToRefreshState = rememberPullToRefreshState(),
    enableHapticFeedback: Boolean = true,
    content: @Composable BoxScope.() -> Unit
) {
    val haptic = LocalHapticFeedback.current

    // 跟踪上一次的刷新状态,用于检测刷新完成
    var wasRefreshing by remember { mutableStateOf(isRefreshing) }

    // 跟踪是否已经触发过阈值震动
    var hasTriggeredThresholdHaptic by remember { mutableStateOf(false) }

    // 标记本次刷新是否由用户手势触发（distanceFraction > 0 表示用户正在下拉）
    var isUserInitiatedRefresh by remember { mutableStateOf(false) }

    // 监听下拉进度,在达到阈值时触发震动
    LaunchedEffect(state.distanceFraction) {
        if (enableHapticFeedback) {
            // 当下拉进度达到 80% 且未触发过震动时
            if (state.distanceFraction >= 0.8f && !hasTriggeredThresholdHaptic) {
                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                hasTriggeredThresholdHaptic = true
            }
            // 当下拉进度低于 50% 时重置震动标记
            else if (state.distanceFraction < 0.5f) {
                hasTriggeredThresholdHaptic = false
            }
        }
        // 仅在非刷新状态下 distanceFraction > 0 才视为用户手势下拉
        // （刷新中 indicator 动画也会产生 distanceFraction > 0，不应视为用户触发）
        if (state.distanceFraction > 0f && !isRefreshing) {
            isUserInitiatedRefresh = true
        }
    }

    // 监听刷新状态变化（仅对用户手势触发的刷新执行震动反馈）
    LaunchedEffect(isRefreshing) {
        if (enableHapticFeedback && isUserInitiatedRefresh) {
            // 刚开始刷新时触发确认震动
            if (isRefreshing && !wasRefreshing) {
                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
            }
            // 刷新完成时触发成功震动，并重置标记
            else if (!isRefreshing && wasRefreshing) {
                haptic.performHapticFeedback(HapticFeedbackType.Confirm)
                isUserInitiatedRefresh = false
            }
        }
        wasRefreshing = isRefreshing
    }

    PullToRefreshBox(
        isRefreshing = isRefreshing,
        onRefresh = onRefresh,
        modifier = modifier,
        state = state,
        content = content
    )
}

/**
 * 简化版 - 不需要自定义 state 的场景
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TinaPullToRefreshBox(
    isRefreshing: Boolean,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier,
    enableHapticFeedback: Boolean = true,
    content: @Composable BoxScope.() -> Unit
) {
    val state = rememberPullToRefreshState()
    TinaPullToRefreshBox(
        isRefreshing = isRefreshing,
        onRefresh = onRefresh,
        modifier = modifier,
        state = state,
        enableHapticFeedback = enableHapticFeedback,
        content = content
    )
}
