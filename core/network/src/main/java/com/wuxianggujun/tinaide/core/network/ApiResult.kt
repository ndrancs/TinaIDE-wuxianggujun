package com.wuxianggujun.tinaide.core.network

/**
 * 统一的 API 结果封装
 *
 * 用于所有 API 客户端的返回值类型，提供一致的错误处理接口
 */
sealed class ApiResult<out T> {
    /**
     * 成功结果
     */
    data class Success<T>(val data: T) : ApiResult<T>()

    /**
     * 错误结果（HTTP 错误或业务错误）
     */
    data class Error(val code: Int, val message: String) : ApiResult<Nothing>()

    /**
     * 网络错误（连接失败、超时等）
     */
    data class NetworkError(val message: String) : ApiResult<Nothing>()

    /**
     * 是否成功
     */
    val isSuccess: Boolean get() = this is Success

    /**
     * 是否失败
     */
    val isError: Boolean get() = this is Error || this is NetworkError

    /**
     * 获取数据（失败时返回 null）
     */
    fun getOrNull(): T? = (this as? Success)?.data

    /**
     * 获取错误消息
     */
    fun getErrorMessage(): String? = when (this) {
        is Error -> message
        is NetworkError -> message
        else -> null
    }

    /**
     * 映射成功结果
     */
    inline fun <R> map(transform: (T) -> R): ApiResult<R> = when (this) {
        is Success -> Success(transform(data))
        is Error -> this
        is NetworkError -> this
    }

    /**
     * 成功时执行操作
     */
    inline fun onSuccess(action: (T) -> Unit): ApiResult<T> {
        if (this is Success) action(data)
        return this
    }

    /**
     * 失败时执行操作
     */
    inline fun onError(action: (String) -> Unit): ApiResult<T> {
        getErrorMessage()?.let(action)
        return this
    }

    /**
     * 转换为 Result
     */
    fun toResult(): Result<T> = when (this) {
        is Success -> Result.success(data)
        is Error -> Result.failure(Exception(message))
        is NetworkError -> Result.failure(Exception(message))
    }
}
