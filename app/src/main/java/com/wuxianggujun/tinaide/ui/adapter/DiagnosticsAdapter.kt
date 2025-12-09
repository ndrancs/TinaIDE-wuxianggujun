package com.wuxianggujun.tinaide.ui.adapter

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.wuxianggujun.tinaide.R
import com.wuxianggujun.tinaide.databinding.ItemDiagnosticBinding
import com.wuxianggujun.tinaide.lsp.model.Diagnostic
import java.io.File

/**
 * 诊断列表适配器
 */
class DiagnosticsAdapter(
    private val onItemClick: (Diagnostic) -> Unit
) : ListAdapter<Diagnostic, DiagnosticsAdapter.DiagnosticViewHolder>(DiagnosticDiffCallback()) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): DiagnosticViewHolder {
        val binding = ItemDiagnosticBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return DiagnosticViewHolder(binding)
    }

    override fun onBindViewHolder(holder: DiagnosticViewHolder, position: Int) {
        holder.bind(getItem(position), onItemClick)
    }

    class DiagnosticViewHolder(
        private val binding: ItemDiagnosticBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(diagnostic: Diagnostic, onItemClick: (Diagnostic) -> Unit) {
            // 设置文件名（只显示文件名，不显示完整路径）
            val fileName = File(diagnostic.uri).name
            binding.tvFileName.text = fileName
            
            // 设置位置（行:列）
            val location = "${diagnostic.range.start.line + 1}:${diagnostic.range.start.character + 1}"
            binding.tvLocation.text = location
            
            // 设置消息
            binding.tvMessage.text = diagnostic.message
            
            // 设置严重程度图标和颜色
            val context = binding.root.context
            when (diagnostic.severity) {
                1 -> { // Error
                    binding.ivSeverityIcon.setImageResource(R.drawable.ic_clear)
                    binding.ivSeverityIcon.setColorFilter(
                        ContextCompat.getColor(context, R.color.lspStatusDisconnected)
                    )
                }
                2 -> { // Warning
                    binding.ivSeverityIcon.setImageResource(R.drawable.ic_clear)
                    binding.ivSeverityIcon.setColorFilter(
                        ContextCompat.getColor(context, android.R.color.holo_orange_dark)
                    )
                }
                3 -> { // Information
                    binding.ivSeverityIcon.setImageResource(R.drawable.ic_clear)
                    binding.ivSeverityIcon.setColorFilter(
                        ContextCompat.getColor(context, android.R.color.holo_blue_dark)
                    )
                }
                4 -> { // Hint
                    binding.ivSeverityIcon.setImageResource(R.drawable.ic_clear)
                    binding.ivSeverityIcon.setColorFilter(
                        ContextCompat.getColor(context, android.R.color.darker_gray)
                    )
                }
            }
            
            // 点击事件
            binding.root.setOnClickListener {
                onItemClick(diagnostic)
            }
        }
    }

    class DiagnosticDiffCallback : DiffUtil.ItemCallback<Diagnostic>() {
        override fun areItemsTheSame(oldItem: Diagnostic, newItem: Diagnostic): Boolean {
            return oldItem.uri == newItem.uri && 
                   oldItem.range == newItem.range
        }

        override fun areContentsTheSame(oldItem: Diagnostic, newItem: Diagnostic): Boolean {
            return oldItem == newItem
        }
    }
}
