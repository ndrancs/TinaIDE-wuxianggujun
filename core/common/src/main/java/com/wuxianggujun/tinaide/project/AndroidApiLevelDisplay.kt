package com.wuxianggujun.tinaide.project

import android.content.Context
import com.wuxianggujun.tinaide.core.i18n.Strings
import com.wuxianggujun.tinaide.core.i18n.strOr

/**
 * Android API Level 的本地化显示扩展。
 */
fun AndroidApiLevel.getDisplayName(context: Context): String = Strings.ndk_api_level_display_name.strOr(context, level, codename)
