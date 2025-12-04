package com.wuxianggujun.tinaide.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.wuxianggujun.tinaide.R
import com.wuxianggujun.tinaide.core.ServiceLocator
import com.wuxianggujun.tinaide.databinding.BottomSheetLogPanelBinding
import com.wuxianggujun.tinaide.lsp.NativeLspService
import com.wuxianggujun.tinaide.output.IOutputManager
import com.wuxianggujun.tinaide.output.LogLevel
import com.wuxianggujun.tinaide.output.OutputManager

/**
 * 底部日志面板管理器
 * 
 * 功能：
 * - 可拖动展开/收起
 * - 显示编译日志和 LSP 调试日志
 * - 工具栏快捷操作
 * - LSP 状态指示
 */
class BottomLogPanel(
    private val container: ViewGroup,
    private val onCompile: () -> Unit = {},
    private val onStop: () -> Unit = {}
) {
    
    private val binding: BottomSheetLogPanelBinding
    private val bottomSheetBehavior: BottomSheetBehavior<*>
    private val outputManager: IOutputManager? = try {
        ServiceLocator.get(IOutputManager::class.java)
    } catch (_: Throwable) {
        null
    }
    private var outputListener: IOutputManager.OutputListener? = null
    private var initListener: NativeLspService.InitializationListener? = null
    private var healthListener: NativeLspService.HealthListener? = null
    
    init {
        // 加载布局
        binding = BottomSheetLogPanelBinding.inflate(
            LayoutInflater.from(container.context),
            container,
            true
        )
        
        // 初始化 BottomSheet 行为
        bottomSheetBehavior = BottomSheetBehavior.from(binding.bottomSheet).apply {
            peekHeight = 56.dpToPx()
            isHideable = false
            isFitToContents = false
            halfExpandedRatio = 0.5f
            state = BottomSheetBehavior.STATE_COLLAPSED
        }
        
        setupToolbar()
        setupLspStatus()
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
        bindOutput()
    }

    private fun bindOutput() {
        outputListener = object : IOutputManager.OutputListener {
            override fun onOutputAppended(text: String) {
                binding.logView.post {
                    binding.logView.appendLog(text)
                }
            }

            override fun onOutputCleared() {
                binding.logView.post {
                    binding.logView.clearLog()
                }
            }
        }

        outputManager?.let { manager ->
            val existing = manager.getOutput()
            if (existing.isNotEmpty()) {
                binding.logView.setText(existing)
            }
            outputListener?.let { manager.addOutputListener(it) }
        }

        OutputManager.setLogView(binding.logView)
    }
    
    private fun setupToolbar() {
        binding.btnCompile.setOnClickListener {
            onCompile()
            expand()
        }
        
        binding.btnStop.setOnClickListener {
            onStop()
        }
        
        binding.btnClearLog.setOnClickListener {
            binding.logView.clearLog()
        }
        
        binding.btnSaveLog.setOnClickListener {
            // TODO: 实现保存日志功能
            binding.logView.appendLog(LogLevel.INFO, "保存日志功能开发中...")
        }
        
        // 点击拖动手柄切换展开/收起
        binding.dragHandle.setOnClickListener {
            toggle()
        }
    }
    
    private fun setupLspStatus() {
        val initialized = NativeLspService.nativeIsInitialized()
        val message = if (initialized) "LSP 已连接" else "LSP 未连接"
        updateLspStatus(initialized, message)
    }
    
    fun updateLspStatus(connected: Boolean, message: String) {
        val context = binding.root.context
        val color = if (connected) {
            ContextCompat.getColor(context, R.color.lsp_status_connected)
        } else {
            ContextCompat.getColor(context, R.color.lsp_status_disconnected)
        }
        
        binding.lspStatusIndicator.backgroundTintList = android.content.res.ColorStateList.valueOf(color)
        binding.tvLspStatus.text = message
    }
    
    fun appendLog(level: LogLevel, message: String) {
        binding.logView.appendLog(level, message)
    }
    
    fun clearLog() {
        binding.logView.clearLog()
    }
    
    fun expand() {
        bottomSheetBehavior.state = BottomSheetBehavior.STATE_EXPANDED
    }
    
    fun collapse() {
        bottomSheetBehavior.state = BottomSheetBehavior.STATE_COLLAPSED
    }
    
    fun halfExpand() {
        bottomSheetBehavior.state = BottomSheetBehavior.STATE_HALF_EXPANDED
    }
    
    fun toggle() {
        when (bottomSheetBehavior.state) {
            BottomSheetBehavior.STATE_COLLAPSED -> halfExpand()
            BottomSheetBehavior.STATE_HALF_EXPANDED -> expand()
            BottomSheetBehavior.STATE_EXPANDED -> collapse()
            else -> collapse()
        }
    }
    
    fun isExpanded(): Boolean {
        return bottomSheetBehavior.state == BottomSheetBehavior.STATE_EXPANDED
    }

    fun destroy() {
        outputListener?.let { listener ->
            outputManager?.removeOutputListener(listener)
        }
        initListener?.let { NativeLspService.removeInitializationListener(it) }
        healthListener?.let { NativeLspService.removeHealthListener(it) }
        OutputManager.setLogView(null)
    }
    
    private fun Int.dpToPx(): Int {
        val density = container.context.resources.displayMetrics.density
        return (this * density).toInt()
    }
}
