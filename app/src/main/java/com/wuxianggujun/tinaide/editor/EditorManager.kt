package com.wuxianggujun.tinaide.editor

import android.content.Context
import android.util.Log
import androidx.fragment.app.FragmentManager
import com.wuxianggujun.tinaide.R
import com.wuxianggujun.tinaide.core.ServiceLifecycle
import com.wuxianggujun.tinaide.ui.fragment.EditorFragment
import java.io.File
import java.util.UUID

/**
 * 编辑器管理器实现
 */
class EditorManager(
    private val context: Context,
    private val fragmentManager: FragmentManager
) : IEditorManager, ServiceLifecycle {
    
    companion object {
        private const val TAG = "EditorManager"
    }
    
    private val openTabs = mutableListOf<EditorTab>()
    private val tabFragments = mutableMapOf<String, EditorFragment>()
    private var currentTab: EditorTab? = null
    private var fontSize: Int = 14
    
    override fun onCreate() {
        // 初始化
    }
    
    override fun onDestroy() {
        // 清理资源
        openTabs.clear()
        tabFragments.clear()
        currentTab = null
    }
    
    override fun openFile(file: File): EditorTab {
        // 检查文件是否已经打开
        openTabs.find { it.file.absolutePath == file.absolutePath }?.let {
            switchToTab(it)
            return it
        }
        
        // 创建新标签页
        val tab = EditorTab(
            id = UUID.randomUUID().toString(),
            file = file
        )
        
        // 创建 Fragment
        val fragment = EditorFragment.newInstance(file.absolutePath)
        tabFragments[tab.id] = fragment
        
        // 读取文件内容
        try {
            val content = file.readText()
            fragment.setText(content)
        } catch (e: Exception) {
            Log.e(TAG, "Error reading file: ${file.absolutePath}", e)
        }
        
        // 添加到标签页列表
        openTabs.add(tab)
        currentTab = tab
        
        // 显示 Fragment
        showFragment(fragment)
        
        return tab
    }
    
    override fun closeFile(tab: EditorTab) {
        // 移除标签页
        openTabs.remove(tab)
        
        // 移除 Fragment
        tabFragments[tab.id]?.let { fragment ->
            fragmentManager.beginTransaction()
                .remove(fragment)
                .commit()
            tabFragments.remove(tab.id)
        }
        
        // 如果关闭的是当前标签页，切换到其他标签页
        if (currentTab?.id == tab.id) {
            currentTab = openTabs.lastOrNull()
            currentTab?.let { switchToTab(it) }
        }
    }
    
    override fun saveFile(tab: EditorTab) {
        val fragment = tabFragments[tab.id] ?: return
        
        try {
            val content = fragment.getText()
            tab.file.writeText(content)
            tab.isDirty = false
            Log.d(TAG, "File saved: ${tab.file.absolutePath}")
        } catch (e: Exception) {
            Log.e(TAG, "Error saving file: ${tab.file.absolutePath}", e)
            throw e
        }
    }
    
    override fun saveAllFiles() {
        openTabs.forEach { tab ->
            if (tab.isDirty) {
                try {
                    saveFile(tab)
                } catch (e: Exception) {
                    Log.e(TAG, "Error saving file: ${tab.file.absolutePath}", e)
                }
            }
        }
    }
    
    override fun getOpenTabs(): List<EditorTab> {
        return openTabs.toList()
    }
    
    override fun switchToTab(tab: EditorTab) {
        if (currentTab?.id == tab.id) return
        
        currentTab = tab
        
        // 显示对应的 Fragment
        tabFragments[tab.id]?.let { fragment ->
            showFragment(fragment)
        }
    }
    
    override fun getCurrentTab(): EditorTab? {
        return currentTab
    }
    
    override fun setFontSize(size: Int) {
        fontSize = size
        
        // 更新所有编辑器的字体大小
        tabFragments.values.forEach { fragment ->
            fragment.setTextSize(size.toFloat())
        }
    }
    
    override fun undo(tab: EditorTab) {
        tabFragments[tab.id]?.undo()
    }
    
    override fun redo(tab: EditorTab) {
        tabFragments[tab.id]?.redo()
    }
    
    override fun find(query: String) {
        // TODO: 实现查找功能
        Log.d(TAG, "Find: $query")
    }
    
    override fun replace(query: String, replacement: String) {
        // TODO: 实现替换功能
        Log.d(TAG, "Replace: $query -> $replacement")
    }
    
    /**
     * 显示 Fragment
     */
    private fun showFragment(fragment: EditorFragment) {
        val transaction = fragmentManager.beginTransaction()
        
        // 隐藏所有其他 Fragment
        tabFragments.values.forEach { f ->
            if (f != fragment && f.isAdded) {
                transaction.hide(f)
            }
        }
        
        // 显示目标 Fragment
        if (fragment.isAdded) {
            transaction.show(fragment)
        } else {
            transaction.add(R.id.editor_container, fragment, fragment.getFilePath())
        }
        
        transaction.commit()
    }
    
    /**
     * 获取 Fragment
     */
    fun getFragment(tab: EditorTab): EditorFragment? {
        return tabFragments[tab.id]
    }
}
