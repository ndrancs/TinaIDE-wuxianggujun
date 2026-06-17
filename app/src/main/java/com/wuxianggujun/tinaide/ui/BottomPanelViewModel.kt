package com.wuxianggujun.tinaide.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import com.wuxianggujun.tinaide.core.compile.BuildLogEntry
import com.wuxianggujun.tinaide.core.compile.BuildLogLevel
import com.wuxianggujun.tinaide.core.lsp.Diagnostic
import com.wuxianggujun.tinaide.output.IOutputManager
import com.wuxianggujun.tinaide.ui.compose.components.BottomPanelTab
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * 底部面板 ViewModel
 *
 * 职责：
 * - 管理构建日志、诊断信息
 * - 管理底部面板选中标签
 * - 监听 OutputManager
 *
 * 重构说明：
 * - 移除 terminalFullScreen 状态（高度控制由 BottomPanelDragState 统一管理）
 * - 简化状态管理，仅保留必要的业务状态
 */
class BottomPanelViewModel(
    application: Application,
    private val outputManager: IOutputManager,
) : AndroidViewModel(application) {

    // ============ UI 状态 ============

    private val _buildLogs = MutableStateFlow<List<BuildLogEntry>>(emptyList())
    val buildLogs: StateFlow<List<BuildLogEntry>> = _buildLogs.asStateFlow()

    private val _buildLogCount = MutableStateFlow(0)
    val buildLogCount: StateFlow<Int> = _buildLogCount.asStateFlow()

    private val _runOutputLogs = MutableStateFlow<List<BuildLogEntry>>(emptyList())
    val runOutputLogs: StateFlow<List<BuildLogEntry>> = _runOutputLogs.asStateFlow()

    private val _runOutputCount = MutableStateFlow(0)
    val runOutputCount: StateFlow<Int> = _runOutputCount.asStateFlow()

    private val _diagnostics = MutableStateFlow<List<Diagnostic>>(emptyList())
    val diagnostics: StateFlow<List<Diagnostic>> = _diagnostics.asStateFlow()

    private val _selectedBottomTab = MutableStateFlow(BottomPanelTab.BUILD_LOG)
    val selectedBottomTab: StateFlow<BottomPanelTab> = _selectedBottomTab.asStateFlow()

    // ============ 监听器 ============

    /**
     * 构建日志监听器
     *
     * 逻辑：
     * - 过滤 BUILD 通道
     * - 移除末尾换行符
     * - 检测日志级别（ERROR/WARN/SUCCESS/INFO/DEBUG）
     * - 添加到构建日志列表
     */
    private val buildLogListener = object : IOutputManager.OutputListener {
        override fun onOutputAppended(text: String, channel: IOutputManager.OutputChannel) {
            when (channel) {
                IOutputManager.OutputChannel.BUILD -> {
                    val entries = parseOutputEntries(text)
                    if (entries.isNotEmpty()) {
                        _buildLogs.update { currentLogs ->
                            val updatedLogs = currentLogs + entries
                            _buildLogCount.value = updatedLogs.size
                            updatedLogs
                        }
                    }
                }
                IOutputManager.OutputChannel.RUN -> {
                    val entries = parseOutputEntries(text)
                    if (entries.isNotEmpty()) {
                        _runOutputLogs.update { currentLogs ->
                            val updatedLogs = currentLogs + entries
                            _runOutputCount.value = updatedLogs.size
                            updatedLogs
                        }
                    }
                }
            }
        }

        override fun onOutputCleared(channel: IOutputManager.OutputChannel) {
            when (channel) {
                IOutputManager.OutputChannel.BUILD -> {
                    _buildLogs.value = emptyList()
                    _buildLogCount.value = 0
                }
                IOutputManager.OutputChannel.RUN -> {
                    _runOutputLogs.value = emptyList()
                    _runOutputCount.value = 0
                }
            }
        }
    }

    // ============ 初始化 ============

    init {
        restoreBuildLogsFromBuffer()
        restoreRunOutputFromBuffer()
        // 注册监听器
        outputManager.addOutputListener(buildLogListener)
    }

    // ============ 公共方法 ============

    /**
     * 设置选中的底部面板标签
     */
    fun setSelectedTab(tab: BottomPanelTab) {
        _selectedBottomTab.value = tab
    }

    /**
     * 清空构建日志
     */
    fun clearBuildLogs() {
        outputManager.clearOutput(IOutputManager.OutputChannel.BUILD)
    }

    /**
     * 清空运行输出
     */
    fun clearRunOutput() {
        outputManager.clearOutput(IOutputManager.OutputChannel.RUN)
    }

    /**
     * 替换指定文件的诊断信息
     *
     * 逻辑：
     * - 移除旧的诊断
     * - 添加新的诊断
     * - 按文件名→行号→列号→严重性→消息排序
     */
    fun replaceDiagnosticsForFile(fileUri: String, fileDiagnostics: List<Diagnostic>) {
        val currentDiagnostics = _diagnostics.value.toMutableList()

        // 移除该文件的旧诊断
        currentDiagnostics.removeAll { it.fileUri == fileUri }

        // 添加新诊断
        currentDiagnostics.addAll(fileDiagnostics)

        // 排序
        currentDiagnostics.sortWith(
            compareBy<Diagnostic>(
                { it.fileName },
                { it.line },
                { it.column },
                { it.severity.ordinal },
                { it.message }
            )
        )

        _diagnostics.value = currentDiagnostics
    }

    // ============ 生命周期管理 ============

    override fun onCleared() {
        super.onCleared()

        // 清理监听器
        outputManager.removeOutputListener(buildLogListener)
    }

    private fun restoreBuildLogsFromBuffer() {
        val buffered = outputManager.getOutput(IOutputManager.OutputChannel.BUILD)
        if (buffered.isBlank()) return
        val entries = parseOutputEntries(buffered)
        _buildLogs.value = entries
        _buildLogCount.value = entries.size
    }

    private fun restoreRunOutputFromBuffer() {
        val buffered = outputManager.getOutput(IOutputManager.OutputChannel.RUN)
        if (buffered.isBlank()) return
        val entries = parseOutputEntries(buffered)
        _runOutputLogs.value = entries
        _runOutputCount.value = entries.size
    }

    private fun parseOutputEntries(text: String): List<BuildLogEntry> = text
        .lineSequence()
        .map { it.trimEnd('\r') }
        .filter { it.isNotBlank() }
        .map { line ->
            val level = BuildLogLevel.detect(line)
            BuildLogEntry.create(level, line)
        }
        .toList()
}
