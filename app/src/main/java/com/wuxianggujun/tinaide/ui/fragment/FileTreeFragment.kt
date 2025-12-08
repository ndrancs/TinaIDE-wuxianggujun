package com.wuxianggujun.tinaide.ui.fragment

import android.os.Bundle
import android.view.View
import android.widget.HorizontalScrollView
import android.widget.PopupMenu
import androidx.lifecycle.lifecycleScope
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.wuxianggujun.tinaide.base.BaseBindingFragment
import com.wuxianggujun.tinaide.databinding.FragmentFileTreeBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.wuxianggujun.tinaide.R
import com.wuxianggujun.tinaide.core.ServiceLocator
import com.wuxianggujun.tinaide.core.get
import com.wuxianggujun.tinaide.file.IFileManager
import com.wuxianggujun.tinaide.extensions.toastError
import com.wuxianggujun.tinaide.extensions.toastSuccess
import com.wuxianggujun.tinaide.extensions.toastInfo
import com.wuxianggujun.tinaide.extensions.handleErrorWithToast
import com.wuxianggujun.tinaide.treeview.TreeNode
import com.wuxianggujun.tinaide.treeview.TreeView
import com.wuxianggujun.tinaide.ui.adapter.FileNodeViewFactory
import com.wuxianggujun.tinaide.ui.adapter.FileNodeViewBinder
import com.wuxianggujun.tinaide.ui.file.TreeUtil
import com.wuxianggujun.tinaide.ui.file.model.TreeFile
import java.io.File

/**
 * 文件树 Fragment
 * 显示项目文件结构
 */
class FileTreeFragment : BaseBindingFragment<FragmentFileTreeBinding>(
    FragmentFileTreeBinding::inflate
) {
    private var treeView: TreeView<TreeFile>? = null
    private lateinit var refreshLayout: SwipeRefreshLayout
    private lateinit var horizontalScrollView: HorizontalScrollView
    private var pendingRefresh = false
    private var selectedNode: TreeNode<TreeFile>? = null

    private fun fileManagerOrNull(): IFileManager? = try {
        ServiceLocator.get(IFileManager::class.java)
    } catch (_: IllegalStateException) { null }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        refreshLayout = binding.refreshLayout
        horizontalScrollView = binding.horizontalScrollView

        // 设置下拉刷新
        refreshLayout.setOnRefreshListener {
            partialRefresh {
                refreshLayout.isRefreshing = false
                treeView?.refreshTreeView()
            }
        }

        // 推迟到首帧后加载，避免进入页面首帧阻塞导致黑屏
        pendingRefresh = true
        triggerPendingLoad()
    }

    private fun triggerPendingLoad() {
        if (!isAdded) {
            return
        }
        val fragmentView = view ?: return
        if (!pendingRefresh) {
            return
        }
        fragmentView.post {
            if (!isAdded) {
                return@post
            }
            pendingRefresh = false
            loadProject()
        }
    }

    private fun loadProject() {
        val fm = fileManagerOrNull()
        if (fm == null) {
            try { requireContext().toastError("Service IFileManager 未注册") } catch (_: Throwable) {}
            return
        }
        val project = fm.getCurrentProject()

        if (project != null) {
            loadProjectFiles(project.rootPath)
        }
    }

    private fun loadProjectFiles(rootPath: String) {
        val rootDir = File(rootPath)
        if (rootDir.exists() && rootDir.isDirectory) {
            val owner = view?.let { viewLifecycleOwner } ?: return
            owner.lifecycleScope.launch(Dispatchers.IO) {
                val root = TreeNode.root(TreeUtil.getNodes(rootDir))

                withContext(Dispatchers.Main) {
                    if (!isAdded || view == null) {
                        return@withContext
                    }
                    setupTreeView(root)
                }
            }
        }
    }

    /**
     * 设置 TreeView
     */
    private fun setupTreeView(root: TreeNode<TreeFile>) {
        // 创建 TreeView
        val tv = TreeView(requireContext(), root)

        // 创建 ViewFactory
        val factory = FileNodeViewFactory(object : FileNodeViewBinder.TreeFileNodeListener {
            override fun onNodeToggled(treeNode: TreeNode<TreeFile>?, expanded: Boolean) {
                // 记录当前选中的节点
                selectedNode = treeNode
                
                if (treeNode?.isLeaf() == true) {
                    val file = treeNode.value?.file
                    if (file?.isFile == true) {
                        openFileInEditor(file)
                    }
                }
            }

            override fun onNodeLongClicked(
                view: View?,
                treeNode: TreeNode<TreeFile>?,
                expanded: Boolean
            ): Boolean {
                if (view != null && treeNode != null) {
                    // 记录当前选中的节点
                    selectedNode = treeNode
                    showFileContextMenu(view, treeNode)
                }
                return true
            }
        })

        tv.setAdapter(factory)

        // 添加到容器
        horizontalScrollView.removeAllViews()
        horizontalScrollView.addView(
            tv.getView(),
            android.view.ViewGroup.LayoutParams(
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
                android.view.ViewGroup.LayoutParams.MATCH_PARENT
            )
        )

        this.treeView = tv
    }

    private fun openFileInEditor(file: File) {
        // 获取 EditorContainerFragment 并打开文件
        val activity = requireActivity()
        val editorContainer = activity.supportFragmentManager.findFragmentById(R.id.editor_container)
            as? EditorContainerFragment

        if (editorContainer != null) {
            editorContainer.openFile(file)
        } else {
            android.util.Log.e("FileTreeFragment", "EditorContainerFragment not found!")
        }
    }

    private fun showFileContextMenu(view: View, treeNode: TreeNode<TreeFile>) {
        val file = treeNode.value?.file ?: return
        val popupMenu = PopupMenu(requireContext(), view)
        val menu = popupMenu.menu
        
        // 添加菜单项
        menu.add(0, MENU_RENAME, 0, "重命名")
        menu.add(0, MENU_DELETE, 1, "删除")
        menu.add(0, MENU_COPY_PATH, 2, "复制路径")
        menu.add(0, MENU_COPY_RELATIVE_PATH, 3, "复制相对路径")
        
        if (file.isFile) {
            menu.add(0, MENU_CUT, 4, "剪切")
        }
        
        if (file.isDirectory) {
            menu.add(0, MENU_NEW_FILE, 5, "新建文件")
        }
        
        popupMenu.setOnMenuItemClickListener { menuItem ->
            handleMenuAction(menuItem.itemId, file, treeNode)
            true
        }
        
        popupMenu.show()
    }
    
    private fun handleMenuAction(actionId: Int, file: File, treeNode: TreeNode<TreeFile>) {
        when (actionId) {
            MENU_RENAME -> showRenameDialog(file)
            MENU_DELETE -> showDeleteConfirmDialog(file)
            MENU_COPY_PATH -> copyPathToClipboard(file.absolutePath)
            MENU_COPY_RELATIVE_PATH -> copyRelativePathToClipboard(file)
            MENU_CUT -> cutFile(file)
            MENU_NEW_FILE -> showNewFileDialog(file)
        }
    }
    
    private fun showRenameDialog(file: File) {
        val context = requireContext()
        val dialog = com.wuxianggujun.tinaide.ui.dialog.InputDialog.newInstance(
            title = "重命名",
            hint = "新名称",
            defaultValue = file.name,
            validator = { input ->
                when {
                    input.isEmpty() -> "名称不能为空"
                    !input.matches(Regex("[a-zA-Z0-9_.-]+")) -> "名称包含非法字符"
                    input == file.name -> "名称未改变"
                    File(file.parent, input).exists() -> "文件已存在"
                    else -> null
                }
            },
            onConfirm = { newName ->
                val newFile = File(file.parent, newName)
                if (file.renameTo(newFile)) {
                    context.toastSuccess("重命名成功")
                    refresh()
                } else {
                    context.toastError("重命名失败")
                }
            }
        )
        dialog.show(parentFragmentManager, "rename_dialog")
    }
    
    private fun showDeleteConfirmDialog(file: File) {
        val context = requireContext()
        val fileType = if (file.isDirectory) "文件夹" else "文件"
        val dialog = com.wuxianggujun.tinaide.ui.dialog.ConfirmDialog.newInstance(
            title = "删除$fileType",
            message = "确定要删除 ${file.name} 吗？此操作不可恢复。",
            onPositive = {
                if (file.deleteRecursively()) {
                    context.toastSuccess("删除成功")
                    refresh()
                } else {
                    context.toastError("删除失败")
                }
            }
        )
        dialog.show(parentFragmentManager, "delete_confirm_dialog")
    }
    
    private fun copyPathToClipboard(path: String) {
        val context = requireContext()
        val clipboard = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) 
            as android.content.ClipboardManager
        val clip = android.content.ClipData.newPlainText("文件路径", path)
        clipboard.setPrimaryClip(clip)
        context.toastSuccess("已复制路径")
    }
    
    private fun copyRelativePathToClipboard(file: File) {
        val context = requireContext()
        val fm = fileManagerOrNull()
        val project = fm?.getCurrentProject()
        
        if (project == null) {
            context.toastError("无法获取项目根目录")
            return
        }
        
        val rootPath = File(project.rootPath).absolutePath
        val filePath = file.absolutePath
        val relativePath = if (filePath.startsWith(rootPath)) {
            filePath.substring(rootPath.length).trimStart('/', '\\')
        } else {
            filePath
        }
        
        val clipboard = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) 
            as android.content.ClipboardManager
        val clip = android.content.ClipData.newPlainText("相对路径", relativePath)
        clipboard.setPrimaryClip(clip)
        context.toastSuccess("已复制相对路径")
    }
    
    private fun cutFile(file: File) {
        // TODO: 实现剪切功能，需要配合粘贴功能
        requireContext().toastInfo("剪切功能开发中")
    }
    
    private fun showNewFileDialog(parentDir: File) {
        val context = requireContext()
        val dialog = com.wuxianggujun.tinaide.ui.dialog.InputDialog.newInstance(
            title = "新建文件",
            hint = "文件名，例如 main.cpp",
            validator = { input ->
                when {
                    input.isEmpty() -> "文件名不能为空"
                    !input.matches(Regex("[a-zA-Z0-9_.-]+")) -> "文件名包含非法字符"
                    File(parentDir, input).exists() -> "文件已存在"
                    else -> null
                }
            },
            onConfirm = { name ->
                com.wuxianggujun.tinaide.utils.FileUtils.createFile(parentDir, name)
                    .onSuccess { file ->
                        context.toastSuccess("已创建 ${file.name}")
                        refresh()
                    }
                    .onFailure { error ->
                        context.handleErrorWithToast(error, "创建失败")
                    }
            }
        )
        dialog.show(parentFragmentManager, "new_file_dialog")
    }
    
    companion object {
        private const val MENU_RENAME = 1
        private const val MENU_DELETE = 2
        private const val MENU_COPY_PATH = 3
        private const val MENU_COPY_RELATIVE_PATH = 4
        private const val MENU_CUT = 5
        private const val MENU_NEW_FILE = 6
    }

    private fun partialRefresh(callback: () -> Unit) {
        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            val allNodes = treeView?.getAllNodes() ?: emptyList()
            if (allNodes.isNotEmpty()) {
                val node = allNodes[0]
                TreeUtil.updateNode(node)
                withContext(Dispatchers.Main) {
                    if (isAdded) {
                        callback()
                    }
                }
            }
        }
    }

    /**
     * 刷新文件树
     */
    fun refresh() {
        if (!isAdded) {
            pendingRefresh = true
            return
        }
        pendingRefresh = true
        triggerPendingLoad()
    }
    
    /**
     * 获取当前选中的文件夹
     * 如果选中的是文件，返回其父文件夹
     * 如果没有选中任何节点，返回项目根目录
     */
    fun getSelectedDirectory(): File? {
        val fm = fileManagerOrNull() ?: return null
        val project = fm.getCurrentProject() ?: return null
        
        val node = selectedNode
        if (node != null) {
            val file = node.value?.file
            if (file != null) {
                return if (file.isDirectory) file else file.parentFile
            }
        }
        
        // 如果没有选中节点，返回项目根目录
        return File(project.rootPath)
    }
}
