package com.wuxianggujun.tinaide.ui.fragment

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.wuxianggujun.tinaide.R
import com.wuxianggujun.tinaide.core.ServiceLocator
import com.wuxianggujun.tinaide.core.get
import com.wuxianggujun.tinaide.file.IFileManager
import com.wuxianggujun.tinaide.extensions.toastError
import com.wuxianggujun.tinaide.ui.adapter.FileTreeAdapter
import com.wuxianggujun.tinaide.ui.dialog.FileContextMenuDialog
import java.io.File

/**
 * 文件树 Fragment
 * 显示项目文件结构
 */
class FileTreeFragment : Fragment() {
    private lateinit var recyclerView: RecyclerView
    private lateinit var emptyView: TextView
    private lateinit var adapter: FileTreeAdapter
    
    private fun fileManagerOrNull(): IFileManager? = try {
        ServiceLocator.get(IFileManager::class.java)
    } catch (_: IllegalStateException) { null }
    
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_file_tree, container, false)
    }
    
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        
        recyclerView = view.findViewById(R.id.file_tree_recycler)
        emptyView = view.findViewById(R.id.empty_view)
        
        setupRecyclerView()
        // 推迟到首帧后加载，避免进入页面首帧阻塞导致黑屏
        view.post { loadProject() }
    }
    
    private fun setupRecyclerView() {
        adapter = FileTreeAdapter(
            onFileClick = { file ->
                handleFileClick(file)
            },
            onFileLongClick = { file ->
                handleFileLongClick(file)
            }
        )
        
        recyclerView.layoutManager = LinearLayoutManager(requireContext())
        recyclerView.adapter = adapter
    }
    
    private fun loadProject() {
        val fm = fileManagerOrNull()
        if (fm == null) {
            try { requireContext().toastError("Service IFileManager 未注册") } catch (_: Throwable) {}
            showEmptyView()
            return
        }
        val project = fm.getCurrentProject()
        
        if (project == null) {
            showEmptyView()
        } else {
            hideEmptyView()
            loadProjectFiles(project.rootPath)
        }
    }
    
    private fun loadProjectFiles(rootPath: String) {
        val rootDir = File(rootPath)
        if (rootDir.exists() && rootDir.isDirectory) {
            val files = rootDir.listFiles()?.toList() ?: emptyList()
            adapter.setFiles(files)
        }
    }
    
    private fun handleFileClick(file: File) {
        if (file.isDirectory) {
            // 展开/折叠目录
            adapter.toggleDirectory(file)
        } else {
            // 打开文件
            openFileInEditor(file)
        }
    }
    
    private fun openFileInEditor(file: File) {
        // 获取 EditorContainerFragment 并打开文件
        val activity = requireActivity()
        val editorContainer = activity.supportFragmentManager.findFragmentById(R.id.editor_container) 
            as? com.wuxianggujun.tinaide.ui.fragment.EditorContainerFragment
        
        if (editorContainer != null) {
            editorContainer.openFile(file)
        } else {
            android.util.Log.e("FileTreeFragment", "EditorContainerFragment not found!")
        }
    }
    
    private fun handleFileLongClick(file: File): Boolean {
        // 显示上下文菜单
        showFileContextMenu(file)
        return true
    }
    
    private fun showFileContextMenu(file: File) {
        val fm = fileManagerOrNull() ?: return
        val dialog = FileContextMenuDialog(file, fm) {
            // 刷新文件树
            refresh()
        }
        dialog.show(childFragmentManager, "FileContextMenu")
    }
    
    private fun showEmptyView() {
        if (!::recyclerView.isInitialized || !::emptyView.isInitialized) return
        recyclerView.visibility = View.GONE
        emptyView.visibility = View.VISIBLE
    }
    
    private fun hideEmptyView() {
        if (!::recyclerView.isInitialized || !::emptyView.isInitialized) return
        recyclerView.visibility = View.VISIBLE
        emptyView.visibility = View.GONE
    }
    
    /**
     * 刷新文件树
     */
    fun refresh() {
        if (!isAdded || !::recyclerView.isInitialized) return
        loadProject()
    }
}
