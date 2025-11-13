package com.wuxianggujun.tinaide.ui.adapter

import android.view.View
import com.wuxianggujun.tinaide.R
import com.wuxianggujun.tinaide.treeview.base.BaseNodeViewBinder
import com.wuxianggujun.tinaide.treeview.base.BaseNodeViewFactory
import java.io.File

/**
 * 文件节点 ViewFactory
 */
class FileNodeViewFactory(
    private val onFileClick: (File) -> Unit,
    private val onFileLongClick: (File) -> Boolean
) : BaseNodeViewFactory<File>() {

    override fun getNodeViewBinder(view: View, viewType: Int): BaseNodeViewBinder<File> {
        return FileNodeViewBinder(view, onFileClick, onFileLongClick)
    }

    override fun getNodeLayoutId(level: Int): Int {
        return R.layout.item_file_tree
    }
}
