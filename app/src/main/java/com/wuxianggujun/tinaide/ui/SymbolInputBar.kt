package com.wuxianggujun.tinaide.ui

import android.content.Context
import android.util.AttributeSet
import android.view.LayoutInflater
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import com.google.android.material.button.MaterialButton
import com.wuxianggujun.tinaide.R

/**
 * 符号输入栏组件
 * 
 * 功能：
 * - 提供常用编程符号快捷输入
 * - 支持水平滚动查看更多符号
 * - 可自定义符号列表
 * - 支持符号点击回调
 */
class SymbolInputBar @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : HorizontalScrollView(context, attrs, defStyleAttr) {

    private val symbolContainer: LinearLayout
    private var onSymbolClickListener: ((String) -> Unit)? = null

    // 默认符号列表（编程常用符号）
    private val defaultSymbols = listOf(
        "{", "}", "(", ")", "[", "]",
        ";", ":", "\"", "'", "<", ">",
        "=", "+", "-", "*", "/", "%",
        "&", "|", "^", "~", "!", "?",
        ".", ",", "#", "@", "\\", "$"
    )

    init {
        // 禁用水平滚动条
        isHorizontalScrollBarEnabled = false
        
        // 创建符号容器
        symbolContainer = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            layoutParams = LayoutParams(
                LayoutParams.WRAP_CONTENT,
                LayoutParams.MATCH_PARENT
            )
            setPadding(
                dpToPx(4),
                dpToPx(2),
                dpToPx(4),
                dpToPx(2)
            )
        }
        
        addView(symbolContainer)
        
        // 加载默认符号
        setSymbols(defaultSymbols)
    }

    /**
     * 设置符号列表
     */
    fun setSymbols(symbols: List<String>) {
        symbolContainer.removeAllViews()
        
        symbols.forEach { symbol ->
            val button = MaterialButton(
                context,
                null,
                com.google.android.material.R.attr.borderlessButtonStyle
            ).apply {
                text = symbol
                textSize = 16f
                minWidth = 0
                minimumWidth = dpToPx(36)
                minHeight = 0
                minimumHeight = dpToPx(36)
                insetTop = 0
                insetBottom = 0
                setPadding(dpToPx(4), 0, dpToPx(4), 0)
                
                // 去掉圆角，设置为小圆角矩形
                cornerRadius = dpToPx(4)
                
                // 设置文字颜色
                setTextColor(context.getColor(android.R.color.white))
                
                // 设置点击事件
                setOnClickListener {
                    onSymbolClickListener?.invoke(symbol)
                }
            }
            
            val layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                marginEnd = dpToPx(2)
                gravity = android.view.Gravity.CENTER_VERTICAL
            }
            
            symbolContainer.addView(button, layoutParams)
        }
    }

    /**
     * 设置符号点击监听器
     */
    fun setOnSymbolClickListener(listener: (String) -> Unit) {
        onSymbolClickListener = listener
    }

    /**
     * dp 转 px
     */
    private fun dpToPx(dp: Int): Int {
        val density = context.resources.displayMetrics.density
        return (dp * density).toInt()
    }
}
