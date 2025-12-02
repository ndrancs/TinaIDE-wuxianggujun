package com.wuxianggujun.tinaide.ui.adapter

import android.util.Log
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import androidx.core.view.ViewCompat
import com.wuxianggujun.tinaide.R
import com.wuxianggujun.tinaide.treeview.TreeNode
import com.wuxianggujun.tinaide.treeview.base.BaseNodeViewBinder
import java.io.File
import kotlin.math.roundToInt
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap

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
    private val basePaddingStart = itemView.paddingStart
    private val basePaddingTop = itemView.paddingTop
    private val basePaddingEnd = itemView.paddingEnd
    private val basePaddingBottom = itemView.paddingBottom
    private val indentPerLevelPx =
        (itemView.resources.displayMetrics.density * 16f).roundToInt()

    override fun bindView(treeNode: TreeNode<File>) {
        val file = treeNode.value ?: return

        applyIndentation(treeNode.level)

        // 设置文件名
        nameView.text = file.name

        // 设置图标
        if (file.isDirectory) {
            val hasChildren = directoryHasChildren(treeNode, file)
            Log.d(
                TAG,
                "Bind directory: ${file.absolutePath}, level=${treeNode.level}, expanded=${treeNode.isExpanded}, hasChildren=$hasChildren"
            )
            iconView.setImageResource(R.drawable.ic_folder)
            if (hasChildren) {
                expandIcon.visibility = View.VISIBLE
                updateExpandIcon(treeNode.isExpanded, animate = false)
            } else {
                expandIcon.visibility = View.GONE
            }
        } else {
            Log.d(TAG, "Bind file: ${file.absolutePath}")
            iconView.setImageResource(getFileIcon(file))
            expandIcon.visibility = View.GONE
        }

        // 点击事件
        itemView.setOnClickListener {
            if (file.isDirectory) {
                // 懒加载：在展开前加载子节点
                if (!treeNode.isExpanded && treeNode.getChildren().isEmpty()) {
                    loadDirectoryChildren(treeNode, file)
                }
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
        val file = treeNode.value ?: return
        
        // 懒加载：在展开前加载子节点
        if (expand && file.isDirectory && treeNode.getChildren().isEmpty()) {
            loadDirectoryChildren(treeNode, file)
        }
        
        // 更新展开图标旋转角度
        updateExpandIcon(expand, animate = true)
    }
    private fun updateExpandIcon(expanded: Boolean, animate: Boolean) {
        val targetRotation = if (expanded) 0f else -90f
        if (animate) {
            expandIcon.animate()
                .rotation(targetRotation)
                .setDuration(120L)
                .start()
        } else {
            expandIcon.rotation = targetRotation
        }
    }

    private fun applyIndentation(level: Int) {
        val indentLevel = (level - 1).coerceAtLeast(0)
        val startPadding = basePaddingStart + indentLevel * indentPerLevelPx
        // 根据节点层级来偏移，保证树结构可读
        ViewCompat.setPaddingRelative(
            itemView,
            startPadding,
            basePaddingTop,
            basePaddingEnd,
            basePaddingBottom
        )
    }
    
    /**
     * 懒加载目录的子文件
     */
    private fun loadDirectoryChildren(node: TreeNode<File>, dir: File) {
        if (!dir.isDirectory) return
        
        // 如果已经加载过子节点，不重复加载
        if (node.getChildren().isNotEmpty()) return

        val dirPath = dir.absolutePath
        if (loadedDirectories.contains(dirPath)) {
            Log.d(TAG, "Skip loading cached directory: $dirPath")
            return
        }

        val children = try {
            dir.listFiles()?.sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() })) ?: emptyList()
        } catch (_: Throwable) {
            emptyList<File>()
        }

        Log.d(TAG, "Loading ${children.size} children for $dirPath")
        if (children.isEmpty()) {
            emptyDirectories.add(dirPath)
        } else {
            for (child in children) {
                val childNode = TreeNode(child, node.level + 1)
                node.addChild(childNode)
                Log.v(TAG, "  + child ${child.absolutePath}")
            }
            loadedDirectories.add(dirPath)
            emptyDirectories.remove(dirPath)
        }
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

    companion object {
        private const val TAG = "FileNodeViewBinder"
        private val loadedDirectories: MutableSet<String> =
            Collections.newSetFromMap(ConcurrentHashMap<String, Boolean>())
        private val emptyDirectories: MutableSet<String> =
            Collections.newSetFromMap(ConcurrentHashMap<String, Boolean>())

        fun resetLoadedDirectoriesCache() {
            loadedDirectories.clear()
            emptyDirectories.clear()
            Log.d(TAG, "Loaded directories cache cleared")
        }
    }

    private fun directoryHasChildren(node: TreeNode<File>, dir: File): Boolean {
        if (node.getChildren().isNotEmpty()) return true
        val path = dir.absolutePath
        if (emptyDirectories.contains(path)) return false
        if (loadedDirectories.contains(path)) return true

        val hasChildren = try {
            dir.list()?.isNotEmpty() == true
        } catch (_: Throwable) {
            false
        }
        if (!hasChildren) {
            emptyDirectories.add(path)
        }
        return hasChildren
    }
}
