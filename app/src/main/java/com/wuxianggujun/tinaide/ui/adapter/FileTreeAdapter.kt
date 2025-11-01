package com.wuxianggujun.tinaide.ui.adapter

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.wuxianggujun.tinaide.R
import java.io.File

/**
 * 文件树适配器
 */
class FileTreeAdapter(
    private val onFileClick: (File) -> Unit,
    private val onFileLongClick: (File) -> Boolean
) : RecyclerView.Adapter<FileTreeAdapter.FileViewHolder>() {
    
    private val items = mutableListOf<FileTreeItem>()
    private val expandedDirs = mutableSetOf<String>()
    
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): FileViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_file_tree, parent, false)
        return FileViewHolder(view)
    }
    
    override fun onBindViewHolder(holder: FileViewHolder, position: Int) {
        val item = items[position]
        holder.bind(item)
    }
    
    override fun getItemCount(): Int = items.size
    
    /**
     * 设置文件列表
     */
    fun setFiles(files: List<File>) {
        items.clear()
        files.sortedWith(compareBy({ !it.isDirectory }, { it.name })).forEach { file ->
            items.add(FileTreeItem(file, 0))
        }
        notifyDataSetChanged()
    }
    
    /**
     * 切换目录展开/折叠状态
     */
    fun toggleDirectory(dir: File) {
        if (!dir.isDirectory) return
        
        val path = dir.absolutePath
        if (expandedDirs.contains(path)) {
            // 折叠目录
            collapseDirectory(dir)
            expandedDirs.remove(path)
        } else {
            // 展开目录
            expandDirectory(dir)
            expandedDirs.add(path)
        }
    }
    
    /**
     * 展开目录
     */
    private fun expandDirectory(dir: File) {
        val index = items.indexOfFirst { it.file.absolutePath == dir.absolutePath }
        if (index == -1) return
        
        val level = items[index].level
        val children = dir.listFiles()?.sortedWith(compareBy({ !it.isDirectory }, { it.name })) ?: return
        
        val childItems = children.map { FileTreeItem(it, level + 1) }
        items.addAll(index + 1, childItems)
        notifyItemRangeInserted(index + 1, childItems.size)
    }
    
    /**
     * 折叠目录
     */
    private fun collapseDirectory(dir: File) {
        val index = items.indexOfFirst { it.file.absolutePath == dir.absolutePath }
        if (index == -1) return
        
        val level = items[index].level
        var count = 0
        
        // 计算要移除的子项数量
        for (i in index + 1 until items.size) {
            if (items[i].level <= level) break
            count++
        }
        
        if (count > 0) {
            // 移除子项
            repeat(count) {
                items.removeAt(index + 1)
            }
            notifyItemRangeRemoved(index + 1, count)
        }
    }
    
    /**
     * ViewHolder
     */
    inner class FileViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val iconView: ImageView = itemView.findViewById(R.id.file_icon)
        private val nameView: TextView = itemView.findViewById(R.id.file_name)
        private val expandIcon: ImageView = itemView.findViewById(R.id.expand_icon)
        
        fun bind(item: FileTreeItem) {
            val file = item.file
            
            // 设置缩进
            val padding = item.level * 24
            itemView.setPadding(padding, itemView.paddingTop, itemView.paddingRight, itemView.paddingBottom)
            
            // 设置文件名
            nameView.text = file.name
            
            // 设置图标
            if (file.isDirectory) {
                iconView.setImageResource(R.drawable.ic_folder)
                expandIcon.visibility = View.VISIBLE
                
                // 设置展开/折叠图标
                val isExpanded = expandedDirs.contains(file.absolutePath)
                expandIcon.rotation = if (isExpanded) 90f else 0f
            } else {
                iconView.setImageResource(getFileIcon(file))
                expandIcon.visibility = View.GONE
            }
            
            // 点击事件
            itemView.setOnClickListener {
                onFileClick(file)
            }
            
            // 长按事件
            itemView.setOnLongClickListener {
                onFileLongClick(file)
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
    }
    
    /**
     * 文件树项
     */
    data class FileTreeItem(
        val file: File,
        val level: Int
    )
}
