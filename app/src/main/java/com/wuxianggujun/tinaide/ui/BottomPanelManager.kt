package com.wuxianggujun.tinaide.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.viewpager2.adapter.FragmentStateAdapter
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.tabs.TabLayoutMediator
import com.wuxianggujun.tinaide.databinding.BottomSheetPanelV2Binding
import com.wuxianggujun.tinaide.lsp.NativeLspService
import com.wuxianggujun.tinaide.output.LogLevel
import com.wuxianggujun.tinaide.ui.fragment.BuildLogFragment
import com.wuxianggujun.tinaide.ui.fragment.GeneralLogFragment
import com.wuxianggujun.tinaide.ui.fragment.DiagnosticsFragment
import com.wuxianggujun.tinaide.lsp.model.Diagnostic
import android.view.MotionEvent
import android.view.GestureDetector
import androidx.core.content.ContextCompat
import com.wuxianggujun.tinaide.R

/**
 * 重构后的底部面板管理器
 * 
 * 布局结构：
 * - 符号输入栏（48dp，默认显示）
 * - Tab栏 + LSP状态（48dp）
 * - ViewPager2（占据剩余空间，显示当前Tab内容）
 * 
 * ViewPager2 只占据内容区域，不占据整个屏幕
 */
class BottomPanelManager(
    private val activity: FragmentActivity,
    private val container: ViewGroup,
    private val onCompile: () -> Unit = {},
    private val onStop: () -> Unit = {},
    private val onOpenOutput: () -> Unit = {},
    private val onDiagnosticClick: (Diagnostic) -> Unit = {},
    private val onSymbolClick: (String) -> Unit = {}
) {
    
    private val binding: BottomSheetPanelV2Binding
    private val bottomSheetBehavior: BottomSheetBehavior<*>
    private val gestureDetector: GestureDetector
    
    // Tab Fragments
    private lateinit var buildLogFragment: BuildLogFragment
    private lateinit var generalLogFragment: GeneralLogFragment
    private lateinit var diagnosticsFragment: DiagnosticsFragment
    
    // 构建状态
    private var buildSucceeded: Boolean = false
    
    // LSP 监听器
    private var initListener: NativeLspService.InitializationListener? = null
    private var healthListener: NativeLspService.HealthListener? = null
    
    init {
        // 加载布局
        binding = BottomSheetPanelV2Binding.inflate(
            LayoutInflater.from(container.context),
            container,
            true
        )
        
        // 初始化 BottomSheet 行为
        bottomSheetBehavior = BottomSheetBehavior.from(binding.bottomSheet).apply {
            peekHeight = 48.dpToPx() // 符号栏高度
            isHideable = false
            isFitToContents = false
            halfExpandedRatio = 0.5f
            state = BottomSheetBehavior.STATE_COLLAPSED
        }
        
        // 初始化手势检测器
        gestureDetector = GestureDetector(container.context, SwipeGestureListener())
        
        setupSymbolBar()
        setupTabs()
        setupGestureDetector()
        setupLspStatus()
        setupBottomSheetCallback()
    }
    
    /**
     * 设置底部面板状态回调
     * 当完全展开时隐藏符号栏，收起时显示符号栏
     * 同时控制日志视图的刷新状态，避免不可见时浪费性能
     */
    private fun setupBottomSheetCallback() {
        bottomSheetBehavior.addBottomSheetCallback(object : BottomSheetBehavior.BottomSheetCallback() {
            override fun onStateChanged(bottomSheet: android.view.View, newState: Int) {
                when (newState) {
                    BottomSheetBehavior.STATE_EXPANDED -> {
                        // 完全展开时隐藏符号栏
                        binding.symbolInputBar.visibility = android.view.View.GONE
                        // 面板展开，通知日志视图可以刷新
                        notifyLogViewsVisibility(true)
                    }
                    BottomSheetBehavior.STATE_HALF_EXPANDED -> {
                        // 半展开时显示符号栏
                        binding.symbolInputBar.visibility = android.view.View.VISIBLE
                        // 面板半展开，通知日志视图可以刷新
                        notifyLogViewsVisibility(true)
                    }
                    BottomSheetBehavior.STATE_COLLAPSED -> {
                        // 收起时显示符号栏
                        binding.symbolInputBar.visibility = android.view.View.VISIBLE
                        // 面板收起，通知日志视图暂停刷新（节省性能）
                        notifyLogViewsVisibility(false)
                    }
                }
            }
            
            override fun onSlide(bottomSheet: android.view.View, slideOffset: Float) {
                // slideOffset: 0 = collapsed, 1 = expanded
                // 当滑动超过 0.9 时开始隐藏符号栏
                if (slideOffset > 0.9f) {
                    val alpha = (1f - slideOffset) * 10f // 0.9->1.0 映射到 1.0->0.0
                    binding.symbolInputBar.alpha = alpha.coerceIn(0f, 1f)
                } else {
                    binding.symbolInputBar.alpha = 1f
                }
            }
        })
    }
    
    /**
     * 通知日志视图可见性变化
     * 当面板收起时暂停日志刷新，展开时恢复
     */
    private fun notifyLogViewsVisibility(visible: Boolean) {
        if (visible) {
            // 面板展开，通知当前可见的 Fragment 刷新
            when (binding.viewPager.currentItem) {
                0 -> if (::buildLogFragment.isInitialized && buildLogFragment.isAdded) {
                    buildLogFragment.notifyVisibilityChanged(true)
                }
                1 -> if (::generalLogFragment.isInitialized && generalLogFragment.isAdded) {
                    generalLogFragment.notifyVisibilityChanged(true)
                }
            }
        } else {
            // 面板收起，通知所有 Fragment 暂停刷新
            if (::buildLogFragment.isInitialized && buildLogFragment.isAdded) {
                buildLogFragment.notifyVisibilityChanged(false)
            }
            if (::generalLogFragment.isInitialized && generalLogFragment.isAdded) {
                generalLogFragment.notifyVisibilityChanged(false)
            }
        }
    }
    
    /**
     * 设置符号输入栏
     */
    private fun setupSymbolBar() {
        binding.symbolInputBar.setOnSymbolClickListener { symbol ->
            onSymbolClick(symbol)
        }
    }
    
    /**
     * 设置 Tab 布局和 ViewPager2
     */
    private fun setupTabs() {
        // 初始化 Fragments
        buildLogFragment = BuildLogFragment.newInstance(
            onCompile = onCompile,
            onStop = onStop,
            onOpenOutput = {
                if (buildSucceeded) {
                    onOpenOutput()
                } else {
                    android.widget.Toast.makeText(
                        container.context,
                        "构建失败，无法打开输出界面",
                        android.widget.Toast.LENGTH_SHORT
                    ).show()
                }
            }
        )
        
        generalLogFragment = GeneralLogFragment.newInstance()
        
        diagnosticsFragment = DiagnosticsFragment.newInstance(onDiagnosticClick)
        
        // 设置 ViewPager2 适配器
        val adapter = BottomPanelPagerAdapter(activity)
        binding.viewPager.adapter = adapter
        
        // 禁止 ViewPager2 左右滑动，避免与内容上下滚动冲突
        binding.viewPager.isUserInputEnabled = false
        
        // 连接 TabLayout 和 ViewPager2
        TabLayoutMediator(binding.tabLayout, binding.viewPager) { tab, position ->
            tab.text = when (position) {
                0 -> "构建日志"
                1 -> "日志"
                2 -> "诊断"
                else -> ""
            }
        }.attach()
    }
    
    /**
     * 设置 LSP 状态监听
     */
    private fun setupLspStatus() {
        initListener = NativeLspService.InitializationListener { isInitialized ->
            binding.root.post {
                val message = if (isInitialized) "LSP" else "LSP"
                updateLspStatus(isInitialized, message)
            }
        }
        
        healthListener = NativeLspService.HealthListener { event ->
            binding.root.post {
                updateLspStatus(false, "LSP")
            }
        }
        
        initListener?.let { NativeLspService.addInitializationListener(it) }
        healthListener?.let { NativeLspService.addHealthListener(it) }
        
        // 初始化状态
        val initialized = NativeLspService.nativeIsInitialized()
        updateLspStatus(initialized, "LSP")
    }
    
    /**
     * 更新 LSP 状态指示器
     */
    private fun updateLspStatus(connected: Boolean, message: String) {
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
    
    /**
     * 设置手势检测器（上滑展开）
     */
    private fun setupGestureDetector() {
        binding.symbolInputBar.setOnTouchListener { _, event ->
            gestureDetector.onTouchEvent(event)
            false
        }
    }
    
    /**
     * 上滑手势监听器
     */
    private inner class SwipeGestureListener : GestureDetector.SimpleOnGestureListener() {
        private val SWIPE_THRESHOLD = 100
        private val SWIPE_VELOCITY_THRESHOLD = 100

        override fun onFling(
            e1: MotionEvent?,
            e2: MotionEvent,
            velocityX: Float,
            velocityY: Float
        ): Boolean {
            if (e1 == null) return false
            
            val diffY = e2.y - e1.y
            val diffX = e2.x - e1.x
            
            // 检测上滑手势
            if (Math.abs(diffY) > Math.abs(diffX) &&
                diffY < -SWIPE_THRESHOLD &&
                Math.abs(velocityY) > SWIPE_VELOCITY_THRESHOLD
            ) {
                // 上滑：展开面板
                expand()
                return true
            }
            
            return false
        }
    }
    
    /**
     * ViewPager2 适配器
     */
    private inner class BottomPanelPagerAdapter(activity: FragmentActivity) 
        : FragmentStateAdapter(activity) {
        
        override fun getItemCount(): Int = 3
        
        override fun createFragment(position: Int): Fragment {
            return when (position) {
                0 -> buildLogFragment
                1 -> generalLogFragment
                2 -> diagnosticsFragment
                else -> throw IllegalArgumentException("Invalid position: $position")
            }
        }
    }
    
    // ==================== 公共 API ====================
    
    /**
     * 追加构建日志
     */
    fun appendBuildLog(level: LogLevel, message: String) {
        if (::buildLogFragment.isInitialized && buildLogFragment.isAdded) {
            buildLogFragment.view?.post {
                buildLogFragment.appendLog(level, message)
            }
        }
    }
    
    /**
     * 追加通用日志
     */
    fun appendGeneralLog(level: LogLevel, message: String) {
        if (::generalLogFragment.isInitialized && generalLogFragment.isAdded) {
            generalLogFragment.view?.post {
                generalLogFragment.appendLog(level, message)
            }
        }
    }
    
    /**
     * 清空构建日志
     */
    fun clearBuildLog() {
        if (::buildLogFragment.isInitialized && buildLogFragment.isAdded) {
            buildLogFragment.view?.post {
                buildLogFragment.clearLog()
            }
        }
    }
    
    /**
     * 清空通用日志
     */
    fun clearGeneralLog() {
        if (::generalLogFragment.isInitialized && generalLogFragment.isAdded) {
            generalLogFragment.view?.post {
                generalLogFragment.clearLog()
            }
        }
    }
    
    /**
     * 设置构建状态（成功/失败）
     */
    fun setBuildSucceeded(succeeded: Boolean) {
        buildSucceeded = succeeded
        if (::buildLogFragment.isInitialized && buildLogFragment.isAdded) {
            buildLogFragment.view?.post {
                buildLogFragment.setOutputButtonEnabled(succeeded)
            }
        }
    }
    
    /**
     * 展开面板
     */
    fun expand() {
        bottomSheetBehavior.state = BottomSheetBehavior.STATE_EXPANDED
    }
    
    /**
     * 收起面板
     */
    fun collapse() {
        bottomSheetBehavior.state = BottomSheetBehavior.STATE_COLLAPSED
    }
    
    /**
     * 半展开面板
     */
    fun halfExpand() {
        bottomSheetBehavior.state = BottomSheetBehavior.STATE_HALF_EXPANDED
    }
    
    /**
     * 切换到构建日志 Tab
     */
    fun switchToBuildLog() {
        binding.viewPager.currentItem = 0
    }
    
    /**
     * 切换到通用日志 Tab
     */
    fun switchToGeneralLog() {
        binding.viewPager.currentItem = 1
    }
    
    /**
     * 切换到诊断 Tab
     */
    fun switchToDiagnostics() {
        binding.viewPager.currentItem = 2
    }
    
    /**
     * 设置诊断列表
     */
    fun setDiagnostics(diagnostics: List<Diagnostic>) {
        if (::diagnosticsFragment.isInitialized && diagnosticsFragment.isAdded) {
            diagnosticsFragment.view?.post {
                diagnosticsFragment.setDiagnostics(diagnostics)
            }
        }
    }
    
    /**
     * 添加单个诊断
     */
    fun addDiagnostic(diagnostic: Diagnostic) {
        if (::diagnosticsFragment.isInitialized && diagnosticsFragment.isAdded) {
            diagnosticsFragment.view?.post {
                diagnosticsFragment.addDiagnostic(diagnostic)
            }
        }
    }
    
    /**
     * 清空诊断
     */
    fun clearDiagnostics() {
        if (::diagnosticsFragment.isInitialized && diagnosticsFragment.isAdded) {
            diagnosticsFragment.view?.post {
                diagnosticsFragment.clearDiagnostics()
            }
        }
    }
    
    /**
     * 销毁资源
     */
    fun destroy() {
        initListener?.let { NativeLspService.removeInitializationListener(it) }
        healthListener?.let { NativeLspService.removeHealthListener(it) }
    }
    
    private fun Int.dpToPx(): Int {
        val density = container.context.resources.displayMetrics.density
        return (this * density).toInt()
    }
}
