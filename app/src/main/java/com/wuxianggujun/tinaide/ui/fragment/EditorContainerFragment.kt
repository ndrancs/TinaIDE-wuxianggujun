package com.wuxianggujun.tinaide.ui.fragment

import android.os.Bundle
import android.view.View
import android.widget.TextView
import androidx.core.view.isVisible
import androidx.recyclerview.widget.DividerItemDecoration
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.viewpager2.widget.ViewPager2
import com.google.android.material.tabs.TabLayout
import com.google.android.material.tabs.TabLayoutMediator
import com.wuxianggujun.tinaide.R
import com.wuxianggujun.tinaide.base.BaseBindingFragment
import com.wuxianggujun.tinaide.core.ServiceLocator
import com.wuxianggujun.tinaide.core.get
import com.wuxianggujun.tinaide.databinding.FragmentEditorContainerBinding
import com.wuxianggujun.tinaide.editor.EditorTab
import com.wuxianggujun.tinaide.editor.IEditorManager
import com.wuxianggujun.tinaide.extensions.*
import com.wuxianggujun.tinaide.lsp.model.Location
import com.wuxianggujun.tinaide.ui.adapter.EditorTabAdapter
import com.wuxianggujun.tinaide.ui.adapter.NativeNavigationResultAdapter

import com.wuxianggujun.tinaide.utils.Logger
import java.io.File

/**
 * 编辑器容器 Fragment
 * 包含多标签页功能
 */
class EditorContainerFragment : BaseBindingFragment<FragmentEditorContainerBinding>(
    FragmentEditorContainerBinding::inflate
) {

    private lateinit var tabLayout: TabLayout
    private lateinit var viewPager: ViewPager2
    private lateinit var adapter: EditorTabAdapter
    private var tabLayoutMediator: TabLayoutMediator? = null
    private lateinit var editorToolbar: View
    private var emptyView: View? = null  // 改为可空类型，延迟加载
    private lateinit var navigationPanel: View
    private lateinit var navigationTitle: TextView
    private lateinit var navigationCount: TextView
    private lateinit var navigationAdapter: NativeNavigationResultAdapter
    
    // EditorManager 只管理数据，Fragment 管理由本 Fragment 负责
    private val editorManager: IEditorManager
        get() = ServiceLocator.get<IEditorManager>()
    
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        tabLayout = binding.tabLayout
        viewPager = binding.viewPager
        editorToolbar = binding.editorToolbar
        setupNavigationPanel()
        // emptyView 不在这里初始化，延迟到需要时再加载

        setupViewPager()
        setupTabLayout()
        setupEditorToolbar()
    }
    
    private fun setupEditorToolbar() {
        binding.btnUndo.setOnClickListener {
            getCurrentEditorFragment()?.undo()
        }
        
        binding.btnRedo.setOnClickListener {
            getCurrentEditorFragment()?.redo()
        }
        
        binding.btnFind.setOnClickListener {
            showFindDialog()
        }
        
        binding.btnGotoLine.setOnClickListener {
            showGotoLineDialog()
        }

        binding.btnNativeDefinition.setOnClickListener {
            val fragment = getCurrentEditorFragment()
            if (fragment == null) {
                requireContext().toastInfo("没有打开的 C/C++ 文件")
            } else {
                fragment.openNativeDefinitionPicker()
            }
        }

        binding.btnNativeReferences.setOnClickListener {
            val fragment = getCurrentEditorFragment()
            if (fragment == null) {
                requireContext().toastInfo("没有打开的 C/C++ 文件")
            } else {
                fragment.openNativeReferencesPicker()
            }
        }
        
        binding.btnSave.setOnClickListener {
            saveCurrentFile()
        }
    }

    private fun setupNavigationPanel() {
        navigationPanel = binding.navigationResultsPanel
        navigationTitle = binding.navigationResultsTitle
        navigationCount = binding.navigationResultsCount
        navigationAdapter = NativeNavigationResultAdapter { location ->
            hideNativeNavigationResults()
            openLocation(location)
        }
        binding.navigationResultsList.apply {
            layoutManager = LinearLayoutManager(requireContext())
            adapter = navigationAdapter
            addItemDecoration(
                DividerItemDecoration(context, DividerItemDecoration.VERTICAL)
            )
        }
        binding.btnNavigationResultsClose.setOnClickListener { hideNativeNavigationResults() }
    }
    
    private fun getCurrentEditorFragment(): com.wuxianggujun.tinaide.ui.fragment.EditorFragment? {
        val currentPosition = viewPager.currentItem
        val tab = adapter.getTab(currentPosition)
        return tab?.let { adapter.getFragment(it) }
    }
    
    /**
     * 在当前编辑器光标位置插入文本
     */
    fun insertTextAtCursor(text: String) {
        val fragment = getCurrentEditorFragment()
        if (fragment != null) {
            fragment.insertTextAtCursor(text)
        }
    }
    
    private fun showFindDialog() {
        val fragment = getCurrentEditorFragment()
        if (fragment != null) {
            val dialog = com.wuxianggujun.tinaide.ui.dialog.FindReplaceDialog(fragment.getEditor())
            dialog.show(childFragmentManager, "FindReplace")
        } else {
            requireContext().toastWarning("没有打开的文件")
        }
    }
    
    private fun showGotoLineDialog() {
        val fragment = getCurrentEditorFragment() ?: return
        val editor = fragment.getEditor()

        val dialog = com.wuxianggujun.tinaide.ui.dialog.InputDialog.newInstance(
            title = "跳转到行",
            hint = "行号",
            validator = { value ->
                if (value.isEmpty()) {
                    "请输入行号"
                } else {
                    val line = value.toIntOrNull()
                    when {
                        line == null -> "请输入有效的数字"
                        line <= 0 || line > editor.lineCount -> "行号超出范围"
                        else -> null
                    }
                }
            },
            onConfirm = { value ->
                val line = value.toInt() - 1
                editor.setSelection(line, 0)
            }
        )
        dialog.show(childFragmentManager, "goto_line_dialog")
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
        val hasFiles = adapter.itemCount > 0

        tabLayout.visibility = if (hasFiles) View.VISIBLE else View.GONE
        editorToolbar.visibility = if (hasFiles) View.VISIBLE else View.GONE
        viewPager.visibility = if (hasFiles) View.VISIBLE else View.GONE
        if (!hasFiles) {
            hideNativeNavigationResults()
        }

        // 懒加载空状态视图
        if (!hasFiles) {
            if (emptyView == null) {
                emptyView = binding.emptyStub.inflate()
            }
            emptyView?.visibility = View.VISIBLE
        } else {
            emptyView?.visibility = View.GONE
        }
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

    fun openLocation(location: Location) {
        val targetFile = File(location.filePath)
        openFile(targetFile)
        viewPager.post {
            getCurrentEditorFragment()?.jumpToLocation(location)
        }
    }

    fun showNativeNavigationResults(title: String, locations: List<Location>) {
        if (locations.isEmpty()) {
            requireContext().toastInfo(getString(R.string.native_navigation_empty))
            hideNativeNavigationResults()
            return
        }
        navigationPanel.isVisible = true
        navigationTitle.text = title
        navigationCount.text = locations.size.toString()
        navigationAdapter.submitList(locations)
    }

    fun hideNativeNavigationResults() {
        navigationPanel.isVisible = false
        navigationAdapter.submitList(emptyList())
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
                    requireContext().toastSuccess("文件已保存")
                } catch (e: Exception) {
                    Logger.e("Error saving file", e, "EditorContainer")
                    requireContext().handleErrorWithToast(e, "保存失败")
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
        requireContext().toastSuccess("所有文件已保存")
    }
    
    override fun onDestroyView() {
        super.onDestroyView()
        tabLayoutMediator?.detach()
        tabLayoutMediator = null
    }
}
