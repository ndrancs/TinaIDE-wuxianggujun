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

fun SysrootProfileInfo.displayName(context: Context): String = when (type) {
    SysrootProfileType.BUILTIN -> Strings.sysroot_profile_builtin_name.strOr(context)
    SysrootProfileType.CUSTOM -> name.trim().ifBlank { id }
    SysrootProfileType.LEGACY -> Strings.sysroot_profile_legacy_name.strOr(context)
}

fun SysrootProfileInfo.displayLabel(context: Context): String {
    val apiLabel = if (apiLevels.isEmpty()) {
        Strings.sysroot_profile_api_unknown.strOr(context)
    } else {
        apiLevels.joinToString(", ")
    }
    val ndkVersionLabel = ndkVersion?.trim()?.takeIf { it.isNotBlank() }
    val ndkLlvmVersionLabel = ndkLlvmVersion?.trim()?.takeIf { it.isNotBlank() }
    val versionLabel = when {
        ndkVersionLabel != null && ndkLlvmVersionLabel != null ->
            Strings.sysroot_profile_ndk_with_llvm_label.strOr(context, ndkVersionLabel, ndkLlvmVersionLabel)
        ndkVersionLabel != null -> ndkVersionLabel
        ndkLlvmVersionLabel != null ->
            Strings.sysroot_profile_ndk_llvm_label.strOr(context, ndkLlvmVersionLabel)
        else -> null
    }
        ?: toolchainTriple?.trim()?.takeIf { it.isNotBlank() }
        ?: arch
    return Strings.sysroot_profile_display_label.strOr(
        context,
        displayName(context),
        versionLabel,
        apiLabel
    )
}
