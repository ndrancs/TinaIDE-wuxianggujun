package com.wuxianggujun.tinaide.core.config

import android.content.Context
import android.content.SharedPreferences

object AppPreferences {
    const val PREFS_NAME = "tinaide_preferences"

    fun get(context: Context): SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
