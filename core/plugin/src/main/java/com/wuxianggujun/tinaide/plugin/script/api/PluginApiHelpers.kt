package com.wuxianggujun.tinaide.plugin.script.api

import com.wuxianggujun.tinaide.plugin.script.PluginPermission
import com.wuxianggujun.tinaide.plugin.script.ScriptPluginRuntime
import party.iroiro.luajava.Lua

/**
 * Runs [block] only if [runtime] has one of the given [permissions].
 * On denial, records a plugin warning log, then pushes (nil, "<localized error>") and returns 2.
 */
inline fun withPermission(
    runtime: ScriptPluginRuntime?,
    lua: Lua,
    apiNamespace: String,
    apiMethod: String,
    vararg permissions: PluginPermission,
    block: () -> Int
): Int {
    if (runtime == null) return 0
    if (!runtime.checkAnyPermission(*permissions)) {
        lua.pushNil()
        lua.push(runtime.reportPermissionDenied(apiNamespace, apiMethod, *permissions))
        return 2
    }
    return block()
}

fun Lua.getStringArg(index: Int): String? = if (top >= index && !isNil(index)) toString(index) else null

fun Lua.getIntArg(index: Int): Int? = if (top >= index && !isNil(index)) toInteger(index).toInt() else null

fun Lua.getLongArg(index: Int): Long? = if (top >= index && !isNil(index)) toInteger(index) else null

fun Lua.getBoolArg(index: Int): Boolean? = if (top >= index && !isNil(index)) toBoolean(index) else null

fun Lua.pushSuccess(value: Boolean, errorMsg: String? = null): Int {
    push(value)
    if (!value && errorMsg != null) {
        push(errorMsg)
        return 2
    }
    return 1
}

fun Lua.pushStringList(values: List<String>): Int {
    createTable(values.size, 0)
    values.forEachIndexed { index, value ->
        push(value)
        rawSetI(-2, index + 1)
    }
    return 1
}
