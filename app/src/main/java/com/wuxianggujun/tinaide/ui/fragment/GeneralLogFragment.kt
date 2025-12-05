package com.wuxianggujun.tinaide.ui.fragment

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import com.wuxianggujun.tinaide.R
import com.wuxianggujun.tinaide.databinding.TabGeneralLogBinding
import com.wuxianggujun.tinaide.lsp.NativeLspService
import com.wuxianggujun.tinaide.output.LogLevel
import com.wuxianggujun.tinaide.ui.BottomLogBuffer

/**
 * 通用日志 Fragment
 * 
 * 功能：
 * - 显示 LSP 和其他系统日志
 * - 显示 LSP 连接状态
 * - 支持日志保存
 */
class GeneralLogFragment : Fragment() {

    private var _binding: TabGeneralLogBinding? = null
    private val binding get() = _binding!!
    
    private var logListener: BottomLogBuffer.LogListener? = null
    private var initListener: NativeLspService.InitializationListener? = null
    private var healthListener: NativeLspService.HealthListener? = null
    
    companion object {
        fun newInstance(): GeneralLogFragment {
            return GeneralLogFragment()
        }
    }
    
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = TabGeneralLogBinding.inflate(inflater, container, false)
        return binding.root
    }
    
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupToolbar()
        setupLspStatus()
        bindLogs()
    }
    
    private fun setupToolbar() {
        binding.btnClear.setOnClickListener {
            clearLog()
        }
        
        binding.btnSave.setOnClickListener {
            // TODO: 实现保存日志功能
            binding.generalLogView.appendLog(LogLevel.INFO, "保存日志功能开发中...")
        }
    }
    
    private fun setupLspStatus() {
        initListener = NativeLspService.InitializationListener { isInitialized ->
            binding.root.post {
                val message = if (isInitialized) "LSP 已连接" else "LSP 未连接"
                updateLspStatus(isInitialized, message)
            }
        }
        
        healthListener = NativeLspService.HealthListener { event ->
            binding.root.post {
                updateLspStatus(false, "LSP 异常: ${event.message}")
            }
        }
        
        initListener?.let { NativeLspService.addInitializationListener(it) }
        healthListener?.let { NativeLspService.addHealthListener(it) }
        
        // 初始化状态
        val initialized = NativeLspService.nativeIsInitialized()
        val message = if (initialized) "LSP 已连接" else "LSP 未连接"
        updateLspStatus(initialized, message)
    }
    
    private fun bindLogs() {
        logListener = BottomLogBuffer.LogListener { entry ->
            binding.generalLogView.post {
                binding.generalLogView.appendLog(entry.level, entry.message)
            }
        }
        logListener?.let { listener ->
            BottomLogBuffer.replayTo(listener)
            BottomLogBuffer.addListener(listener)
        }
    }
    
    /**
     * 追加日志
     */
    fun appendLog(level: LogLevel, message: String) {
        binding.generalLogView.appendLog(level, message)
    }
    
    /**
     * 清空日志
     */
    fun clearLog() {
        BottomLogBuffer.clear()
        binding.generalLogView.clearLog()
    }
    
    /**
     * 获取日志内容
     */
    fun getLogContent(): String {
        return binding.generalLogView.getLogContent()
    }
    
    /**
     * 更新 LSP 状态
     */
    fun updateLspStatus(connected: Boolean, message: String) {
        val context = binding.root.context
        val color = if (connected) {
            ContextCompat.getColor(context, R.color.lsp_status_connected)
        } else {
            ContextCompat.getColor(context, R.color.lsp_status_disconnected)
        }
        
        binding.lspStatusIndicator.backgroundTintList = 
            android.content.res.ColorStateList.valueOf(color)
        binding.tvLspStatus.text = message
    }
    
    override fun onDestroyView() {
        super.onDestroyView()
        
        logListener?.let { BottomLogBuffer.removeListener(it) }
        logListener = null
        
        initListener?.let { NativeLspService.removeInitializationListener(it) }
        healthListener?.let { NativeLspService.removeHealthListener(it) }
        
        _binding = null
    }
}
