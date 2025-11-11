package com.wuxianggujun.tinaide.base

import android.os.Bundle
import android.view.View
import androidx.fragment.app.Fragment
import com.wuxianggujun.tinaide.utils.Logger
import kotlinx.coroutines.*
import kotlin.coroutines.CoroutineContext

/**
 * Fragment 基类
 * 
 * 提供的功能：
 * - 协程作用域
 * - 加载对话框管理（委托给 Activity）
 * - 错误处理
 * - 日志记录
 */
abstract class BaseFragment : Fragment(), CoroutineScope {
    
    // 协程作用域
    override val coroutineContext: CoroutineContext
        get() = SupervisorJob() + Dispatchers.Main
    
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        Logger.d("${this::class.simpleName} view created", tag = "Lifecycle")
    }
    
    /**
     * 显示加载对话框（委托给 Activity）
     */
    fun showLoading(message: String = "加载中...", cancelable: Boolean = false) {
        (activity as? BaseActivity)?.showLoading(message, cancelable)
    }
    
    /**
     * 隐藏加载对话框（委托给 Activity）
     */
    fun hideLoading() {
        (activity as? BaseActivity)?.hideLoading()
    }
    
    /**
     * 安全执行协程
     * 自动处理异常
     */
    fun launchSafely(
        onError: ((Throwable) -> Unit)? = null,
        block: suspend CoroutineScope.() -> Unit
    ): Job {
        return launch {
            try {
                block()
            } catch (e: CancellationException) {
                // 协程被取消，不处理
                throw e
            } catch (e: Exception) {
                Logger.e("Coroutine error in ${this@BaseFragment::class.simpleName}", e)
                onError?.invoke(e) ?: handleDefaultError(e)
            }
        }
    }
    
    /**
     * 在 IO 线程执行
     */
    suspend fun <T> withIO(block: suspend CoroutineScope.() -> T): T {
        return withContext(Dispatchers.IO, block)
    }
    
    /**
     * 在主线程执行
     */
    suspend fun <T> withMain(block: suspend CoroutineScope.() -> T): T {
        return withContext(Dispatchers.Main, block)
    }
    
    /**
     * 默认错误处理
     */
    protected open fun handleDefaultError(error: Throwable) {
        hideLoading()
        activity?.let { act ->
            (act as? BaseActivity)?.handleDefaultError(error)
        }
    }
    
    override fun onDestroyView() {
        super.onDestroyView()
        // 取消所有协程
        coroutineContext.cancelChildren()
        // 隐藏加载框
        hideLoading()
        Logger.d("${this::class.simpleName} view destroyed", tag = "Lifecycle")
    }
}
