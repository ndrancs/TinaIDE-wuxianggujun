package com.wuxianggujun.tinaide.extensions

import android.content.Context
import android.widget.Toast
import com.wuxianggujun.tinaide.utils.ToastUtil

/**
 * Context 扩展函数
 */

/**
 * 显示 Toast
 */
fun Context.toast(message: String, duration: Int = Toast.LENGTH_SHORT) {
    ToastUtil.show(this, message, duration)
}

/**
 * 显示长时间 Toast
 */
fun Context.toastLong(message: String) {
    ToastUtil.showLong(this, message)
}

/**
 * 显示成功消息
 */
fun Context.toastSuccess(message: String) {
    ToastUtil.showSuccess(this, message)
}

/**
 * 显示错误消息
 */
fun Context.toastError(message: String) {
    ToastUtil.showError(this, message)
}

/**
 * 显示警告消息
 */
fun Context.toastWarning(message: String) {
    ToastUtil.showWarning(this, message)
}

/**
 * 显示信息消息
 */
fun Context.toastInfo(message: String) {
    ToastUtil.showInfo(this, message)
}
