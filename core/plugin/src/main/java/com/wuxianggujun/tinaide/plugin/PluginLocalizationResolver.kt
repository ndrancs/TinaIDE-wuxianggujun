package com.wuxianggujun.tinaide.plugin

import android.content.Context
import com.wuxianggujun.tinaide.core.serialization.JsonSerializer
import com.wuxianggujun.tinaide.plugin.lsp.LspServerConfig
import com.wuxianggujun.tinaide.plugin.lsp.LspToolchainConfig
import java.io.File
import java.util.Locale

object PluginLocalizationResolver {

    fun localize(
        manifest: PluginManifest,
        pluginDir: File,
        context: Context,
    ): PluginManifest = localize(
        manifest = manifest,
        pluginDir = pluginDir,
        locale = resolveContextLocale(context),
    )

    fun localize(
        manifest: PluginManifest,
        pluginDir: File,
        locale: Locale = Locale.getDefault(),
    ): PluginManifest {
        val localization = resolveLocalization(manifest.locales, pluginDir, locale) ?: return manifest
        return applyLocalization(manifest, localization)
    }

    fun resolveLocalization(
        locales: PluginLocales?,
        pluginDir: File,
        locale: Locale = Locale.getDefault(),
    ): PluginLocalization? {
        if (locales == null || locales.files.isEmpty()) return null
        val localeKey = resolveLocaleKey(locales, locale) ?: return null
        val path = locales.files[localeKey] ?: return null
        return JsonSerializer.decodeFromFile<PluginLocalization>(File(pluginDir, path))
    }

    internal fun resolveLocaleKey(
        locales: PluginLocales,
        locale: Locale,
    ): String? {
        val candidates = buildList {
            val language = locale.language.takeIf { it.isNotBlank() }
            val country = locale.country.takeIf { it.isNotBlank() }
            val script = locale.script.takeIf { it.isNotBlank() }
            if (language != null && script != null && country != null) add("$language-$script-$country")
            if (language != null && country != null) add("$language-$country")
            if (language != null && script != null) add("$language-$script")
            if (language != null) add(language)
            add(locales.default)
        }.map(::normalizeLocaleKey)

        val available = locales.files.keys.associateBy(::normalizeLocaleKey)
        return candidates.firstNotNullOfOrNull { candidate -> available[candidate] }
    }

    private fun applyLocalization(
        manifest: PluginManifest,
        localization: PluginLocalization,
    ): PluginManifest = manifest.copy(
        name = localization.name.notBlankOrNull() ?: manifest.name,
        description = localization.description.notBlankOrNull() ?: manifest.description,
        configuration = localizeConfiguration(manifest.configuration, localization.configuration),
        contributions = localizeContributions(manifest.contributions, localization.contributions),
    )

    private fun localizeConfiguration(
        configuration: PluginConfiguration?,
        localization: PluginConfigurationLocalization?,
    ): PluginConfiguration? {
        if (configuration == null || localization == null) return configuration
        return configuration.copy(
            title = localization.title.notBlankOrNull() ?: configuration.title,
            properties = configuration.properties.mapValues { (key, property) ->
                val localized = localization.properties[key]
                property.copy(
                    description = localized?.description.notBlankOrNull()
                        ?: property.description
                )
            },
        )
    }

    private fun localizeContributions(
        contributions: PluginContributions?,
        localization: PluginContributionsLocalization?,
    ): PluginContributions? {
        if (contributions == null || localization == null) return contributions
        return contributions.copy(
            projectTemplates = contributions.projectTemplates?.map { template ->
                val localized = localization.projectTemplates[template.id]
                template.copy(
                    name = localized?.name.notBlankOrNull() ?: template.name,
                    description = localized?.description.notBlankOrNull()
                        ?: template.description,
                )
            },
            apkExports = contributions.apkExports?.map { export ->
                val localized = localization.apkExports[export.id]
                export.copy(
                    name = localized?.name.notBlankOrNull() ?: export.name,
                    description = localized?.description.notBlankOrNull()
                        ?: export.description,
                )
            },
            commands = contributions.commands?.map { command ->
                val localized = localization.commands[command.id]
                command.copy(
                    title = localized?.title.notBlankOrNull() ?: command.title,
                )
            },
            panels = contributions.panels?.map { panel ->
                val localized = localization.panels[panel.id]
                panel.copy(
                    title = localized?.title.notBlankOrNull() ?: panel.title,
                )
            },
            languageServers = contributions.languageServers?.map { server ->
                localizeLspServer(server, localization.languageServers[server.id])
            },
            toolchains = contributions.toolchains?.map { toolchain ->
                localizeToolchain(toolchain, localization.toolchains[toolchain.id])
            },
        )
    }

    private fun localizeLspServer(
        server: LspServerConfig,
        localization: PluginNamedLocalization?,
    ): LspServerConfig = server.copy(
        name = localization?.name.notBlankOrNull() ?: server.name
    )

    private fun localizeToolchain(
        toolchain: LspToolchainConfig,
        localization: PluginNamedLocalization?,
    ): LspToolchainConfig = toolchain.copy(
        name = localization?.name.notBlankOrNull() ?: toolchain.name
    )

    private fun normalizeLocaleKey(key: String): String = key.trim().replace('_', '-').lowercase(Locale.ROOT)

    private fun resolveContextLocale(context: Context): Locale {
        val locales = context.resources.configuration.locales
        return if (locales.size() > 0) locales[0] else Locale.getDefault()
    }

    private fun String?.notBlankOrNull(): String? = this?.takeIf { it.isNotBlank() }
}
