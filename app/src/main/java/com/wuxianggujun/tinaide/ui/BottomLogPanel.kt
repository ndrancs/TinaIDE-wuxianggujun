package com.wuxianggujun.tinaide.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.wuxianggujun.tinaide.R
import com.wuxianggujun.tinaide.databinding.BottomSheetLogPanelBinding
import com.wuxianggujun.tinaide.lsp.NativeLspService
import com.wuxianggujun.tinaide.output.LogLevel

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
    private var logListener: BottomLogBuffer.LogListener? = null
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
        bindLogs()
    }

    private fun bindLogs() {
        logListener = BottomLogBuffer.LogListener { entry ->
            binding.logView.post {
                binding.logView.appendLog(entry.level, entry.message)
                updateStatusFromLog(entry)
            }
        }
        logListener?.let { listener ->
            BottomLogBuffer.replayTo(listener)
            BottomLogBuffer.addListener(listener)
        }
    }

    private fun updateStatusFromLog(entry: BottomLogBuffer.LogEntry) {
        val raw = entry.message
        val normalized = raw.lowercase()
        when {
            normalized.contains("lsp 初始化成功") ||
            normalized.contains("lsp 已连接") ||
            normalized.contains("✅ lsp") -> updateLspStatus(true, "LSP 已连接")
            normalized.contains("lsp 初始化中") ||
            normalized.contains("🚀 lsp") -> updateLspStatus(false, "LSP 初始化中...")
            normalized.contains("lsp 初始化失败") ||
            normalized.contains("lsp error") ||
            normalized.contains("lsp 异常") ||
            normalized.contains("❌ lsp") -> {
                val detail = raw.substringAfter("] ", raw)
                updateLspStatus(false, detail)
            }
            normalized.contains("lsp 已关闭") ||
            normalized.contains("🛑 lsp") -> updateLspStatus(false, "LSP 已关闭")
        }
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
        BottomLogBuffer.clear()
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
        logListener?.let { BottomLogBuffer.removeListener(it) }
        logListener = null
        initListener?.let { NativeLspService.removeInitializationListener(it) }
        healthListener?.let { NativeLspService.removeHealthListener(it) }
    }
    
    private fun Int.dpToPx(): Int {
        val density = container.context.resources.displayMetrics.density
        return (this * density).toInt()
    }
}
