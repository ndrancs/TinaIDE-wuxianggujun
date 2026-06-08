package com.wuxianggujun.tinaide.tutorial.spotlight

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.wuxianggujun.tinaide.core.i18n.Strings
import com.wuxianggujun.tinaide.tutorial.data.HighlightShape
import com.wuxianggujun.tinaide.tutorial.data.TutorialStep
import com.wuxianggujun.tinaide.ui.compose.components.TinaDialogActionRow
import com.wuxianggujun.tinaide.ui.compose.components.TinaDialogCard
import com.wuxianggujun.tinaide.ui.compose.components.TinaOutlinedButton
import com.wuxianggujun.tinaide.ui.compose.components.TinaPrimaryButton
import com.wuxianggujun.tinaide.ui.compose.components.TinaTextButton

/**
 * Spotlight 遮罩引导组件
 *
 * 在屏幕上显示半透明遮罩，并高亮指定的目标区域
 */
@Composable
fun SpotlightOverlay(
    visible: Boolean,
    steps: List<TutorialStep>,
    currentStepIndex: Int,
    onNext: () -> Unit,
    onPrevious: () -> Unit,
    onSkip: () -> Unit,
    onComplete: () -> Unit,
    onRequestNavigateToTarget: (String) -> Unit = {},
    modifier: Modifier = Modifier,
    overlayColor: Color = Color.Black.copy(alpha = 0.7f),
    highlightPadding: Dp = 8.dp,
) {
    val registry = LocalSpotlightRegistry.current
    val density = LocalDensity.current

    val currentStep = steps.getOrNull(currentStepIndex)
    val targetInfo = currentStep?.let { registry.getTarget(it.targetId) }

    // 动画过渡
    var animatedBounds by remember { mutableStateOf<Rect?>(null) }
    val animationProgress by animateFloatAsState(
        targetValue = if (targetInfo != null) 1f else 0f,
        animationSpec = tween(300),
        label = "spotlight_animation"
    )

    LaunchedEffect(targetInfo) {
        animatedBounds = targetInfo?.bounds
    }

    AnimatedVisibility(
        visible = visible && currentStep != null,
        enter = fadeIn(animationSpec = tween(300)),
        exit = fadeOut(animationSpec = tween(300)),
        modifier = modifier
    ) {
        val step = currentStep ?: return@AnimatedVisibility
        Box(modifier = Modifier.fillMaxSize()) {
            // 目标已定位：绘制遮罩层（并阻止点击穿透）
            if (targetInfo != null) {
                Canvas(
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
                        .pointerInput(Unit) {
                            detectTapGestures { _ ->
                                // 阻止穿透：引导中避免误操作
                            }
                        }
                ) {
                    val paddingPx = with(density) { highlightPadding.toPx() }
                    val stepPaddingPx = step.highlightPadding.let {
                        with(density) { it.dp.toPx() }
                    }.takeIf { it > 0f } ?: paddingPx

                    drawOverlayWithHighlight(
                        overlayColor = overlayColor,
                        targetBounds = animatedBounds,
                        highlightShape = step.highlightShape,
                        padding = stepPaddingPx,
                        animationProgress = animationProgress
                    )
                }

                SpotlightTooltip(
                    step = step,
                    targetBounds = targetInfo.bounds,
                    currentIndex = currentStepIndex,
                    totalSteps = steps.size,
                    onNext = {
                        if (currentStepIndex < steps.size - 1) {
                            onNext()
                        } else {
                            onComplete()
                        }
                    },
                    onPrevious = onPrevious,
                    onSkip = onSkip,
                    highlightPadding = highlightPadding
                )
            } else {
                // 目标未定位：不画遮罩、不阻塞交互，避免“黑屏卡死”
                SpotlightFallbackTooltip(
                    step = step,
                    currentIndex = currentStepIndex,
                    totalSteps = steps.size,
                    onNext = onNext,
                    onPrevious = onPrevious,
                    onSkip = onSkip,
                    onComplete = onComplete,
                    onRequestNavigateToTarget = onRequestNavigateToTarget,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(16.dp)
                )
            }
        }
    }
}

@Composable
private fun SpotlightFallbackTooltip(
    step: TutorialStep,
    currentIndex: Int,
    totalSteps: Int,
    onNext: () -> Unit,
    onPrevious: () -> Unit,
    onSkip: () -> Unit,
    onComplete: () -> Unit,
    onRequestNavigateToTarget: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val isLastStep = currentIndex == totalSteps - 1

    SpotlightTooltipPanel(
        title = stringResource(step.titleRes),
        modifier = modifier.widthIn(max = 360.dp),
        onClose = onSkip,
        onCloseContentDescription = stringResource(Strings.spotlight_skip),
        footer = {
            TinaOutlinedButton(
                text = stringResource(Strings.spotlight_go_to),
                onClick = { onRequestNavigateToTarget(step.targetId) },
                modifier = Modifier.fillMaxWidth()
            )

            TinaDialogActionRow(
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                if (currentIndex > 0) {
                    TinaOutlinedButton(
                        text = stringResource(Strings.spotlight_previous),
                        onClick = onPrevious,
                        modifier = Modifier.weight(1f)
                    )
                } else {
                    TinaTextButton(
                        text = stringResource(Strings.spotlight_skip),
                        onClick = onSkip,
                        modifier = Modifier.weight(1f)
                    )
                }

                TinaPrimaryButton(
                    text = stringResource(
                        if (isLastStep) Strings.spotlight_finish else Strings.spotlight_next
                    ),
                    onClick = { if (isLastStep) onComplete() else onNext() },
                    modifier = Modifier.weight(1f)
                )
            }
        }
    ) {
        TinaDialogCard(
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.22f)
        ) {
            Text(
                text = stringResource(Strings.spotlight_target_not_found, step.targetId),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = stringResource(Strings.spotlight_navigate_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        TinaDialogCard {
            Text(
                text = androidx.compose.ui.res.stringResource(step.descriptionRes),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        SpotlightProgressCard(currentIndex = currentIndex, totalSteps = totalSteps)
    }
}

/**
 * 绘制带镂空高亮的遮罩
 */
private fun DrawScope.drawOverlayWithHighlight(
    overlayColor: Color,
    targetBounds: Rect?,
    highlightShape: HighlightShape,
    padding: Float,
    animationProgress: Float
) {
    // 绘制全屏遮罩
    drawRect(color = overlayColor)

    // 如果有目标，绘制镂空区域
    if (targetBounds != null && animationProgress > 0f) {
        val paddedBounds = Rect(
            left = targetBounds.left - padding,
            top = targetBounds.top - padding,
            right = targetBounds.right + padding,
            bottom = targetBounds.bottom + padding
        )

        // 使用 BlendMode.Clear 来创建镂空效果
        when (highlightShape) {
            HighlightShape.RECTANGLE -> {
                drawRect(
                    color = Color.Transparent,
                    topLeft = Offset(paddedBounds.left, paddedBounds.top),
                    size = Size(paddedBounds.width, paddedBounds.height),
                    blendMode = BlendMode.Clear
                )
            }

            HighlightShape.CIRCLE -> {
                val radius = maxOf(paddedBounds.width, paddedBounds.height) / 2
                drawCircle(
                    color = Color.Transparent,
                    radius = radius * animationProgress,
                    center = paddedBounds.center,
                    blendMode = BlendMode.Clear
                )
            }

            HighlightShape.ROUNDED_RECT -> {
                val cornerRadius = CornerRadius(12f, 12f)
                drawRoundRect(
                    color = Color.Transparent,
                    topLeft = Offset(paddedBounds.left, paddedBounds.top),
                    size = Size(paddedBounds.width, paddedBounds.height),
                    cornerRadius = cornerRadius,
                    blendMode = BlendMode.Clear
                )
            }
        }
    }
}
