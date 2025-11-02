package com.wuxianggujun.tinaide.ui.dialog

import android.view.View
import android.widget.PopupMenu
import com.wuxianggujun.tinaide.R
import com.wuxianggujun.tinaide.editor.EditorTab

/**
 * Tab 标签上下文菜单
 */
object TabContextMenu {
    
    fun show(
        anchorView: View,
        tab: EditorTab,
        onClose: () -> Unit,
        onCloseOthers: () -> Unit,
        onCloseAll: () -> Unit
    ) {
        val popup = PopupMenu(anchorView.context, anchorView)
        
        // 添加菜单项
        popup.menu.add(0, 1, 0, "关闭")
        popup.menu.add(0, 2, 1, "关闭其他")
        popup.menu.add(0, 3, 2, "关闭全部")
        
        // 设置菜单项点击监听
        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                1 -> {
                    onClose()
                    true
                }
                2 -> {
                    onCloseOthers()
                    true
                }
                3 -> {
                    onCloseAll()
                    true
                }
                else -> false
            }
        }
        
        popup.show()
    }
}
