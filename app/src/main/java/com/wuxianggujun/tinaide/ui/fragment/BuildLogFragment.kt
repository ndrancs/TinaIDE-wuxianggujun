package com.wuxianggujun.tinaide.ui.fragment

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import com.wuxianggujun.tinaide.databinding.TabBuildLogBinding
import com.wuxianggujun.tinaide.output.LogLevel

/**
 * 构建日志 Fragment
 * 
 * 功能：
 * - 显示 C++ 编译过程的日志
 * - 提供编译/停止按钮
 * - 构建成功后才能打开输出界面
 */
class BuildLogFragment : Fragment() {

    private var _binding: TabBuildLogBinding? = null
    private val binding get() = _binding!!
    
    private var onCompile: (() -> Unit)? = null
    private var onStop: (() -> Unit)? = null
    private var onOpenOutput: (() -> Unit)? = null
    
    companion object {
        fun newInstance(
            onCompile: () -> Unit,
            onStop: () -> Unit,
            onOpenOutput: () -> Unit
        ): BuildLogFragment {
            return BuildLogFragment().apply {
                this.onCompile = onCompile
                this.onStop = onStop
                this.onOpenOutput = onOpenOutput
            }
        }
    }
    
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = TabBuildLogBinding.inflate(inflater, container, false)
        return binding.root
    }
    
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupToolbar()
    }
    
    private fun setupToolbar() {
        binding.btnCompile.setOnClickListener {
            onCompile?.invoke()
        }
        
        binding.btnStop.setOnClickListener {
            onStop?.invoke()
        }
        
        binding.btnClear.setOnClickListener {
            clearLog()
        }
        
        binding.btnOpenOutput.setOnClickListener {
            onOpenOutput?.invoke()
        }
    }
    
    /**
     * 追加日志
     */
    fun appendLog(level: LogLevel, message: String) {
        binding.buildLogView.appendLog(level, message)
    }
    
    /**
     * 清空日志
     */
    fun clearLog() {
        binding.buildLogView.clearLog()
    }
    
    /**
     * 获取日志内容
     */
    fun getLogContent(): String {
        return binding.buildLogView.getLogContent()
    }
    
    /**
     * 设置"打开输出"按钮的启用状态
     */
    fun setOutputButtonEnabled(enabled: Boolean) {
        binding.btnOpenOutput.isEnabled = enabled
    }
    
    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
