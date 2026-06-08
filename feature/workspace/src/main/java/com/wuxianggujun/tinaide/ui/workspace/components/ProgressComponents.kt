package com.wuxianggujun.tinaide.ui.workspace.components

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.wuxianggujun.tinaide.core.i18n.Drawables
import com.wuxianggujun.tinaide.core.i18n.Strings
import com.wuxianggujun.tinaide.core.proot.PRootBootstrap

/**
 * 进度相关 Compose 组件
 *
 * 包含：
 * - CircularProgressWithIcon: 圆形进度指示器
 * - PackageInstallItem: 单个包安装项
 * - InstallStepItem: 安装步骤项
 */

/**
 * 圆形进度指示器带图标
 *
 * 优化版本：
 * - 使用渐变色背景圆环，更有层次感
 * - 添加微妙的脉冲动画效果
 * - 进度条使用渐变色，更有活力
 */
@Composable
fun CircularProgressWithIcon(
    progress: Float,
    isAnimating: Boolean,
    size: Dp = 80.dp,
    modifier: Modifier = Modifier
) {
    val infiniteTransition = rememberInfiniteTransition(label = "rotation")
    val rotation by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(2000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "rotation"
    )

    // 脉冲动画 - 轻微的呼吸效果
    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = 1.05f,
        animationSpec = infiniteRepeatable(
            animation = tween(1500, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulse"
    )

    // 根据尺寸动态计算其他尺寸
    val strokeWidth = (size.value * 0.075f).dp // 线宽为尺寸的 7.5%
    val innerCircleSize = size * 0.65f // 内圆为尺寸的 65%
    val iconSize = size * 0.35f // 图标为尺寸的 35%

    // 使用主题颜色
    val primaryColor = MaterialTheme.colorScheme.primary
    val primaryContainer = MaterialTheme.colorScheme.primaryContainer
    val surfaceVariant = MaterialTheme.colorScheme.surfaceVariant

    // 创建渐变色
    val trackGradient = Brush.sweepGradient(
        colors = listOf(
            primaryContainer.copy(alpha = 0.3f),
            primaryContainer.copy(alpha = 0.5f),
            primaryContainer.copy(alpha = 0.3f),
            primaryContainer.copy(alpha = 0.5f)
        )
    )

    val progressGradient = Brush.sweepGradient(
        colors = listOf(
            primaryColor,
            primaryColor.copy(alpha = 0.8f),
            primaryColor
        )
    )

    Box(
        modifier = modifier.size(size),
        contentAlignment = Alignment.Center
    ) {
        // 外层光晕效果（仅在动画时显示）
        if (isAnimating) {
            Canvas(
                modifier = Modifier
                    .size(size * 1.15f)
                    .scale(pulseScale)
            ) {
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(
                            primaryColor.copy(alpha = 0.15f),
                            primaryColor.copy(alpha = 0.05f),
                            Color.Transparent
                        )
                    )
                )
            }
        }

        // 背景圆环 - 使用渐变色
        Canvas(modifier = Modifier.size(size)) {
            drawCircle(
                brush = trackGradient,
                style = Stroke(width = strokeWidth.toPx(), cap = StrokeCap.Round)
            )
        }

        // 进度圆环 - 使用渐变色
        Canvas(
            modifier = Modifier
                .size(size)
                .rotate(if (isAnimating) rotation else 0f)
        ) {
            drawArc(
                brush = progressGradient,
                startAngle = -90f,
                sweepAngle = 360f * progress,
                useCenter = false,
                style = Stroke(width = strokeWidth.toPx(), cap = StrokeCap.Round)
            )
        }

        // 中心图标背景 - 使用渐变
        Box(
            modifier = Modifier
                .size(innerCircleSize)
                .clip(CircleShape)
                .background(
                    brush = Brush.radialGradient(
                        colors = listOf(
                            primaryColor.copy(alpha = 0.25f),
                            primaryColor.copy(alpha = 0.15f)
                        )
                    )
                ),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                painter = rememberWorkspacePainter(Drawables.ic_download_circle),
                contentDescription = null,
                modifier = Modifier
                    .size(iconSize)
                    .scale(if (isAnimating) pulseScale else 1f),
                tint = primaryColor
            )
        }
    }
}

/**
 * 单个包安装项 - 进度条样式
 *
 * 使用背景色渐变来显示安装进度，颜色从左到右推进覆盖
 */
@Composable
fun PackageInstallItem(
    packageInfo: PRootBootstrap.PackageInfo,
    isCurrentPackage: Boolean,
    modifier: Modifier = Modifier
) {
    val isBusy = packageInfo.status == PRootBootstrap.PackageStatus.DOWNLOADING ||
        packageInfo.status == PRootBootstrap.PackageStatus.INSTALLING

    // 只有在需要时才创建无限动画，避免“全量包名”列表过大导致卡顿。
    val animatedProgress = if (isBusy) {
        val infiniteTransition = rememberInfiniteTransition(label = "progressAnimation")
        val p by infiniteTransition.animateFloat(
            initialValue = 0f,
            targetValue = 1.2f,
            animationSpec = infiniteRepeatable(
                animation = tween(2000, easing = LinearEasing),
                repeatMode = RepeatMode.Restart
            ),
            label = "flowProgress"
        )
        p
    } else {
        0f
    }

    val shimmerOffset = if (isBusy) {
        val infiniteTransition = rememberInfiniteTransition(label = "shimmerAnimation")
        val s by infiniteTransition.animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(1500, easing = LinearEasing),
                repeatMode = RepeatMode.Restart
            ),
            label = "shimmer"
        )
        s
    } else {
        0f
    }

    // 根据状态确定进度和颜色 - 使用主题颜色
    val primaryColor = MaterialTheme.colorScheme.primary
    val errorColor = MaterialTheme.colorScheme.error
    val surfaceVariantColor = MaterialTheme.colorScheme.surfaceVariant

    val (displayProgress, progressColor, backgroundColor) = when (packageInfo.status) {
        PRootBootstrap.PackageStatus.COMPLETED -> Triple(
            1f,
            primaryColor.copy(alpha = 0.25f), // 主题色，完成
            primaryColor.copy(alpha = 0.08f)
        )
        PRootBootstrap.PackageStatus.DOWNLOADING -> Triple(
            animatedProgress.coerceIn(0f, 1f), // 动态进度
            primaryColor.copy(alpha = 0.3f), // 主题色，下载中
            primaryColor.copy(alpha = 0.08f)
        )
        PRootBootstrap.PackageStatus.INSTALLING -> Triple(
            animatedProgress.coerceIn(0f, 1f), // 动态进度
            primaryColor.copy(alpha = 0.3f), // 主题色，安装中
            primaryColor.copy(alpha = 0.08f)
        )
        PRootBootstrap.PackageStatus.FAILED -> Triple(
            1f,
            errorColor.copy(alpha = 0.2f), // 错误色，失败
            errorColor.copy(alpha = 0.08f)
        )
        PRootBootstrap.PackageStatus.PENDING -> Triple(
            0f,
            Color.Transparent,
            surfaceVariantColor.copy(alpha = 0.3f)
        )
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(64.dp)
            .clip(RoundedCornerShape(16.dp))
    ) {
        // 背景层
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(backgroundColor)
        )

        // 进度条层 - 从左到右推进的颜色覆盖
        if (displayProgress > 0f) {
            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .fillMaxWidth(displayProgress)
                    .background(
                        brush = Brush.horizontalGradient(
                            colors = listOf(
                                progressColor,
                                progressColor.copy(alpha = progressColor.alpha * 0.7f)
                            )
                        )
                    )
            )

            // 光晕效果（仅用于正在处理的状态）
            if (isBusy) {
                Box(
                    modifier = Modifier
                        .fillMaxHeight()
                        .width(60.dp)
                        .offset(x = (displayProgress * 300 * shimmerOffset).dp - 60.dp)
                        .background(
                            brush = Brush.horizontalGradient(
                                colors = listOf(
                                    Color.White.copy(alpha = 0f),
                                    Color.White.copy(alpha = 0.15f),
                                    Color.White.copy(alpha = 0f)
                                )
                            )
                        )
                )
            }
        }

        // 内容层（包名、图标、状态）
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 状态图标（28dp，更突出）
            Box(
                modifier = Modifier
                    .size(28.dp)
                    .clip(CircleShape)
                    .background(
                        when (packageInfo.status) {
                            PRootBootstrap.PackageStatus.COMPLETED -> primaryColor
                            PRootBootstrap.PackageStatus.INSTALLING -> primaryColor
                            PRootBootstrap.PackageStatus.DOWNLOADING -> primaryColor
                            PRootBootstrap.PackageStatus.FAILED -> errorColor
                            PRootBootstrap.PackageStatus.PENDING -> surfaceVariantColor
                        }
                    ),
                contentAlignment = Alignment.Center
            ) {
                when (packageInfo.status) {
                    PRootBootstrap.PackageStatus.COMPLETED -> {
                        Icon(
                            painter = rememberWorkspacePainter(Drawables.ic_check),
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                            tint = Color.White
                        )
                    }
                    PRootBootstrap.PackageStatus.INSTALLING, PRootBootstrap.PackageStatus.DOWNLOADING -> {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp,
                            color = Color.White
                        )
                    }
                    PRootBootstrap.PackageStatus.FAILED -> {
                        Icon(
                            painter = rememberWorkspacePainter(Drawables.ic_close),
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                            tint = Color.White
                        )
                    }
                    PRootBootstrap.PackageStatus.PENDING -> {
                        // 空的圆点
                    }
                }
            }

            // 包名和描述
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = packageInfo.name,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = if (isCurrentPackage) FontWeight.SemiBold else FontWeight.Medium,
                    color = if (packageInfo.status == PRootBootstrap.PackageStatus.PENDING) {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    }
                )
                Text(
                    text = packageInfo.displayName,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            // 状态标签（带背景）
            val statusText = when (packageInfo.status) {
                PRootBootstrap.PackageStatus.COMPLETED -> stringResource(Strings.package_status_completed)
                PRootBootstrap.PackageStatus.INSTALLING -> stringResource(Strings.package_status_installing)
                PRootBootstrap.PackageStatus.DOWNLOADING -> stringResource(Strings.package_status_downloading)
                PRootBootstrap.PackageStatus.FAILED -> stringResource(Strings.package_status_failed)
                PRootBootstrap.PackageStatus.PENDING -> stringResource(Strings.package_status_pending)
            }

            Surface(
                shape = RoundedCornerShape(12.dp),
                color = when (packageInfo.status) {
                    PRootBootstrap.PackageStatus.COMPLETED -> primaryColor.copy(alpha = 0.15f)
                    PRootBootstrap.PackageStatus.INSTALLING, PRootBootstrap.PackageStatus.DOWNLOADING ->
                        MaterialTheme.colorScheme.primaryContainer
                    PRootBootstrap.PackageStatus.FAILED -> errorColor.copy(alpha = 0.15f)
                    PRootBootstrap.PackageStatus.PENDING -> surfaceVariantColor
                }
            ) {
                Text(
                    text = statusText,
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Medium,
                    color = when (packageInfo.status) {
                        PRootBootstrap.PackageStatus.COMPLETED -> primaryColor
                        PRootBootstrap.PackageStatus.INSTALLING, PRootBootstrap.PackageStatus.DOWNLOADING ->
                            MaterialTheme.colorScheme.primary
                        PRootBootstrap.PackageStatus.FAILED -> errorColor
                        PRootBootstrap.PackageStatus.PENDING -> MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                )
            }
        }
    }
}

/**
 * 安装步骤项
 */
@Composable
fun InstallStepItem(
    step: Int,
    title: String,
    description: String,
    isCompleted: Boolean,
    isActive: Boolean,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // 步骤图标
        Box(
            modifier = Modifier
                .size(32.dp)
                .clip(CircleShape)
                .background(
                    when {
                        isCompleted -> MaterialTheme.colorScheme.primary
                        isActive -> MaterialTheme.colorScheme.primary
                        else -> MaterialTheme.colorScheme.surfaceVariant
                    }
                ),
            contentAlignment = Alignment.Center
        ) {
            if (isCompleted) {
                Icon(
                    painter = rememberWorkspacePainter(Drawables.ic_check),
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.onPrimary
                )
            } else {
                Text(
                    text = step.toString(),
                    style = MaterialTheme.typography.labelMedium,
                    color = if (isActive) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = if (isActive) FontWeight.Medium else FontWeight.Normal,
                color = if (isActive || isCompleted) {
                    MaterialTheme.colorScheme.onSurface
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                }
            )
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        // 状态指示
        if (isActive) {
            CircularProgressIndicator(
                modifier = Modifier.size(20.dp),
                strokeWidth = 2.dp
            )
        }
    }
}
