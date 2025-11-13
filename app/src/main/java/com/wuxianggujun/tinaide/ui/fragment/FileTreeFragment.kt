package com.wuxianggujun.tinaide.ui.fragment

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.wuxianggujun.tinaide.R
import com.wuxianggujun.tinaide.core.ServiceLocator
import com.wuxianggujun.tinaide.core.get
import com.wuxianggujun.tinaide.file.IFileManager
import com.wuxianggujun.tinaide.extensions.toastError
import com.wuxianggujun.tinaide.treeview.TreeNode
import com.wuxianggujun.tinaide.treeview.TreeView
import com.wuxianggujun.tinaide.ui.adapter.FileNodeViewFactory
import com.wuxianggujun.tinaide.ui.dialog.FileContextMenuDialog
import java.io.File

/**
 * 文件树 Fragment
 * 显示项目文件结构
 */
class FileTreeFragment : Fragment() {
    private lateinit var treeViewContainer: FrameLayout
    private lateinit var emptyView: TextView
    private var treeView: TreeView<File>? = null

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

        treeViewContainer = view.findViewById(R.id.file_tree_recycler)
        emptyView = view.findViewById(R.id.empty_view)

        // 推迟到首帧后加载，避免进入页面首帧阻塞导致黑屏
        view.post { loadProject() }
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
            viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
                val root = buildFileTree(rootDir)

                withContext(Dispatchers.Main) {
                    setupTreeView(root)
                }
            }
        }
    }

    /**
     * 构建文件树
     */
    private fun buildFileTree(rootDir: File): TreeNode<File> {
        val root = TreeNode.root<File>()

        val files = try {
            rootDir.listFiles()?.sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() })) ?: emptyList()
        } catch (_: Throwable) {
            emptyList<File>()
        }

        for (file in files) {
            val node = TreeNode(file, 1)
            if (file.isDirectory) {
                // 为目录添加空占位符，用于懒加载
                // 实际子节点将在展开时加载
                loadDirectoryChildren(node, file)
            }
            root.addChild(node)
        }

        return root
    }

    /**
     * 加载目录的子文件
     */
    private fun loadDirectoryChildren(node: TreeNode<File>, dir: File) {
        if (!dir.isDirectory) return

        val children = try {
            dir.listFiles()?.sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() })) ?: emptyList()
        } catch (_: Throwable) {
            emptyList<File>()
        }

        for (child in children) {
            val childNode = TreeNode(child, node.level + 1)
            if (child.isDirectory) {
                loadDirectoryChildren(childNode, child)
            }
            node.addChild(childNode)
        }
    }

    /**
     * 设置 TreeView
     */
    private fun setupTreeView(root: TreeNode<File>) {
        // 创建 TreeView
        val tv = TreeView(requireContext(), root)

        // 创建 ViewFactory
        val factory = FileNodeViewFactory(
            onFileClick = { file ->
                handleFileClick(file)
            },
            onFileLongClick = { file ->
                handleFileLongClick(file)
                true
            }
        )

        tv.setAdapter(factory)

        // 添加到容器
        treeViewContainer.removeAllViews()
        treeViewContainer.addView(tv.getView())

        this.treeView = tv
    }

    private fun handleFileClick(file: File) {
        if (!file.isDirectory) {
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
        if (!::treeViewContainer.isInitialized || !::emptyView.isInitialized) return
        treeViewContainer.visibility = View.GONE
        emptyView.visibility = View.VISIBLE
    }

    private fun hideEmptyView() {
        if (!::treeViewContainer.isInitialized || !::emptyView.isInitialized) return
        treeViewContainer.visibility = View.VISIBLE
        emptyView.visibility = View.GONE
    }

    /**
     * 刷新文件树
     */
    fun refresh() {
        if (!isAdded || !::treeViewContainer.isInitialized) return
        loadProject()
    }
}
