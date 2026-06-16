package com.wuxianggujun.tinaide.ui.compose.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.absoluteValue

/**
 * 项目图标组件 - 类似 JetBrains IDE 风格
 * 从项目名称中提取字母，使用基于名称的稳定颜色
 */
@Composable
fun ProjectIcon(
    projectName: String,
    modifier: Modifier = Modifier,
    size: Dp = 40.dp
) {
    val initials = remember(projectName) { extractInitials(projectName) }
    val backgroundColor = remember(projectName) { generateColorFromName(projectName) }

    Box(
        modifier = modifier
            .size(size)
            .clip(RoundedCornerShape(8.dp))
            .background(backgroundColor),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = initials,
            color = Color.White,
            fontSize = (size.value * 0.4f).sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1
        )
    }
}

/**
 * 从项目名称中提取缩写字母
 * - 驼峰命名: MyProject -> MP
 * - 下划线/连字符: my_project / my-project -> MP
 * - 普通名称: project -> PR
 */
private fun extractInitials(name: String): String {
    if (name.isBlank()) return "?"

    val cleaned = name.trim()

    // 1. 尝试从驼峰命名中提取大写字母
    val camelCaseInitials = cleaned.filter { it.isUpperCase() }
    if (camelCaseInitials.length >= 2) {
        return camelCaseInitials.take(2).uppercase()
    }

    // 2. 尝试从分隔符（下划线、连字符、空格）分割的单词中提取首字母
    val words = cleaned.split(Regex("[_\\-\\s]+")).filter { it.isNotEmpty() }
    if (words.size >= 2) {
        return words.take(2).map { it.first().uppercaseChar() }.joinToString("")
    }

    // 3. 如果有一个驼峰大写字母，加上第一个字母
    if (camelCaseInitials.length == 1) {
        val firstChar = cleaned.first().uppercaseChar()
        return if (firstChar == camelCaseInitials.first()) {
            // 首字母就是大写，取前两个字符
            cleaned.take(2).uppercase()
        } else {
            "$firstChar${camelCaseInitials.first()}"
        }
    }

    // 4. 默认取前两个字符
    return cleaned.take(2).uppercase()
}

/**
 * 根据项目名称生成稳定的颜色
 * 使用预定义的柔和色板，确保视觉效果良好
 */
private fun generateColorFromName(name: String): Color {
    // JetBrains 风格的柔和色板
    val colors = listOf(
        Color(0xFF6B5B95), // 紫色
        Color(0xFF88B04B), // 绿色
        Color(0xFFF7CAC9), // 粉色
        Color(0xFF92A8D1), // 蓝色
        Color(0xFF955251), // 棕红
        Color(0xFFB565A7), // 洋红
        Color(0xFF009B77), // 青绿
        Color(0xFFDD4124), // 红橙
        Color(0xFFD65076), // 玫红
        Color(0xFF45B8AC), // 蓝绿
        Color(0xFFEFC050), // 金黄
        Color(0xFF5B5EA6), // 靛蓝
        Color(0xFF9B2335), // 深红
        Color(0xFFBC243C), // 酒红
        Color(0xFFE15D44), // 橙红
        Color(0xFF7FCDCD), // 浅青
    )

    val hash = name.hashCode().absoluteValue
    return colors[hash % colors.size]
}
