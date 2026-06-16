package com.wuxianggujun.tinaide.project

import android.content.Context
import com.wuxianggujun.tinaide.core.i18n.Strings
import com.wuxianggujun.tinaide.core.i18n.strOr

/**
 * C++ 标准的本地化显示扩展。
 *
 * `core:model` 保持纯模型；用户可见展示放到更外层模块完成。
 */
fun CppStandard.getDisplayName(context: Context): String = when (this) {
    CppStandard.CPP_11 -> Strings.cpp_standard_11.strOr(context)
    CppStandard.CPP_14 -> Strings.cpp_standard_14.strOr(context)
    CppStandard.CPP_17 -> Strings.cpp_standard_17.strOr(context)
    CppStandard.CPP_20 -> Strings.cpp_standard_20.strOr(context)
    CppStandard.CPP_23 -> Strings.cpp_standard_23.strOr(context)
}
