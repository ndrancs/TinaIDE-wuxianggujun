package com.wuxianggujun.tinaide.ui.theme

import com.wuxianggujun.tinaide.core.config.AppTheme
import com.wuxianggujun.tinaide.core.config.ThemeManager
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * TinaIDE Compose 主题入口（重构版）
 *
 * 核心改进：
 * - 使用 ThemeManager.themeFlow 实现响应式主题
 * - 移除 colorResource() 依赖，使用硬编码颜色值
 * - 主题切换时自动触发 Compose 重组
 *
 * 使用方式：
 * ```kotlin
 * setContent {
 *     TinaIDETheme {
 *         // 你的 UI 内容
 *     }
 * }
 * ```
 */
@Composable
fun TinaIDETheme(
    content: @Composable () -> Unit
) {
    // 订阅 ThemeManager 的主题状态（响应式）
    val theme by ThemeManager.themeFlow.collectAsState()
    val isSystemDark = isSystemInDarkTheme()

    // 根据主题状态选择颜色方案
    val colorScheme = when (theme) {
        AppTheme.LIGHT -> LightColors.colorScheme()
        AppTheme.DARK -> DarkColors.colorScheme()
        AppTheme.GRAY -> GrayColors.colorScheme()
        AppTheme.AUTO -> if (isSystemDark) DarkColors.colorScheme() else LightColors.colorScheme()
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = TinaIDETypography,
        shapes = TinaIDEShapes,
        content = content
    )
}

/**
 * 浅色主题颜色常量
 * 对应 res/values/colors.xml
 */
private object LightColors {
    val primary = Color(0xFF006B61)
    val onPrimary = Color(0xFFFFFFFF)
    val primaryContainer = Color(0xFF6FF7E2)
    val onPrimaryContainer = Color(0xFF00201C)
    val inversePrimary = Color(0xFF03DAC6)

    val secondary = Color(0xFF4A635E)
    val onSecondary = Color(0xFFFFFFFF)
    val secondaryContainer = Color(0xFFCCE8E2)
    val onSecondaryContainer = Color(0xFF06201C)

    val tertiary = Color(0xFF446274)
    val onTertiary = Color(0xFFFFFFFF)
    val tertiaryContainer = Color(0xFFC6E7FB)
    val onTertiaryContainer = Color(0xFF001E2B)

    val error = Color(0xFFBA1A1A)
    val onError = Color(0xFFFFFFFF)
    val errorContainer = Color(0xFFFFDAD6)
    val onErrorContainer = Color(0xFF410002)

    val background = Color(0xFFFAFDFB)
    val onBackground = Color(0xFF191C1B)

    val surface = Color(0xFFFAFDFB)
    val onSurface = Color(0xFF191C1B)
    val surfaceVariant = Color(0xFFDBE5E1)
    val onSurfaceVariant = Color(0xFF3F4946)

    val outline = Color(0xFF6F7976)
    val outlineVariant = Color(0xFFBFC9C5)

    val scrim = Color(0xFF000000)
    val inverseSurface = Color(0xFF2E3130)
    val inverseOnSurface = Color(0xFFEFF1EF)

    val surfaceDim = Color(0xFFDAD9D7)
    val surfaceBright = Color(0xFFFAFDFB)
    val surfaceContainerLowest = Color(0xFFFFFFFF)
    val surfaceContainerLow = Color(0xFFF4F6F4)
    val surfaceContainer = Color(0xFFEEEFED)
    val surfaceContainerHigh = Color(0xFFE8EAE8)
    val surfaceContainerHighest = Color(0xFFE2E4E2)

    fun colorScheme(): ColorScheme = lightColorScheme(
        primary = primary,
        onPrimary = onPrimary,
        primaryContainer = primaryContainer,
        onPrimaryContainer = onPrimaryContainer,
        inversePrimary = inversePrimary,
        secondary = secondary,
        onSecondary = onSecondary,
        secondaryContainer = secondaryContainer,
        onSecondaryContainer = onSecondaryContainer,
        tertiary = tertiary,
        onTertiary = onTertiary,
        tertiaryContainer = tertiaryContainer,
        onTertiaryContainer = onTertiaryContainer,
        error = error,
        onError = onError,
        errorContainer = errorContainer,
        onErrorContainer = onErrorContainer,
        background = background,
        onBackground = onBackground,
        surface = surface,
        onSurface = onSurface,
        surfaceVariant = surfaceVariant,
        onSurfaceVariant = onSurfaceVariant,
        outline = outline,
        outlineVariant = outlineVariant,
        scrim = scrim,
        inverseSurface = inverseSurface,
        inverseOnSurface = inverseOnSurface,
        surfaceDim = surfaceDim,
        surfaceBright = surfaceBright,
        surfaceContainer = surfaceContainer,
        surfaceContainerHigh = surfaceContainerHigh,
        surfaceContainerHighest = surfaceContainerHighest,
        surfaceContainerLow = surfaceContainerLow,
        surfaceContainerLowest = surfaceContainerLowest,
    )
}

/**
 * 深色主题颜色常量（纯黑背景 #121212）
 * 对应 res/values-night/colors.xml
 */
private object DarkColors {
    val primary = Color(0xFF03DAC6)
    val onPrimary = Color(0xFF003731)
    val primaryContainer = Color(0xFF00504A)
    val onPrimaryContainer = Color(0xFF6FF7E2)
    val inversePrimary = Color(0xFF006B61)

    val secondary = Color(0xFFB1CCC6)
    val onSecondary = Color(0xFF1C3531)
    val secondaryContainer = Color(0xFF334B47)
    val onSecondaryContainer = Color(0xFFCCE8E2)

    val tertiary = Color(0xFFAACBDF)
    val onTertiary = Color(0xFF103445)
    val tertiaryContainer = Color(0xFF2A4A5C)
    val onTertiaryContainer = Color(0xFFC6E7FB)

    val error = Color(0xFFFFB4AB)
    val onError = Color(0xFF690005)
    val errorContainer = Color(0xFF93000A)
    val onErrorContainer = Color(0xFFFFDAD6)

    val background = Color(0xFF121212) // 深色主题：纯黑背景
    val onBackground = Color(0xFFE1E3E0)

    val surface = Color(0xFF121212)
    val onSurface = Color(0xFFE1E3E0)
    val surfaceVariant = Color(0xFF3F4946)
    val onSurfaceVariant = Color(0xFFBFC9C5)

    val outline = Color(0xFF89938F)
    val outlineVariant = Color(0xFF3F4946)

    val scrim = Color(0xFF000000)
    val inverseSurface = Color(0xFFE1E3E0)
    val inverseOnSurface = Color(0xFF2E3130)

    val surfaceDim = Color(0xFF121212)
    val surfaceBright = Color(0xFF373A38)
    val surfaceContainerLowest = Color(0xFF0B0F0E)
    val surfaceContainerLow = Color(0xFF191C1B)
    val surfaceContainer = Color(0xFF1E1E1E)
    val surfaceContainerHigh = Color(0xFF282B29)
    val surfaceContainerHighest = Color(0xFF333634)

    fun colorScheme(): ColorScheme = darkColorScheme(
        primary = primary,
        onPrimary = onPrimary,
        primaryContainer = primaryContainer,
        onPrimaryContainer = onPrimaryContainer,
        inversePrimary = inversePrimary,
        secondary = secondary,
        onSecondary = onSecondary,
        secondaryContainer = secondaryContainer,
        onSecondaryContainer = onSecondaryContainer,
        tertiary = tertiary,
        onTertiary = onTertiary,
        tertiaryContainer = tertiaryContainer,
        onTertiaryContainer = onTertiaryContainer,
        error = error,
        onError = onError,
        errorContainer = errorContainer,
        onErrorContainer = onErrorContainer,
        background = background,
        onBackground = onBackground,
        surface = surface,
        onSurface = onSurface,
        surfaceVariant = surfaceVariant,
        onSurfaceVariant = onSurfaceVariant,
        outline = outline,
        outlineVariant = outlineVariant,
        scrim = scrim,
        inverseSurface = inverseSurface,
        inverseOnSurface = inverseOnSurface,
        surfaceDim = surfaceDim,
        surfaceBright = surfaceBright,
        surfaceContainer = surfaceContainer,
        surfaceContainerHigh = surfaceContainerHigh,
        surfaceContainerHighest = surfaceContainerHighest,
        surfaceContainerLow = surfaceContainerLow,
        surfaceContainerLowest = surfaceContainerLowest,
    )
}

/**
 * 灰色主题颜色常量（深灰背景 #2D2D2D，减少视觉疲劳）
 * 灰色主题颜色常量。
 */
private object GrayColors {
    val primary = Color(0xFF03DAC6)
    val onPrimary = Color(0xFF003731)
    val primaryContainer = Color(0xFF00504A)
    val onPrimaryContainer = Color(0xFF6FF7E2)
    val inversePrimary = Color(0xFF006B61)

    val secondary = Color(0xFFB1CCC6)
    val onSecondary = Color(0xFF1C3531)
    val secondaryContainer = Color(0xFF3D4D4A)
    val onSecondaryContainer = Color(0xFFCCE8E2)

    val tertiary = Color(0xFFAACBDF)
    val onTertiary = Color(0xFF103445)
    val tertiaryContainer = Color(0xFF324A5C)
    val onTertiaryContainer = Color(0xFFC6E7FB)

    val error = Color(0xFFFFB4AB)
    val onError = Color(0xFF690005)
    val errorContainer = Color(0xFF93000A)
    val onErrorContainer = Color(0xFFFFDAD6)

    val background = Color(0xFF2D2D2D) // 灰色主题：深灰背景
    val onBackground = Color(0xFFE1E3E0)

    val surface = Color(0xFF2D2D2D)
    val onSurface = Color(0xFFE1E3E0)
    val surfaceVariant = Color(0xFF484848)
    val onSurfaceVariant = Color(0xFFC0C0C0)

    val outline = Color(0xFF8A8A8A)
    val outlineVariant = Color(0xFF484848)

    val scrim = Color(0xFF000000)
    val inverseSurface = Color(0xFFE1E3E0)
    val inverseOnSurface = Color(0xFF2D2D2D)

    val surfaceDim = Color(0xFF252525)
    val surfaceBright = Color(0xFF424242)
    val surfaceContainerLowest = Color(0xFF1A1A1A)
    val surfaceContainerLow = Color(0xFF242424)
    val surfaceContainer = Color(0xFF2D2D2D)
    val surfaceContainerHigh = Color(0xFF383838)
    val surfaceContainerHighest = Color(0xFF424242)

    fun colorScheme(): ColorScheme = darkColorScheme(
        primary = primary,
        onPrimary = onPrimary,
        primaryContainer = primaryContainer,
        onPrimaryContainer = onPrimaryContainer,
        inversePrimary = inversePrimary,
        secondary = secondary,
        onSecondary = onSecondary,
        secondaryContainer = secondaryContainer,
        onSecondaryContainer = onSecondaryContainer,
        tertiary = tertiary,
        onTertiary = onTertiary,
        tertiaryContainer = tertiaryContainer,
        onTertiaryContainer = onTertiaryContainer,
        error = error,
        onError = onError,
        errorContainer = errorContainer,
        onErrorContainer = onErrorContainer,
        background = background,
        onBackground = onBackground,
        surface = surface,
        onSurface = onSurface,
        surfaceVariant = surfaceVariant,
        onSurfaceVariant = onSurfaceVariant,
        outline = outline,
        outlineVariant = outlineVariant,
        scrim = scrim,
        inverseSurface = inverseSurface,
        inverseOnSurface = inverseOnSurface,
        surfaceDim = surfaceDim,
        surfaceBright = surfaceBright,
        surfaceContainer = surfaceContainer,
        surfaceContainerHigh = surfaceContainerHigh,
        surfaceContainerHighest = surfaceContainerHighest,
        surfaceContainerLow = surfaceContainerLow,
        surfaceContainerLowest = surfaceContainerLowest,
    )
}

// ----- Typography & Shapes -----

/**
 * TinaIDE 自定义排版
 *
 * 基于 Material 3 默认排版，针对 IDE 场景做了以下调整：
 * - bodySmall/bodyMedium/bodyLarge: 适当调整行高，提升代码阅读体验
 * - labelSmall: 用于状态栏、标签等紧凑场景
 */
private val TinaIDETypography: Typography = Typography(
    displayLarge = TextStyle(
        fontWeight = FontWeight.Normal,
        fontSize = 57.sp,
        lineHeight = 64.sp,
        letterSpacing = (-0.25).sp
    ),
    headlineLarge = TextStyle(
        fontWeight = FontWeight.Normal,
        fontSize = 32.sp,
        lineHeight = 40.sp,
        letterSpacing = 0.sp
    ),
    headlineMedium = TextStyle(
        fontWeight = FontWeight.Normal,
        fontSize = 28.sp,
        lineHeight = 36.sp,
        letterSpacing = 0.sp
    ),
    headlineSmall = TextStyle(
        fontWeight = FontWeight.Normal,
        fontSize = 24.sp,
        lineHeight = 32.sp,
        letterSpacing = 0.sp
    ),
    titleLarge = TextStyle(
        fontWeight = FontWeight.Normal,
        fontSize = 22.sp,
        lineHeight = 28.sp,
        letterSpacing = 0.sp
    ),
    titleMedium = TextStyle(
        fontWeight = FontWeight.Medium,
        fontSize = 16.sp,
        lineHeight = 24.sp,
        letterSpacing = 0.15.sp
    ),
    titleSmall = TextStyle(
        fontWeight = FontWeight.Medium,
        fontSize = 14.sp,
        lineHeight = 20.sp,
        letterSpacing = 0.1.sp
    ),
    bodyLarge = TextStyle(
        fontWeight = FontWeight.Normal,
        fontSize = 16.sp,
        lineHeight = 24.sp,
        letterSpacing = 0.5.sp
    ),
    bodyMedium = TextStyle(
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
        lineHeight = 20.sp,
        letterSpacing = 0.25.sp
    ),
    bodySmall = TextStyle(
        fontWeight = FontWeight.Normal,
        fontSize = 12.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.4.sp
    ),
    labelLarge = TextStyle(
        fontWeight = FontWeight.Medium,
        fontSize = 14.sp,
        lineHeight = 20.sp,
        letterSpacing = 0.1.sp
    ),
    labelMedium = TextStyle(
        fontWeight = FontWeight.Medium,
        fontSize = 12.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.5.sp
    ),
    labelSmall = TextStyle(
        fontWeight = FontWeight.Medium,
        fontSize = 11.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.5.sp
    )
)

/**
 * TinaIDE 统一形状定义
 *
 * 设计规范：
 * - extraSmall (4dp): 徽章、标签
 * - small (8dp): 小型卡片、小按钮
 * - medium (12dp): 按钮、输入框
 * - large (16dp): 卡片
 * - extraLarge (24dp): 对话框、底部弹窗
 */
private val TinaIDEShapes: Shapes = Shapes(
    extraSmall = RoundedCornerShape(4.dp),
    small = RoundedCornerShape(8.dp),
    medium = RoundedCornerShape(12.dp),
    large = RoundedCornerShape(16.dp),
    extraLarge = RoundedCornerShape(24.dp),
)
