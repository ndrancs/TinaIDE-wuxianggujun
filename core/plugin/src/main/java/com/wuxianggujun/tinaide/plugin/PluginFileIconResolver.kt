package com.wuxianggujun.tinaide.plugin

import java.io.File
import timber.log.Timber

internal object PluginFileIconResolver {
    private const val TAG = "PluginFileIconResolver"
    private const val BUILTIN_PREFIX = "builtin:"

    fun resolve(installedPlugins: List<InstalledPlugin>): List<ResolvedPluginFileIcon> = installedPlugins.asSequence()
        .filter { it.enabled }
        .flatMap { plugin ->
            plugin.manifest.contributions?.fileIcons.orEmpty()
                .asSequence()
                .mapNotNull { contribution -> resolveContribution(plugin, contribution) }
        }
        .sortedWith(
            compareByDescending<ResolvedPluginFileIcon> { it.priority }
                .thenBy { it.pluginId }
                .thenBy { it.iconSpec }
        )
        .toList()

    private fun resolveContribution(
        plugin: InstalledPlugin,
        contribution: PluginFileIcon
    ): ResolvedPluginFileIcon? {
        val normalizedExtensions = contribution.extensions
            .orEmpty()
            .mapNotNull(::normalizeExtension)
            .toSet()
        val normalizedFileNames = contribution.fileNames
            .orEmpty()
            .mapNotNull(::normalizeFileName)
            .toSet()

        if (normalizedExtensions.isEmpty() && normalizedFileNames.isEmpty()) {
            Timber.tag(TAG).i(
                "Ignore file icon rule without matchers: plugin=%s icon=%s",
                plugin.manifest.id,
                contribution.icon
            )
            return null
        }

        val iconSpec = contribution.icon.trim()
        if (iconSpec.isEmpty()) {
            Timber.tag(TAG).i(
                "Ignore file icon rule with blank icon: plugin=%s",
                plugin.manifest.id
            )
            return null
        }

        val iconFile = if (iconSpec.startsWith(BUILTIN_PREFIX, ignoreCase = true)) {
            null
        } else {
            if (!isSafePluginRelativePath(iconSpec)) {
                Timber.tag(TAG).i(
                    "Ignore unsafe plugin icon path: plugin=%s path=%s",
                    plugin.manifest.id,
                    iconSpec
                )
                return null
            }
            val file = File(plugin.directory, iconSpec)
            if (!file.isFile) {
                Timber.tag(TAG).i(
                    "Ignore missing plugin icon file: plugin=%s path=%s",
                    plugin.manifest.id,
                    file.absolutePath
                )
                return null
            }
            file
        }

        return ResolvedPluginFileIcon(
            pluginId = plugin.manifest.id,
            iconSpec = iconSpec,
            iconFile = iconFile,
            extensions = normalizedExtensions,
            fileNames = normalizedFileNames,
            priority = contribution.priority
        )
    }

    private fun normalizeExtension(raw: String?): String? {
        val normalized = raw
            ?.trim()
            ?.removePrefix(".")
            ?.lowercase()
            ?.takeIf { it.isNotEmpty() }
        return normalized
    }

    private fun normalizeFileName(raw: String?): String? = raw
        ?.trim()
        ?.lowercase()
        ?.takeIf { it.isNotEmpty() }
}
