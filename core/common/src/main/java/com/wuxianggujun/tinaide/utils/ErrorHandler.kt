package com.wuxianggujun.tinaide.utils

import android.content.Context
import com.wuxianggujun.tinaide.core.i18n.Strings
import com.wuxianggujun.tinaide.core.i18n.strOr
import java.io.IOException
import timber.log.Timber

/**
 * 统一错误处理工具
 */
object ErrorHandler {

    private const val TAG = "ErrorHandler"

    /**
     * 处理错误并显示 Toast
     */
    fun handleWithToast(
        context: Context,
        error: Throwable,
        prefix: String = ""
    ) {
        val message = getErrorMessage(context, error)
        val fullMessage = if (prefix.isNotEmpty()) "$prefix: $message" else message

        // 记录日志
        Timber.tag(TAG).e(error, "Error: %s", fullMessage)

        // 显示 Toast
        ToastUtil.showError(context, fullMessage)
    }

    /**
     * 只记录错误日志，不显示
     */
    fun log(error: Throwable, tag: String = TAG) {
        Timber.tag(tag).e(error, "Error: %s", error.message)
    }

    /**
     * 获取友好的错误消息
     */
    private fun getErrorMessage(context: Context, error: Throwable): String {
        val unknownError = Strings.error_unknown_message.strOr(context)
        return when (error) {
            is IOException -> Strings.error_io_failed.strOr(context, error.message ?: unknownError)
            is SecurityException -> Strings.error_security.strOr(context, error.message ?: unknownError)
            is IllegalArgumentException -> Strings.error_illegal_argument.strOr(context, error.message ?: unknownError)
            is IllegalStateException -> Strings.error_illegal_state.strOr(context, error.message ?: unknownError)
            is NullPointerException -> Strings.error_null_pointer.strOr(context)
            is OutOfMemoryError -> Strings.error_out_of_memory.strOr(context)
            is Exception -> error.message ?: unknownError
            else -> Strings.error_unknown_generic.strOr(context)
        }
    }
}

/**
 * Context 扩展函数 - 使用 Toast 处理错误
 */
fun Context.handleErrorWithToast(error: Throwable, prefix: String = "") {
    ErrorHandler.handleWithToast(this, error, prefix)
}
