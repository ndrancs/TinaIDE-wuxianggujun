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
    }
    
    private fun setupTabLayout() {
        tabLayoutMediator = TabLayoutMediator(tabLayout, viewPager) { tab, position ->
            val editorTab = adapter.getTab(position)
            tab.text = editorTab?.file?.name ?: "未命名"
            
            // 添加关闭按钮
            tab.view.setOnLongClickListener {
                editorTab?.let { closeTab(it) }
                true
            }
        }
        tabLayoutMediator?.attach()
    }
    
    /**
     * 打开文件
     */
    fun openFile(file: File) {
        val tab = editorManager.openFile(file)
        
        // 检查是否已经打开
        val existingPosition = adapter.findTabPosition(tab)
        if (existingPosition >= 0) {
            viewPager.currentItem = existingPosition
        } else {
            adapter.addTab(tab)
            viewPager.currentItem = adapter.itemCount - 1
        }
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
    }
    
    /**
     * 保存当前文件
     */
    fun saveCurrentFile() {
        val currentPosition = viewPager.currentItem
        adapter.getTab(currentPosition)?.let { tab ->
            editorManager.saveFile(tab)
        }
    }
    
    /**
     * 保存所有文件
     */
    fun saveAllFiles() {
        editorManager.saveAllFiles()
    }
    
    override fun onDestroyView() {
        super.onDestroyView()
        tabLayoutMediator?.detach()
        tabLayoutMediator = null
    }
}
