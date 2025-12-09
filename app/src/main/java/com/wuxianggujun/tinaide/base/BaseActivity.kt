package com.wuxianggujun.tinaide.base

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.viewbinding.ViewBinding
import com.gyf.immersionbar.ktx.immersionBar
import com.wuxianggujun.tinaide.R

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
abstract class BaseActivity<VB : ViewBinding>(
    private val inflateBinding: (LayoutInflater) -> VB
) : AppCompatActivity(), CoroutineScope {
    
    // 协程作用域
    override val coroutineContext: CoroutineContext
        get() = SupervisorJob() + Dispatchers.Main
    
    // ViewBinding
    private var _binding: VB? = null
    protected val binding: VB
        get() = _binding
            ?: error("ViewBinding 在 onDestroy 之后不可用，请在 Activity 生命周期内访问 binding")
    
    // 加载对话框
    private var progressDialog: AlertDialog? = null
    
    override fun onCreate(savedInstanceState: Bundle?) {
        // 设置主题
        setTheme(R.style.Theme_TinaIDE)
        super.onCreate(savedInstanceState)

        _binding = inflateBinding(layoutInflater)
        setContentView(binding.root)

        // 设置沉浸式状态栏
        setupImmersionBar()

        Logger.d("${this::class.simpleName} created", tag = "Lifecycle")
    }

    /**
     * setContentView 之后调用，自动为根视图设置 fitsSystemWindows
     */
    override fun setContentView(layoutResID: Int) {
        super.setContentView(layoutResID)
        setupFitsSystemWindows()
    }

    override fun setContentView(view: View?) {
        super.setContentView(view)
        setupFitsSystemWindows()
    }

    /**
     * 设置沉浸式状态栏
     * 子类可以重写自定义
     *
     * 注意：
     * 1. 根据当前主题模式（浅色/深色）自动调整状态栏颜色和图标颜色
     * 2. fitsSystemWindows 由 setupFitsSystemWindows() 统一处理
     */
    protected open fun setupImmersionBar() {
        // 检测当前是否为深色模式
        val isDarkMode = (resources.configuration.uiMode and
                android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
                android.content.res.Configuration.UI_MODE_NIGHT_YES

        immersionBar {
            // 显式设置状态栏颜色，使用主题定义的颜色
            statusBarColorInt(androidx.core.content.ContextCompat.getColor(this@BaseActivity, R.color.statusBarColor))
            // 浅色模式使用深色图标，深色模式使用浅色图标
            statusBarDarkFont(!isDarkMode)
            // 导航栏颜色
            navigationBarColorInt(androidx.core.content.ContextCompat.getColor(this@BaseActivity, R.color.navigationBarColor))
            // 导航栏图标颜色同样适配
            navigationBarDarkIcon(!isDarkMode)
            // fitsSystemWindows 在 setupFitsSystemWindows() 中统一处理
            fitsSystemWindows(false)
        }
    }

    /**
     * 自动为根视图设置 fitsSystemWindows
     * 避免在每个 XML 布局中重复添加
     *
     * 注意：如果子视图或其子节点已经设置了 fitsSystemWindows，则跳过自动设置
     */
    private fun setupFitsSystemWindows() {
        window?.decorView?.findViewById<android.view.View>(android.R.id.content)?.let { content ->
            if (content is android.view.ViewGroup && content.childCount > 0) {
                val rootView = content.getChildAt(0)

                // 检查根视图或其直接子视图是否已经设置了 fitsSystemWindows
                if (rootView != null && !hasFitsSystemWindows(rootView)) {
                    rootView.fitsSystemWindows = true
                }
            }
        }
    }

    /**
     * 检查视图或其直接子视图是否已设置 fitsSystemWindows
     */
    private fun hasFitsSystemWindows(view: android.view.View): Boolean {
        // 检查当前视图
        if (view.fitsSystemWindows) {
            return true
        }

        // 检查直接子视图（一层深度）
        if (view is android.view.ViewGroup) {
            for (i in 0 until view.childCount) {
                if (view.getChildAt(i)?.fitsSystemWindows == true) {
                    return true
                }
            }
        }

        return false
    }
    
    /**
     * 显示加载对话框
     */
    fun showLoading(message: String = "加载中...", cancelable: Boolean = false) {
        hideLoading() // 先隐藏之前的
        progressDialog = com.google.android.material.dialog.MaterialAlertDialogBuilder(
            this,
            R.style.ThemeOverlay_App_MaterialAlertDialog
        )
            .setTitle("请稍候")
            .setMessage(message)
            .setCancelable(cancelable)
            .create()
        progressDialog?.show()
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
    open fun handleDefaultError(error: Throwable) {
        hideLoading()
        val dialog = com.wuxianggujun.tinaide.ui.dialog.InfoDialog.newInstance(
            title = "错误",
            message = error.message ?: "未知错误"
        )
        dialog.show(supportFragmentManager, "error_dialog")
    }
    
    override fun onDestroy() {
        super.onDestroy()
        // 取消所有协程
        coroutineContext.cancelChildren()
        // 隐藏加载框
        hideLoading()
        _binding = null
        Logger.d("${this::class.simpleName} destroyed", tag = "Lifecycle")
    }
}
