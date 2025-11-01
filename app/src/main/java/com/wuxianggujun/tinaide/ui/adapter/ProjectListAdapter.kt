package com.wuxianggujun.tinaide.ui.adapter

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.wuxianggujun.tinaide.R
import java.io.File

class ProjectListAdapter(
    private val onClick: (File) -> Unit
) : RecyclerView.Adapter<ProjectListAdapter.VH>() {

    private val items = mutableListOf<File>()

    fun submitList(list: List<File>) {
        items.clear()
        items.addAll(list)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_project, parent, false)
        return VH(v)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        holder.bind(items[position])
    }

    override fun getItemCount(): Int = items.size

    inner class VH(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val tvName: TextView = itemView.findViewById(R.id.tv_name)
        private val tvPath: TextView = itemView.findViewById(R.id.tv_path)

        fun bind(dir: File) {
            tvName.text = dir.name
            tvPath.text = dir.absolutePath
            itemView.setOnClickListener { onClick(dir) }
        }
    }
}

