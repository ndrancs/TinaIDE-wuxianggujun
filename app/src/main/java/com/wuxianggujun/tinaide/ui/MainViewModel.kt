package com.wuxianggujun.tinaide.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.wuxianggujun.tinaide.core.i18n.Strings
import com.wuxianggujun.tinaide.core.i18n.strOr
import com.wuxianggujun.tinaide.file.IProjectContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * 主界面 ViewModel
 *
 * 职责：
 * - 管理主界面的全局状态（项目名称、编译状态）
 * - 加载和更新项目信息
 *
 * 设计原则：
 * - 使用 StateFlow 暴露状态给 Compose
 * - 简单的状态管理，不涉及复杂业务逻辑
 */
class MainViewModel(
    application: Application,
    private val projectContext: IProjectContext,
) : AndroidViewModel(application) {

    // ============ UI 状态 ============

    private val _projectName = MutableStateFlow(Strings.project_not_opened.strOr(getApplication()))
    val projectName: StateFlow<String> = _projectName.asStateFlow()

    private val _isCompiling = MutableStateFlow(false)
    val isCompiling: StateFlow<Boolean> = _isCompiling.asStateFlow()

    // ============ 初始化 ============

    init {
        // 加载项目名称
        loadProjectName()
    }

    // ============ 公共方法 ============

    /**
     * 加载项目名称
     */
    fun loadProjectName() {
        viewModelScope.launch {
            try {
                val project = projectContext.getCurrentProject()
                _projectName.value = project?.name ?: Strings.project_not_opened.strOr(getApplication())
            } catch (e: Exception) {
                _projectName.value = Strings.project_not_opened.strOr(getApplication())
            }
        }
    }

    /**
     * 设置编译状态
     */
    fun setCompiling(compiling: Boolean) {
        _isCompiling.value = compiling
    }
}
