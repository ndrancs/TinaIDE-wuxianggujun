package com.wuxianggujun.tinaide.ui

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.wuxianggujun.tinaide.core.ServiceLocator
import com.wuxianggujun.tinaide.core.compile.CompileProjectUseCase
import com.wuxianggujun.tinaide.core.get
import com.wuxianggujun.tinaide.file.IFileManager
import com.wuxianggujun.tinaide.output.IOutputManager
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * 编译 ViewModel（按 AI 方案）
 *
 * 负责：
 * - 调用 CompileProjectUseCase 执行编译
 * - 暴露编译状态（Idle / Compiling / Success / Error）
 * - 支持取消编译
 */
class CompilerViewModel(
    private val compileUseCase: CompileProjectUseCase
) : ViewModel() {

    private val _state = MutableStateFlow<CompileState>(CompileState.Idle)
    val state: StateFlow<CompileState> = _state.asStateFlow()

    private val _progress = MutableStateFlow<CompileProjectUseCase.CompileProgress?>(null)
    val progress: StateFlow<CompileProjectUseCase.CompileProgress?> = _progress.asStateFlow()

    private val _events = MutableSharedFlow<CompileEvent>(extraBufferCapacity = 1)
    val events: SharedFlow<CompileEvent> = _events.asSharedFlow()

    private var compileJob: Job? = null

    fun compile() {
        compileJob?.cancel()
        compileJob = viewModelScope.launch {
            try {
                _state.value = CompileState.Compiling
                val result = compileUseCase.execute { p ->
                    _progress.value = p
                }
                when (result) {
                    is CompileProjectUseCase.Result.Success -> _events.emit(CompileEvent.Success(result.summary))
                    is CompileProjectUseCase.Result.Error -> _events.emit(CompileEvent.Error(result.userMessage, result.throwable))
                }
            } finally {
                _state.value = CompileState.Idle
            }
        }
    }

    fun cancelCompile() {
        compileJob?.cancel()
    }

    /**
     * ViewModel 工厂：通过 ServiceLocator 注入依赖
     */
    class Factory(
        private val appContext: Context
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            if (modelClass.isAssignableFrom(CompilerViewModel::class.java)) {
                val fileManager: IFileManager = ServiceLocator.get()
                val outputManager: IOutputManager = ServiceLocator.get()
                val useCase = CompileProjectUseCase(appContext, fileManager, outputManager)
                return CompilerViewModel(useCase) as T
            }
            throw IllegalArgumentException("Unknown ViewModel class: ${modelClass.name}")
        }
    }
}

/**
 * 编译状态（供 UI 观察）
 */
sealed class CompileState {
    object Idle : CompileState()
    object Compiling : CompileState()
}

sealed class CompileEvent {
    data class Success(val summary: String) : CompileEvent()
    data class Error(val message: String, val throwable: Throwable?) : CompileEvent()
}
