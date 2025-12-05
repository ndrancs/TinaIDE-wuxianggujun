package com.wuxianggujun.tinaide.ui.fragment

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import com.wuxianggujun.tinaide.databinding.TabDiagnosticsBinding
import com.wuxianggujun.tinaide.lsp.model.Diagnostic
import com.wuxianggujun.tinaide.ui.adapter.DiagnosticsAdapter

/**
 * 诊断 Fragment
 * 
 * 功能：
 * - 显示 Clangd LSP 诊断信息（错误、警告、提示）
 * - 点击诊断项跳转到对应代码位置
 * - 统计错误和警告数量
 */
class DiagnosticsFragment : Fragment() {

    private var _binding: TabDiagnosticsBinding? = null
    private val binding get() = _binding!!
    
    private lateinit var diagnosticsAdapter: DiagnosticsAdapter
    private var onDiagnosticClick: ((Diagnostic) -> Unit)? = null
    
    companion object {
        fun newInstance(onDiagnosticClick: (Diagnostic) -> Unit): DiagnosticsFragment {
            return DiagnosticsFragment().apply {
                this.onDiagnosticClick = onDiagnosticClick
            }
        }
    }
    
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = TabDiagnosticsBinding.inflate(inflater, container, false)
        return binding.root
    }
    
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupRecyclerView()
        setupToolbar()
    }
    
    private fun setupRecyclerView() {
        diagnosticsAdapter = DiagnosticsAdapter { diagnostic ->
            onDiagnosticClick?.invoke(diagnostic)
        }
        
        binding.diagnosticList.apply {
            layoutManager = LinearLayoutManager(requireContext())
            adapter = diagnosticsAdapter
        }
    }
    
    private fun setupToolbar() {
        binding.btnClear.setOnClickListener {
            clearDiagnostics()
        }
    }
    
    /**
     * 设置诊断列表
     */
    fun setDiagnostics(diagnostics: List<Diagnostic>) {
        diagnosticsAdapter.submitList(diagnostics)
        updateStats(diagnostics)
    }
    
    /**
     * 添加诊断
     */
    fun addDiagnostic(diagnostic: Diagnostic) {
        val currentList = diagnosticsAdapter.currentList.toMutableList()
        currentList.add(diagnostic)
        diagnosticsAdapter.submitList(currentList)
        updateStats(currentList)
    }
    
    /**
     * 清空诊断
     */
    fun clearDiagnostics() {
        diagnosticsAdapter.submitList(emptyList())
        updateStats(emptyList())
    }
    
    /**
     * 更新统计信息
     */
    private fun updateStats(diagnostics: List<Diagnostic>) {
        val errorCount = diagnostics.count { it.severity == 1 } // Error
        val warningCount = diagnostics.count { it.severity == 2 } // Warning
        binding.tvDiagnosticStats.text = "错误: $errorCount, 警告: $warningCount"
    }
    
    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
