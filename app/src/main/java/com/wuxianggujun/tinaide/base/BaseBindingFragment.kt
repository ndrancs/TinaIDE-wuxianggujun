package com.wuxianggujun.tinaide.base

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.addCallback
import androidx.annotation.CallSuper
import androidx.viewbinding.ViewBinding
import com.google.android.material.transition.MaterialSharedAxis

/**
 * 基于 ViewBinding 的 Fragment 基类。
 *
 * 设计目标：
 * - 统一管理 ViewBinding 的创建与销毁，避免重复模板代码；
 * - 可选启用 MaterialSharedAxis 转场动画；
 * - 可选提供统一的返回键处理逻辑（先回退 Fragment 栈，再退回桌面）。
 *
 * 注意：
 * - 默认不启用转场和返回键处理，子类按需开启，避免过度设计；
 * - 继承自 BaseFragment，复用协程与加载对话框等基础能力。
 */
abstract class BaseBindingFragment<T : ViewBinding>(
    private val inflateBinding: (LayoutInflater, ViewGroup?, Boolean) -> T
) : BaseFragment() {

    private var _binding: T? = null
    protected val binding: T
        get() = _binding
            ?: error("ViewBinding 在 onDestroyView 之后不可用，请在视图生命周期内访问 binding")

    /** 是否启用共享轴转场动画（X 轴） */
    protected open val enableSharedAxisTransitions: Boolean = false

    /** 是否启用统一的返回键处理逻辑 */
    protected open val enableDefaultBackHandler: Boolean = false

    @CallSuper
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (enableSharedAxisTransitions) {
            applySharedAxisTransitions()
        }

        if (enableDefaultBackHandler) {
            registerDefaultBackHandler()
        }
    }
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = inflateBinding(inflater, container, false)
        return binding.root
    }

    @CallSuper
    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    /**
     * 为当前 Fragment 应用 MaterialSharedAxis(X) 转场动画。
     */
    protected open fun applySharedAxisTransitions() {
        enterTransition = MaterialSharedAxis(MaterialSharedAxis.X, /* forward = */ true)
        returnTransition = MaterialSharedAxis(MaterialSharedAxis.X, /* forward = */ false)
        exitTransition = MaterialSharedAxis(MaterialSharedAxis.X, /* forward = */ true)
        reenterTransition = MaterialSharedAxis(MaterialSharedAxis.X, /* forward = */ false)
    }

    /**
     * 注册默认返回键处理：优先回退 Fragment 栈，否则退回桌面。
     */
    protected open fun registerDefaultBackHandler() {
        requireActivity().onBackPressedDispatcher.addCallback(this) {
            val manager = parentFragmentManager
            if (manager.backStackEntryCount > 0) {
                manager.popBackStack()
            } else {
                isEnabled = false
                startActivity(
                    Intent(Intent.ACTION_MAIN).apply {
                        addCategory(Intent.CATEGORY_HOME)
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                )
            }
        }
    }
}
