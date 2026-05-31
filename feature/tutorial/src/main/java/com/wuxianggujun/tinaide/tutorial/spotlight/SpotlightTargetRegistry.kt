package com.wuxianggujun.tinaide.tutorial.spotlight

import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInRoot

/**
 * Spotlight 目标注册表
 *
 * 用于管理所有可被高亮引导的 UI 组件位置信息
 */
class SpotlightTargetRegistry {
    private val _targets: SnapshotStateMap<String, TargetInfo> = mutableStateMapOf()

    /** 所有已注册的目标 */
    val targets: Map<String, TargetInfo> get() = _targets

    /**
     * 注册一个目标组件
     *
     * @param id 目标的语义化 ID，如 "run_button", "project_tab"
     * @param coordinates 组件的布局坐标
     */
    fun register(id: String, coordinates: LayoutCoordinates) {
        if (coordinates.isAttached) {
            val bounds = coordinates.boundsInRoot()
            _targets[id] = TargetInfo(
                id = id,
                bounds = bounds,
                center = Offset(
                    x = bounds.left + bounds.width / 2,
                    y = bounds.top + bounds.height / 2
                )
            )
        }
    }

    /**
     * 注销一个目标组件
     */
    fun unregister(id: String) {
        _targets.remove(id)
    }

    /**
     * 获取目标的边界信息
     */
    fun getTargetBounds(id: String): Rect? {
        return _targets[id]?.bounds
    }

    /**
     * 获取目标信息
     */
    fun getTarget(id: String): TargetInfo? {
        return _targets[id]
    }

    /**
     * 清除所有注册的目标
     */
    fun clear() {
        _targets.clear()
    }
}

/**
 * 目标信息
 */
data class TargetInfo(
    val id: String,
    val bounds: Rect,
    val center: Offset
)

/**
 * CompositionLocal 用于在 Compose 树中共享 SpotlightTargetRegistry
 */
val LocalSpotlightRegistry = compositionLocalOf { SpotlightTargetRegistry() }

/**
 * 创建并记住一个 SpotlightTargetRegistry 实例
 */
@Composable
fun rememberSpotlightRegistry(): SpotlightTargetRegistry {
    return remember { SpotlightTargetRegistry() }
}
