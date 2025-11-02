package com.wuxianggujun.tinaide.ui.adapter

import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentManager
import androidx.lifecycle.Lifecycle
import androidx.viewpager2.adapter.FragmentStateAdapter
import com.wuxianggujun.tinaide.editor.EditorTab
import com.wuxianggujun.tinaide.ui.fragment.EditorFragment

/**
 * 编辑器标签页适配器
 */
class EditorTabAdapter(
    fragmentManager: FragmentManager,
    lifecycle: Lifecycle
) : FragmentStateAdapter(fragmentManager, lifecycle) {
    
    private val tabs = mutableListOf<EditorTab>()
    private val fragments = mutableMapOf<String, EditorFragment>()
    
    override fun getItemCount(): Int = tabs.size
    
    override fun createFragment(position: Int): Fragment {
        val tab = tabs[position]
        android.util.Log.d("EditorTabAdapter", "Creating fragment for position $position, file: ${tab.file.absolutePath}")
        val fragment = EditorFragment.newInstance(tab.file.absolutePath)
        fragments[tab.id] = fragment
        return fragment
    }
    
    override fun getItemId(position: Int): Long {
        return tabs[position].id.hashCode().toLong()
    }
    
    override fun containsItem(itemId: Long): Boolean {
        return tabs.any { it.id.hashCode().toLong() == itemId }
    }
    
    /**
     * 添加标签页
     */
    fun addTab(tab: EditorTab) {
        tabs.add(tab)
        notifyItemInserted(tabs.size - 1)
    }
    
    /**
     * 移除标签页
     */
    fun removeTab(position: Int) {
        if (position in tabs.indices) {
            val tab = tabs.removeAt(position)
            fragments.remove(tab.id)
            notifyItemRemoved(position)
        }
    }
    
    /**
     * 移除标签页（通过 tab）
     */
    fun removeTab(tab: EditorTab) {
        val position = tabs.indexOf(tab)
        if (position >= 0) {
            removeTab(position)
        }
    }
    
    /**
     * 获取标签页
     */
    fun getTab(position: Int): EditorTab? {
        return tabs.getOrNull(position)
    }
    
    /**
     * 获取所有标签页
     */
    fun getTabs(): List<EditorTab> {
        return tabs.toList()
    }
    
    /**
     * 获取 Fragment
     */
    fun getFragment(tab: EditorTab): EditorFragment? {
        return fragments[tab.id]
    }
    
    /**
     * 查找标签页位置
     */
    fun findTabPosition(tab: EditorTab): Int {
        return tabs.indexOf(tab)
    }
}
