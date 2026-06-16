package com.wuxianggujun.tinaide.tutorial.spotlight

import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.layout.onGloballyPositioned

/**
 * Modifier 扩展：将组件标记为 Spotlight 引导目标
 *
 * 使用示例：
 * ```kotlin
 * IconButton(
 *     onClick = { /* ... */ },
 *     modifier = Modifier.spotlightTarget("run_button")
 * ) {
 *     Icon(Icons.Default.PlayArrow, contentDescription = Strings.tutorial_run.str())
 * }
 * ```
 *
 * @param id 目标的语义化 ID，用于在教程步骤中引用
 */
fun Modifier.spotlightTarget(id: String): Modifier = composed {
    val registry = LocalSpotlightRegistry.current

    DisposableEffect(id) {
        onDispose {
            registry.unregister(id)
        }
    }

    this.onGloballyPositioned { coordinates ->
        registry.register(id, coordinates)
    }
}

/**
 * Modifier 扩展：条件性地将组件标记为 Spotlight 引导目标
 *
 * @param id 目标的语义化 ID
 * @param enabled 是否启用目标注册
 */
fun Modifier.spotlightTargetIf(id: String, enabled: Boolean): Modifier = if (enabled) {
    this.spotlightTarget(id)
} else {
    this
}
