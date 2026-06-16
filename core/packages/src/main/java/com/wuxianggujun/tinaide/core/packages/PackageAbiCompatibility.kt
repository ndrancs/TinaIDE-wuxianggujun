package com.wuxianggujun.tinaide.core.packages

object PackageAbiCompatibility {

    fun isCompatible(requiredAbis: List<String>?, supportedAbis: Array<String>): Boolean {
        val required = normalize(requiredAbis)
        if (required.isEmpty()) return true

        val supported = supportedAbis
            .asSequence()
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .map { it.lowercase() }
            .toSet()
        if (supported.isEmpty()) return false

        return required.any { abi -> abi in supported }
    }

    fun currentAbiLabel(supportedAbis: Array<String>): String = supportedAbis.firstOrNull { it.isNotBlank() } ?: "unknown"

    private fun normalize(abis: List<String>?): List<String> = abis.orEmpty()
        .asSequence()
        .map { it.trim() }
        .filter { it.isNotBlank() }
        .map { it.lowercase() }
        .distinct()
        .toList()
}
