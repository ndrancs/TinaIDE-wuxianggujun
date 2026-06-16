package com.wuxianggujun.tinaide.core.i18n

import android.content.Context
import android.graphics.drawable.Drawable
import androidx.annotation.ArrayRes
import androidx.annotation.DrawableRes
import androidx.annotation.PluralsRes
import androidx.annotation.StringRes
import androidx.core.content.ContextCompat

fun @receiver:StringRes Int.str(vararg formatArgs: Any?): String = AppStrings.get(this, *formatArgs)

fun @receiver:StringRes Int.strOr(context: Context?, vararg formatArgs: Any?): String = AppStrings.getOr(context, this, *formatArgs)

fun @receiver:PluralsRes Int.plural(quantity: Int, vararg formatArgs: Any): String {
    val ctx = checkNotNull(AppStrings.applicationContextOrNull()) {
        "AppStrings not initialized. Call AppStrings.initialize(context) in TinaApplication.onCreate()."
    }
    return if (formatArgs.isEmpty()) {
        ctx.resources.getQuantityString(this, quantity, quantity)
    } else {
        ctx.resources.getQuantityString(this, quantity, *formatArgs)
    }
}

fun @receiver:ArrayRes Int.stringArray(): Array<String> {
    val ctx = checkNotNull(AppStrings.applicationContextOrNull()) {
        "AppStrings not initialized. Call AppStrings.initialize(context) in TinaApplication.onCreate()."
    }
    return ctx.resources.getStringArray(this)
}

fun @receiver:ArrayRes Int.stringArrayOr(context: Context?): Array<String> {
    val ctx = context ?: checkNotNull(AppStrings.applicationContextOrNull()) {
        "AppStrings not initialized. Call AppStrings.initialize(context) in TinaApplication.onCreate()."
    }
    return ctx.resources.getStringArray(this)
}

fun @receiver:DrawableRes Int.drawable(context: Context): Drawable? = ContextCompat.getDrawable(context, this)
