package com.wuxianggujun.tinaide.ui.fragment

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.viewpager2.widget.ViewPager2
import com.google.android.material.tabs.TabLayout
import com.google.android.material.tabs.TabLayoutMediator
import com.wuxianggujun.tinaide.R
import com.wuxianggujun.tinaide.core.ServiceLocator
import com.wuxianggujun.tinaide.core.get
import com.wuxianggujun.tinaide.editor.EditorTab
import com.wuxianggujun.tinaide.editor.IEditorManager
import com.wuxianggujun.tinaide.ui.adapter.EditorTabAdapter
import java.io.File

/**
 * 编辑器容器 Fragment
 * 包含多标签页功能
 */
class EditorContainerFragment : Fragment() {
    
    private lateinit var tabLayout: TabLayout
    private lateinit var viewPager: ViewPager2
    private lateinit var adapter: EditorTabAdapter
    private var tabLayoutMediator: TabLayoutMediator? = null
    
    private val editorManager: IEditorManager by lazy {
        ServiceLocator.get<IEditorManager>()
    }
    
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_editor_container, container, false)
    }
    
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        
        tabLayout = view.findViewById(R.id.tab_layout)
        viewPager = view.findViewById(R.id.view_pager)
        
        setupViewPager()
        setupTabLayout()
    }
    
    private fun setupViewPager() {
        adapter = EditorTabAdapter(childFragmentManager, lifecycle)
        viewPager.adapter = adapter
        
        // 监听页面切换
        viewPager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                super.onPageSelected(position)
                adapter.getTab(position)?.let { tab ->
                    editorManager.switchToTab(tab)
                }
            }
        })
        
        // 初始状态：没有标签页时隐藏 TabLayout
        updateTabLayoutVisibility()
    }
    
    private fun updateTabLayoutVisibility() {
        tabLayout.visibility = if (adapter.itemCount > 0) View.VISIBLE else View.GONE
    }
    
    private fun setupTabLayout() {
        tabLayoutMediator = TabLayoutMediator(tabLayout, viewPager) { tab, position ->
            val editorTab = adapter.getTab(position)
            tab.text = editorTab?.file?.name ?: "未命名"
            
            // 在 Tab 创建时立即设置长按监听器
            tab.view.setOnLongClickListener { view ->
                android.util.Log.d("EditorContainer", "Tab long clicked at position: $position")
                editorTab?.let { 
                    showTabContextMenu(view, it)
                }
                true
            }
        }
        tabLayoutMediator?.attach()
    }
    
    /**
     * 设置 Tab 长按监听器（用于动态添加的 Tab）
     */
    private fun setupTabLongClickListeners() {
        // 延迟执行，确保 Tab 已经创建
        tabLayout.postDelayed({
            for (i in 0 until tabLayout.tabCount) {
                val tab = tabLayout.getTabAt(i)
                tab?.view?.setOnLongClickListener { view ->
                    android.util.Log.d("EditorContainer", "Tab long clicked at position: $i")
                    val editorTab = adapter.getTab(i)
                    editorTab?.let { showTabContextMenu(view, it) }
                    true
                }
            }
        }, 100) // 延迟 100ms 确保 Tab 已经创建
    }
    
    /**
     * 显示 Tab 上下文菜单
     */
    private fun showTabContextMenu(anchorView: View, tab: EditorTab) {
        com.wuxianggujun.tinaide.ui.dialog.TabContextMenu.show(
            anchorView = anchorView,
            tab = tab,
            onClose = { closeTab(tab) },
            onCloseOthers = { closeOtherTabs(tab) },
            onCloseAll = { closeAllTabs() }
        )
    }
    
    /**
     * 打开文件
     */
    fun openFile(file: File) {
        android.util.Log.d("EditorContainer", "Opening file: ${file.absolutePath}")
        
        // 检查文件是否已经在适配器中
        val existingTab = adapter.getTabs().find { it.file.absolutePath == file.absolutePath }
        if (existingTab != null) {
            val position = adapter.findTabPosition(existingTab)
            android.util.Log.d("EditorContainer", "File already open at position: $position")
            viewPager.currentItem = position
            updateTabLayoutVisibility()
            return
        }
        
        // 创建新标签页
        val tab = editorManager.openFile(file)
        android.util.Log.d("EditorContainer", "Created new tab: ${tab.id}")
        
        // 添加到适配器
        adapter.addTab(tab)
        android.util.Log.d("EditorContainer", "Tab added, total tabs: ${adapter.itemCount}")
        
        // 切换到新标签页
        viewPager.currentItem = adapter.itemCount - 1
        
        // 更新 TabLayout 可见性
        updateTabLayoutVisibility()
        
        // 更新长按监听器
        setupTabLongClickListeners()
        
        android.util.Log.d("EditorContainer", "TabLayout visibility: ${if (tabLayout.visibility == View.VISIBLE) "VISIBLE" else "GONE"}")
    }
    
    /**
     * 关闭标签页
     */
    fun closeTab(tab: EditorTab) {
        val position = adapter.findTabPosition(tab)
        if (position >= 0) {
            editorManager.closeFile(tab)
            adapter.removeTab(position)
            
            // 如果还有其他标签页，切换到相邻的标签页
            if (adapter.itemCount > 0) {
                val newPosition = if (position > 0) position - 1 else 0
                viewPager.currentItem = newPosition
            }
            
            // 更新 TabLayout 可见性
            updateTabLayoutVisibility()
            
            // 更新长按监听器
            if (adapter.itemCount > 0) {
                setupTabLongClickListeners()
            }
        }
    }
    
    /**
     * 关闭当前标签页
     */
    fun closeCurrentTab() {
        val currentPosition = viewPager.currentItem
        adapter.getTab(currentPosition)?.let { tab ->
            closeTab(tab)
        }
    }
    
    /**
     * 关闭其他标签页
     */
    fun closeOtherTabs(keepTab: EditorTab) {
        val tabs = adapter.getTabs().toList()
        tabs.forEach { tab ->
            if (tab.id != keepTab.id) {
                editorManager.closeFile(tab)
            }
        }
        
        // 从后往前移除，避免索引变化
        adapter.getTabs().indices.reversed().forEach { position ->
            val tab = adapter.getTab(position)
            if (tab?.id != keepTab.id) {
                adapter.removeTab(position)
            }
        }
        
        // 切换到保留的标签页
        val keepPosition = adapter.findTabPosition(keepTab)
        if (keepPosition >= 0) {
            viewPager.currentItem = keepPosition
        }
        
        // 更新 TabLayout 可见性
        updateTabLayoutVisibility()
        
        // 更新长按监听器
        setupTabLongClickListeners()
    }
    
    /**
     * 关闭所有标签页
     */
    fun closeAllTabs() {
        val tabs = adapter.getTabs()
        tabs.forEach { tab ->
            editorManager.closeFile(tab)
        }
        adapter.getTabs().indices.reversed().forEach { position ->
            adapter.removeTab(position)
        }
        
        // 更新 TabLayout 可见性
        updateTabLayoutVisibility()
    }
    
    /**
     * 保存当前文件
     */
    fun saveCurrentFile() {
        val currentPosition = viewPager.currentItem
        adapter.getTab(currentPosition)?.let { tab ->
            val fragment = adapter.getFragment(tab)
            fragment?.let {
                try {
                    val content = it.getText()
                    tab.file.writeText(content)
                    tab.isDirty = false
                    android.util.Log.d("EditorContainer", "File saved: ${tab.file.absolutePath}")
                    android.widget.Toast.makeText(requireContext(), "文件已保存", android.widget.Toast.LENGTH_SHORT).show()
                } catch (e: Exception) {
                    android.util.Log.e("EditorContainer", "Error saving file", e)
                    android.widget.Toast.makeText(requireContext(), "保存失败: ${e.message}", android.widget.Toast.LENGTH_SHORT).show()
                }
            }
        }
    }
    
    /**
     * 保存所有文件
     */
    fun saveAllFiles() {
        adapter.getTabs().forEach { tab ->
            val fragment = adapter.getFragment(tab)
            fragment?.let {
                try {
                    val content = it.getText()
                    tab.file.writeText(content)
                    tab.isDirty = false
                } catch (e: Exception) {
                    android.util.Log.e("EditorContainer", "Error saving file: ${tab.file.absolutePath}", e)
                }
            }
        }
        android.widget.Toast.makeText(requireContext(), "所有文件已保存", android.widget.Toast.LENGTH_SHORT).show()
    }
    
    override fun onDestroyView() {
        super.onDestroyView()
        tabLayoutMediator?.detach()
        tabLayoutMediator = null
    }
}
