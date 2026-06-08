package com.wuxianggujun.tinaide.cmake.i18n

import com.wuxianggujun.tinaide.core.i18n.str

internal object CMakeI18n {
    fun strOrFallback(resId: Int, fallback: String, vararg formatArgs: Any?): String = runCatching { resId.str(*formatArgs) }.getOrDefault(fallback)
}
