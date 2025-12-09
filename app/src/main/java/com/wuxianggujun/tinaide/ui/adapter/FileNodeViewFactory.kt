package com.wuxianggujun.tinaide.ui.adapter

import android.view.View
import com.wuxianggujun.tinaide.R
import com.wuxianggujun.tinaide.treeview.TreeNode
import com.wuxianggujun.tinaide.treeview.base.BaseNodeViewBinder
import com.wuxianggujun.tinaide.treeview.base.BaseNodeViewFactory
import com.wuxianggujun.tinaide.ui.file.TreeUtil
import com.wuxianggujun.tinaide.ui.file.model.TreeFile

/**
 * 文件节点 ViewFactory
 */
class FileNodeViewFactory(
    private val nodeListener: FileNodeViewBinder.TreeFileNodeListener
) : BaseNodeViewFactory<TreeFile>() {

    override fun getNodeViewBinder(view: View, viewType: Int): BaseNodeViewBinder<TreeFile> {
        return FileNodeViewBinder(view, viewType, nodeListener)
    }

    override fun getNodeLayoutId(level: Int): Int {
        return R.layout.item_file_tree
    }

    /**
     * 当目录展开时，按需加载其真实子节点
     */
    override fun onLoadChildren(treeNode: TreeNode<TreeFile>): Boolean {
        val treeFile = treeNode.value ?: return false
        if (!treeFile.file.isDirectory) {
            treeNode.isChildrenLoaded = true
            return false
        }
        return TreeUtil.loadChildren(treeNode)
    }
}
