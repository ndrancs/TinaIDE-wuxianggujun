package com.wuxianggujun.tinaide.plugin

import java.net.URI

internal object PluginNetworkHostRules {

    fun normalizeDeclaredHosts(hosts: Iterable<String>?): Set<String> = (hosts ?: emptyList())
        .mapNotNull(::normalizeDeclaredHost)
        .toSet()

    fun findDuplicateDeclaredHosts(hosts: Iterable<String>?): Set<String> = (hosts ?: emptyList())
        .mapNotNull(::normalizeDeclaredHost)
        .groupingBy { it }
        .eachCount()
        .filterValues { it > 1 }
        .keys

    fun findInvalidDeclaredHosts(hosts: Iterable<String>?): List<String> = (hosts ?: emptyList())
        .map { it.trim() }
        .filter(String::isNotBlank)
        .filter { normalizeDeclaredHost(it) == null }
        .distinct()

    fun extractRequestHost(url: String): String? = runCatching {
        URI(url).host?.lowercase()
    }.getOrNull()

    fun isUrlAllowed(
        url: String,
        pluginAllowedHosts: Iterable<String>,
        hostAllowedHosts: Iterable<String> = emptySet(),
    ): Boolean {
        val host = extractRequestHost(url) ?: return false
        val allAllowed = normalizeDeclaredHosts(hostAllowedHosts) + normalizeDeclaredHosts(pluginAllowedHosts)
        if (allAllowed.isEmpty()) return false
        return allAllowed.any { allowed ->
            host == allowed || host.endsWith(".$allowed")
        }
    }

    fun normalizeDeclaredHost(raw: String?): String? {
        val candidate = raw?.trim()?.lowercase() ?: return null
        if (candidate.isBlank()) return null
        if (candidate.contains("://")) return null
        if (candidate.startsWith(".") || candidate.endsWith(".")) return null
        if (candidate.contains('/') || candidate.contains('\\')) return null
        if (candidate.contains('?') || candidate.contains('#')) return null
        if (candidate.contains('@') || candidate.contains('*')) return null
        if (candidate.contains(':')) return null
        val parsedHost = runCatching {
            URI("https://$candidate").host?.lowercase()
        }.getOrNull()
        return candidate.takeIf { parsedHost == candidate }
    }
}
