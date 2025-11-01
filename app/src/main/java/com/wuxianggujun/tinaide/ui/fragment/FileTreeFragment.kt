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
    
    private val fileManager: IFileManager by lazy {
        ServiceLocator.get<IFileManager>()
    }
    
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
        loadProject()
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
        val project = fileManager.getCurrentProject()
        
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
        val editorContainer = parentFragmentManager.findFragmentById(R.id.editor_container) 
            as? com.wuxianggujun.tinaide.ui.fragment.EditorContainerFragment
        editorContainer?.openFile(file)
    }
    
    private fun handleFileLongClick(file: File): Boolean {
        // 显示上下文菜单
        showFileContextMenu(file)
        return true
    }
    
    private fun showFileContextMenu(file: File) {
        val dialog = FileContextMenuDialog(file, fileManager) {
            // 刷新文件树
            refresh()
        }
        dialog.show(childFragmentManager, "FileContextMenu")
    }
    
    private fun showEmptyView() {
        recyclerView.visibility = View.GONE
        emptyView.visibility = View.VISIBLE
    }
    
    private fun hideEmptyView() {
        recyclerView.visibility = View.VISIBLE
        emptyView.visibility = View.GONE
    }
    
    /**
     * 刷新文件树
     */
    fun refresh() {
        loadProject()
    }
}
