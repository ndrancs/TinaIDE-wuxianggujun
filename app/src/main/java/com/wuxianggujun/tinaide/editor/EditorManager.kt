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
    private var currentTab: EditorTab? = null
    private var fontSize: Int = 14
    
    override fun onCreate() {
        // 初始化
    }
    
    override fun onDestroy() {
        // 清理资源
        openTabs.clear()
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
        
        // 添加到标签页列表
        openTabs.add(tab)
        currentTab = tab
        
        Log.d(TAG, "Created tab for file: ${file.absolutePath}, tab id: ${tab.id}")
        
        return tab
    }
    
    override fun closeFile(tab: EditorTab) {
        // 移除标签页
        openTabs.remove(tab)
        
        // 如果关闭的是当前标签页，切换到其他标签页
        if (currentTab?.id == tab.id) {
            currentTab = openTabs.lastOrNull()
        }
        
        Log.d(TAG, "Closed tab: ${tab.id}, remaining tabs: ${openTabs.size}")
    }
    
    override fun saveFile(tab: EditorTab) {
        // 保存逻辑由 EditorContainerFragment 处理
        Log.d(TAG, "Save file requested for: ${tab.file.absolutePath}")
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
        Log.d(TAG, "Switched to tab: ${tab.id}")
    }
    
    override fun getCurrentTab(): EditorTab? {
        return currentTab
    }
    
    override fun setFontSize(size: Int) {
        fontSize = size
        Log.d(TAG, "Font size set to: $size")
    }
    
    override fun undo(tab: EditorTab) {
        Log.d(TAG, "Undo requested for tab: ${tab.id}")
    }
    
    override fun redo(tab: EditorTab) {
        Log.d(TAG, "Redo requested for tab: ${tab.id}")
    }
    
    override fun find(query: String) {
        Log.d(TAG, "Find: $query")
    }
    
    override fun replace(query: String, replacement: String) {
        Log.d(TAG, "Replace: $query -> $replacement")
    }
}
