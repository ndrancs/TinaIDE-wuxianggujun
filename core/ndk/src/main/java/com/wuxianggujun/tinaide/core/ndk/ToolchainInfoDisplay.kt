package com.wuxianggujun.tinaide.core.ndk

import android.content.Context
import com.wuxianggujun.tinaide.core.i18n.Strings
import com.wuxianggujun.tinaide.core.i18n.strOr

fun ToolchainInfo.displayName(context: Context): String = when (type) {
    ToolchainType.BUILTIN -> Strings.toolchain_builtin_name.strOr(context)
    ToolchainType.CUSTOM -> name.trim().ifBlank { id }
}

fun ToolchainInfo.displayVersionLabel(context: Context): String? {
    val normalizedVersion = version?.trim().orEmpty()
    if (normalizedVersion.isBlank()) return null
    return Strings.toolchain_version_label.strOr(context, normalizedVersion)
}

fun ToolchainInfo.displayLabel(context: Context): String {
    val title = displayName(context)
    val versionLabel = displayVersionLabel(context) ?: return title
    return "$title ($versionLabel)"
}
