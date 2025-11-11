package com.wuxianggujun.tinaide.base

import android.os.Bundle
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.geyifeng.immersionbar.ktx.immersionBar
import com.wuxianggujun.tinaide.R
import com.wuxianggujun.tinaide.ui.dialog.MaterialDialogBuilder
import com.wuxianggujun.tinaide.utils.Logger
import kotlinx.coroutines.*
import kotlin.coroutines.CoroutineContext

/**
 * Activity 基类
 * 
 * 提供的功能：
 * - 统一的沉浸式状态栏
 * - 加载对话框管理
 * - 协程作用域
 * - 错误处理
 * - 日志记录
 */
abstract class BaseActivity : AppCompatActivity(), CoroutineScope {
    
    // 协程作用域
    override val coroutineContext: CoroutineContext
        get() = SupervisorJob() + Dispatchers.Main
    
    // 加载对话框
    private var progressDialog: AlertDialog? = null
    
    override fun onCreate(savedInstanceState: Bundle?) {
        // 设置主题
        setTheme(R.style.Theme_TinaIDE)
        super.onCreate(savedInstanceState)
        
        // 设置沉浸式状态栏
        setupImmersionBar()
        
        Logger.d("${this::class.simpleName} created", tag = "Lifecycle")
    }
    
    /**
     * 设置沉浸式状态栏
     * 子类可以重写自定义
     */
    protected open fun setupImmersionBar() {
        immersionBar {
            statusBarColorInt(getColor(R.color.dark_primary))
            statusBarDarkFont(false)
            navigationBarColorInt(getColor(R.color.dark_background))
            fitsSystemWindows(true)
            autoStatusBarDarkModeEnable(true)
            init()
        }
    }
    
    /**
     * 显示加载对话框
     */
    fun showLoading(message: String = "加载中...", cancelable: Boolean = false) {
        hideLoading() // 先隐藏之前的
        progressDialog = MaterialDialogBuilder.showProgress(
            context = this,
            title = "请稍候",
            message = message,
            cancelable = cancelable
        )
    }
    
    /**
     * 隐藏加载对话框
     */
    fun hideLoading() {
        progressDialog?.dismiss()
        progressDialog = null
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
                Logger.e("Coroutine error in ${this@BaseActivity::class.simpleName}", e)
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
        MaterialDialogBuilder.showError(
            context = this,
            message = error.message ?: "未知错误"
        )
    }
    
    override fun onDestroy() {
        super.onDestroy()
        // 取消所有协程
        coroutineContext.cancelChildren()
        // 隐藏加载框
        hideLoading()
        Logger.d("${this::class.simpleName} destroyed", tag = "Lifecycle")
    }
}
