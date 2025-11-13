package com.wuxianggujun.tinaide.ui.adapter

import android.view.View
import android.widget.ImageView
import android.widget.TextView
import com.wuxianggujun.tinaide.R
import com.wuxianggujun.tinaide.treeview.TreeNode
import com.wuxianggujun.tinaide.treeview.base.BaseNodeViewBinder
import java.io.File

/**
 * 文件节点 ViewBinder
 */
class FileNodeViewBinder(
    itemView: View,
    private val onFileClick: (File) -> Unit,
    private val onFileLongClick: (File) -> Boolean
) : BaseNodeViewBinder<File>(itemView) {

    private val iconView: ImageView = itemView.findViewById(R.id.file_icon)
    private val nameView: TextView = itemView.findViewById(R.id.file_name)
    private val expandIcon: ImageView = itemView.findViewById(R.id.expand_icon)

    override fun bindView(treeNode: TreeNode<File>) {
        val file = treeNode.value ?: return

        // 设置文件名
        nameView.text = file.name

        // 设置图标
        if (file.isDirectory) {
            iconView.setImageResource(R.drawable.ic_folder)
            expandIcon.visibility = View.VISIBLE

            // 设置展开/折叠图标
            expandIcon.rotation = if (treeNode.isExpanded) 90f else 0f
        } else {
            iconView.setImageResource(getFileIcon(file))
            expandIcon.visibility = View.GONE
        }

        // 点击事件
        itemView.setOnClickListener {
            if (file.isDirectory) {
                treeView?.toggleNode(treeNode)
            } else {
                onFileClick(file)
            }
        }

        // 长按事件
        itemView.setOnLongClickListener {
            onFileLongClick(file)
        }
    }

    override fun getToggleTriggerViewId(): Int {
        // 返回展开图标的 ID，点击它触发展开/折叠
        return R.id.expand_icon
    }

    override fun onNodeToggled(treeNode: TreeNode<File>, expand: Boolean) {
        // 更新展开图标旋转角度
        expandIcon.rotation = if (expand) 90f else 0f
    }

    /**
     * 根据文件扩展名获取图标
     */
    private fun getFileIcon(file: File): Int {
        return when (file.extension.lowercase()) {
            "cpp", "cc", "cxx" -> R.drawable.ic_file_cpp
            "c" -> R.drawable.ic_file_c
            "h", "hpp" -> R.drawable.ic_file_header
            "java" -> R.drawable.ic_file_java
            "kt" -> R.drawable.ic_file_kotlin
            "xml" -> R.drawable.ic_file_xml
            "json" -> R.drawable.ic_file_json
            "txt" -> R.drawable.ic_file_text
            "md" -> R.drawable.ic_file_markdown
            else -> R.drawable.ic_file_default
        }
    }
}
