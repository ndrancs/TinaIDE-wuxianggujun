package com.wuxianggujun.tinaide.core.proot

/**
 * Linux rootfs 自检结果的业务摘要。
 *
 * 这里不绑定任何 UI 文案，调用方负责把状态和检查项转换为对应场景的国际化展示。
 */
enum class LinuxDistroRootfsHealthLevel {
    READY,
    ATTENTION,
    UNAVAILABLE,
}

data class LinuxDistroRootfsHealthSummary(
    val level: LinuxDistroRootfsHealthLevel,
    val requiredMissingItems: List<String> = emptyList(),
    val optionalMissingItems: List<String> = emptyList(),
    val identity: String = "",
)

fun LinuxDistroRootfsHealthReport.toHealthSummary(
    probeLabel: (LinuxDistroRootfsHealthProbe) -> String = { probe -> probe.name },
): LinuxDistroRootfsHealthSummary {
    val requiredMissingItems = requiredFailures.flatMap { check ->
        check.toDisplayItems(probeLabel)
    }.distinct()
    val optionalMissingItems = checks
        .filter { check -> !check.required && !check.passed }
        .flatMap { check -> check.toDisplayItems(probeLabel) }
        .distinct()
    val osName = osRelease["PRETTY_NAME"] ?: osRelease["NAME"]
    val identity = listOfNotNull(osName, architecture)
        .filter { value -> value.isNotBlank() }
        .joinToString(" · ")

    val level = when {
        !isUsable -> LinuxDistroRootfsHealthLevel.UNAVAILABLE
        !allChecksPassed -> LinuxDistroRootfsHealthLevel.ATTENTION
        else -> LinuxDistroRootfsHealthLevel.READY
    }

    return LinuxDistroRootfsHealthSummary(
        level = level,
        requiredMissingItems = requiredMissingItems,
        optionalMissingItems = optionalMissingItems,
        identity = identity,
    )
}

private fun LinuxDistroRootfsHealthCheck.toDisplayItems(
    probeLabel: (LinuxDistroRootfsHealthProbe) -> String,
): List<String> = when {
    missingItems.isNotEmpty() -> missingItems
    checkedItems.isNotEmpty() -> checkedItems
    else -> listOf(probeLabel(probe))
}
