package com.wuxianggujun.tinaide.ui.compose.screens.settings.sections

import com.google.common.truth.Truth.assertThat
import com.wuxianggujun.tinaide.core.i18n.Strings
import com.wuxianggujun.tinaide.plugin.InstalledPlugin
import com.wuxianggujun.tinaide.plugin.PluginConfiguration
import com.wuxianggujun.tinaide.plugin.PluginConfigurationProperty
import com.wuxianggujun.tinaide.plugin.PluginContributions
import com.wuxianggujun.tinaide.plugin.PluginCommand
import com.wuxianggujun.tinaide.plugin.PluginDiagnosticCategory
import com.wuxianggujun.tinaide.plugin.PluginDiagnosticEntry
import com.wuxianggujun.tinaide.plugin.PluginDiagnosticIssue
import com.wuxianggujun.tinaide.plugin.PluginDiagnosticSeverity
import com.wuxianggujun.tinaide.plugin.PluginDiagnosticSource
import com.wuxianggujun.tinaide.plugin.PluginDiagnosticsReport
import com.wuxianggujun.tinaide.plugin.PluginDiagnosticsSnapshot
import com.wuxianggujun.tinaide.plugin.PluginLogLevel
import com.wuxianggujun.tinaide.plugin.PluginManifest
import com.wuxianggujun.tinaide.plugin.PluginMenuItem
import com.wuxianggujun.tinaide.plugin.PluginMenus
import com.wuxianggujun.tinaide.plugin.PluginRequirements
import com.wuxianggujun.tinaide.plugin.PluginToolchainRequirements
import com.wuxianggujun.tinaide.plugin.ResolvedPluginCommandSource
import com.wuxianggujun.tinaide.plugin.ResolvedPluginCommandSurface
import com.wuxianggujun.tinaide.plugin.ThemeConfig
import com.wuxianggujun.tinaide.plugin.lsp.LspPluginInfo
import com.wuxianggujun.tinaide.plugin.lsp.LspPluginInstallState
import com.wuxianggujun.tinaide.plugin.lsp.LspToolchainConfig
import com.wuxianggujun.tinaide.plugin.lsp.ToolchainInstallState
import com.wuxianggujun.tinaide.plugin.script.api.PluginCommandAvailability
import com.wuxianggujun.tinaide.plugin.script.api.PluginCommandRegistrationIssue
import java.io.File
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Test

class PluginsSettingsSectionSupportTest {

    @Test
    fun toggleAndThemeHelpers_shouldStayDeterministic() {
        assertThat(
            PluginsSettingsSectionSupport.toggleSelectedPlugin(
                selectedIds = setOf("a"),
                pluginId = "b",
            )
        ).containsExactly("a", "b")
        assertThat(
            PluginsSettingsSectionSupport.toggleSelectedPlugin(
                selectedIds = setOf("a", "b"),
                pluginId = "b",
            )
        ).containsExactly("a")

        val plugin = installedPlugin(
            id = "demo.plugin",
            name = "Demo Plugin",
            contributions = PluginContributions(
                themes = listOf("themes/dark.json", "themes\\light.json", "themes/missing.json"),
            ),
        )
        val themesIndex = mapOf(
            "plugin:demo.plugin/themes/dark.json" to ThemeConfig(
                name = "Dark Theme",
                colors = emptyMap(),
            ),
            "plugin:demo.plugin/themes/light.json" to ThemeConfig(
                name = "Light Theme",
                colors = emptyMap(),
            ),
        )

        assertThat(
            PluginsSettingsSectionSupport.buildPluginThemeIds(
                pluginId = plugin.manifest.id,
                relativePaths = plugin.manifest.contributions?.themes.orEmpty(),
                themesIndex = themesIndex,
            )
        ).containsExactly(
            "plugin:demo.plugin/themes/dark.json",
            "plugin:demo.plugin/themes/light.json",
        ).inOrder()
        assertThat(
            PluginsSettingsSectionSupport.buildPluginThemeOptions(
                plugin = plugin,
                themesIndex = themesIndex,
            )
        ).containsExactly(
            "plugin:demo.plugin/themes/dark.json" to "Dark Theme",
            "plugin:demo.plugin/themes/light.json" to "Light Theme",
        ).inOrder()
        assertThat(
            PluginsSettingsSectionSupport.resolvePluginInitial("Demo Plugin")
        ).isEqualTo("D")
        assertThat(
            PluginsSettingsSectionSupport.resolvePluginInitial("")
        ).isEqualTo("P")
    }

    @Test
    fun batchUninstallSpec_shouldExcludeBundledPluginsFromExecutionList() {
        val bundled = installedPlugin(
            id = "bundled.plugin",
            name = "Bundled Plugin",
            isBundled = true,
        )
        val custom = installedPlugin(
            id = "custom.plugin",
            name = "Custom Plugin",
            isBundled = false,
        )

        assertThat(
            PluginsSettingsSectionSupport.resolveBatchUninstallSpec(
                installedPlugins = listOf(bundled),
                selectedIds = setOf("bundled.plugin"),
            )
        ).isEqualTo(PluginsBatchUninstallSpec.BundledOnly)

        assertThat(
            PluginsSettingsSectionSupport.resolveBatchUninstallSpec(
                installedPlugins = listOf(bundled, custom),
                selectedIds = setOf("bundled.plugin", "custom.plugin"),
            )
        ).isEqualTo(
            PluginsBatchUninstallSpec.Confirm(
                pluginIds = listOf("custom.plugin"),
                pluginNames = "• Custom Plugin",
            )
        )
    }

    @Test
    fun installFileAndLspStatusHelpers_shouldNormalizeInputs() {
        assertThat(
            PluginsSettingsSectionSupport.resolveInstallSourceFileName(
                "content://plugins/foo/bar/demo.tinaplug"
            )
        ).isEqualTo("demo.tinaplug")
        assertThat(
            PluginsSettingsSectionSupport.resolveInstallSourceFileName(
                "C:\\Users\\demo\\plugin package.tinaplug"
            )
        ).isEqualTo("plugin_package.tinaplug")
        assertThat(
            PluginsSettingsSectionSupport.resolveInstallSourceFileName(
                "C:/Users/demo/plugin:package.tinaplug"
            )
        ).isEqualTo("plugin_package.tinaplug")
        assertThat(
            PluginsSettingsSectionSupport.resolveInstallSourceFileName(" ")
        ).isEqualTo("plugin.tinaplug")
        assertThat(
            PluginsSettingsSectionSupport.resolveInstallSourceFileName(null)
        ).isEqualTo("plugin.tinaplug")
        assertThat(
            PluginsSettingsSectionSupport.buildTempInstallFileName(
                timestampMillis = 123L,
                fileName = "demo.tinaplug",
            )
        ).isEqualTo("install_123_demo.tinaplug")
        assertThat(
            PluginsSettingsSectionSupport.resolveLspStatusLabelRes(true)
        ).isEqualTo(Strings.lsp_plugin_status_ready)
        assertThat(
            PluginsSettingsSectionSupport.resolveLspStatusLabelRes(false)
        ).isEqualTo(Strings.lsp_plugin_status_not_ready)
        assertThat(
            PluginsSettingsSectionSupport.resolvePluginDiagnosticSeverityLabelRes(
                PluginDiagnosticSeverity.ERROR
            )
        ).isEqualTo(Strings.diagnostic_error)
        assertThat(
            PluginsSettingsSectionSupport.resolvePluginDiagnosticCategoryLabelRes(
                PluginDiagnosticCategory.RUNTIME
            )
        ).isEqualTo(Strings.plugins_diagnostics_category_runtime)
        assertThat(
            PluginsSettingsSectionSupport.resolvePluginDiagnosticCategoryLabelRes(
                PluginDiagnosticCategory.LSP
            )
        ).isEqualTo(Strings.plugins_diagnostics_category_lsp)
        assertThat(
            PluginsSettingsSectionSupport.resolvePluginDiagnosticSourceLabelRes(
                PluginDiagnosticSource.LOAD
            )
        ).isEqualTo(Strings.plugins_diagnostics_source_load)
        assertThat(
            PluginsSettingsSectionSupport.resolvePluginDiagnosticsFilterLabelRes(
                PluginDiagnosticsFilter.LSP
            )
        ).isEqualTo(Strings.plugins_diagnostics_filter_lsp)
        assertThat(
            PluginsSettingsSectionSupport.resolvePluginDiagnosticSourceFilterLabelRes(
                PluginDiagnosticSourceFilter.ALL
            )
        ).isEqualTo(Strings.filter_all)
        assertThat(
            PluginsSettingsSectionSupport.resolvePluginPreflightCategoryGuideRes(
                PluginDiagnosticCategory.MANIFEST
            )
        ).isEqualTo(Strings.plugins_preflight_guide_manifest)
        assertThat(
            PluginsSettingsSectionSupport.resolvePluginPreflightCategoryGuideRes(
                PluginDiagnosticCategory.PERMISSIONS
            )
        ).isEqualTo(Strings.plugins_preflight_guide_permissions)
        assertThat(
            PluginsSettingsSectionSupport.resolvePluginPreflightCategoryGuideRes(
                PluginDiagnosticCategory.CONTRIBUTIONS
            )
        ).isEqualTo(Strings.plugins_preflight_guide_contributions)
        assertThat(
            PluginsSettingsSectionSupport.resolvePluginPreflightCategoryGuideRes(
                PluginDiagnosticCategory.COMPATIBILITY
            )
        ).isEqualTo(Strings.plugins_preflight_guide_compatibility)
        assertThat(
            PluginsSettingsSectionSupport.resolvePluginPreflightCategoryGuideRes(
                PluginDiagnosticCategory.RUNTIME
            )
        ).isEqualTo(Strings.plugins_preflight_guide_runtime)
        assertThat(
            PluginsSettingsSectionSupport.resolvePluginPreflightCategoryGuideRes(
                PluginDiagnosticCategory.LSP
            )
        ).isEqualTo(Strings.plugins_preflight_guide_lsp)
    }

    @Test
    fun lspRuntimeDiagnostics_shouldExposeMissingFailedAndLastError() {
        val lspPlugin = LspPluginInfo(
            pluginId = "tinaide.lsp.python",
            pluginName = "Python LSP",
            pluginVersion = "1.0.0",
            directory = File("plugins/python-lsp"),
            serverConfigs = emptyList(),
            toolchainConfigs = listOf(
                LspToolchainConfig(
                    id = "python",
                    name = "Python",
                    type = "system",
                    required = true,
                ),
                LspToolchainConfig(
                    id = "pyright",
                    name = "Pyright",
                    type = "npm",
                    required = true,
                ),
                LspToolchainConfig(
                    id = "ruff",
                    name = "Ruff",
                    type = "system",
                    required = false,
                ),
            ),
            activationEvents = emptyList(),
        )
        val diagnosticEntries = PluginsSettingsSectionSupport.buildLspRuntimeEntriesByPluginId(
            lspPlugins = listOf(lspPlugin),
            installStates = mapOf(
                lspPlugin.pluginId to LspPluginInstallState(
                    pluginId = lspPlugin.pluginId,
                    toolchainStates = mapOf(
                        "python" to ToolchainInstallState.FAILED,
                        "pyright" to ToolchainInstallState.NOT_INSTALLED,
                        "ruff" to ToolchainInstallState.NOT_INSTALLED,
                    ),
                    serverReady = false,
                    lastError = "pyright-langserver missing",
                )
            ),
            diagnosticText = LspRuntimeDiagnosticText(
                missingRequiredToolchainsTemplate = "Missing: %1\$s",
                failedRequiredToolchainsTemplate = "Failed: %1\$s",
                lastErrorTemplate = "Last: %1\$s",
                installFixHint = "Install dependencies",
                repairFixHint = "Repair dependencies",
            ),
        ).getValue(lspPlugin.pluginId)

        assertThat(diagnosticEntries.map { entry -> entry.issue.category })
            .containsExactly(
                PluginDiagnosticCategory.LSP,
                PluginDiagnosticCategory.LSP,
                PluginDiagnosticCategory.LSP,
            )
        assertThat(diagnosticEntries.map { entry -> entry.issue.message }).containsExactly(
            "Missing: Pyright",
            "Failed: Python",
            "Last: pyright-langserver missing",
        ).inOrder()
        assertThat(diagnosticEntries.first().issue.severity).isEqualTo(PluginDiagnosticSeverity.WARNING)
        assertThat(diagnosticEntries.drop(1).map { entry -> entry.issue.severity }).containsExactly(
            PluginDiagnosticSeverity.ERROR,
            PluginDiagnosticSeverity.ERROR,
        )
    }

    @Test
    fun contributionSummary_shouldCountThemeAndMenuEntries() {
        val manifest = PluginManifest(
            id = "demo.plugin",
            name = "Demo Plugin",
            version = "1.0.0",
            contributions = PluginContributions(
                themes = listOf("dark.json", "light.json"),
                menus = PluginMenus(
                    fileTreeContext = listOf(
                        PluginMenuItem(command = "a"),
                        PluginMenuItem(command = "b"),
                    ),
                    editorContext = listOf(
                        PluginMenuItem(command = "c"),
                    ),
                    editorToolbar = listOf(
                        PluginMenuItem(command = "d"),
                    ),
                ),
            ),
        )

        assertThat(
            PluginsSettingsSectionSupport.resolveContributionSummary(manifest)
        ).isEqualTo(
            PluginsContributionSummary(
                themeCount = 2,
                fileTreeMenuCount = 2,
                editorContextMenuCount = 1,
                editorToolbarMenuCount = 1,
            )
        )
    }

    @Test
    fun commandContributions_shouldExposeSurfaceSourceAndStatus() {
        val manifest = PluginManifest(
            id = "demo.plugin",
            name = "Demo Plugin",
            version = "1.0.0",
            contributions = PluginContributions(
                commands = listOf(
                    PluginCommand(id = "plugin.run", title = "Run Demo"),
                    PluginCommand(id = "plugin.open", title = "Open Demo"),
                ),
                menus = PluginMenus(
                    editorContext = listOf(
                        PluginMenuItem(
                            command = "plugin.run",
                            group = "1_run",
                            `when` = " isDirty ",
                        ),
                        PluginMenuItem(
                            command = "missing.command",
                            group = "2_missing",
                        ),
                        PluginMenuItem(
                            command = " ",
                            group = "3_blank",
                        ),
                    ),
                    editorToolbar = listOf(
                        PluginMenuItem(command = "editor.save"),
                    ),
                    fileTreeContext = listOf(
                        PluginMenuItem(command = "plugin.open", group = "0_open"),
                    ),
                ),
            ),
        )

        val commands = PluginsSettingsSectionSupport.resolveCommandContributions(
            manifest = manifest,
            isPluginCommandRegistered = { _, _ -> true },
            pluginCommandAvailability = { _, _ -> PluginCommandAvailability(available = true) },
        )

        assertThat(commands.map { command -> command.surface }).containsExactly(
            ResolvedPluginCommandSurface.EDITOR_CONTEXT,
            ResolvedPluginCommandSurface.EDITOR_CONTEXT,
            ResolvedPluginCommandSurface.EDITOR_CONTEXT,
            ResolvedPluginCommandSurface.EDITOR_TOOLBAR,
            ResolvedPluginCommandSurface.FILE_TREE_CONTEXT,
        ).inOrder()
        assertThat(commands.map { command -> command.commandId }).containsExactly(
            "plugin.run",
            "missing.command",
            "",
            "editor.save",
            "plugin.open",
        ).inOrder()
        assertThat(commands.map { command -> command.source }).containsExactly(
            ResolvedPluginCommandSource.PLUGIN,
            null,
            null,
            ResolvedPluginCommandSource.HOST,
            ResolvedPluginCommandSource.PLUGIN,
        ).inOrder()
        assertThat(commands.map { command -> command.status }).containsExactly(
            PluginCommandContributionStatus.AVAILABLE,
            PluginCommandContributionStatus.MISSING_COMMAND_DECLARATION,
            PluginCommandContributionStatus.MISSING_COMMAND_ID,
            PluginCommandContributionStatus.AVAILABLE,
            PluginCommandContributionStatus.AVAILABLE,
        ).inOrder()
        assertThat(commands.first().title).isEqualTo("Run Demo")
        assertThat(commands.first().whenExpression).isEqualTo("isDirty")
        assertThat(commands[3].group).isEqualTo("9_plugin")
        assertThat(
            PluginsSettingsSectionSupport.resolveCommandContributionSummary(commands)
        ).isEqualTo(
            PluginsCommandContributionSummary(
                totalCount = 5,
                availableCount = 3,
                issueCount = 2,
            )
        )
    }

    @Test
    fun commandContributionFilters_shouldExposeCountsAndFilteredLists() {
        val commands = listOf(
            PluginsCommandContribution(
                surface = ResolvedPluginCommandSurface.EDITOR_CONTEXT,
                commandId = "plugin.ready",
                title = "Ready",
                group = "ready",
                source = ResolvedPluginCommandSource.PLUGIN,
                status = PluginCommandContributionStatus.AVAILABLE,
                whenExpression = null,
            ),
            PluginsCommandContribution(
                surface = ResolvedPluginCommandSurface.EDITOR_CONTEXT,
                commandId = "plugin.missing",
                title = "Missing Runtime",
                group = "missing",
                source = ResolvedPluginCommandSource.PLUGIN,
                status = PluginCommandContributionStatus.MISSING_RUNTIME_REGISTRATION,
                whenExpression = null,
                statusMessage = "Command ID already registered by plugin other.plugin: plugin.missing",
            ),
            PluginsCommandContribution(
                surface = ResolvedPluginCommandSurface.FILE_TREE_CONTEXT,
                commandId = "",
                title = "Broken Menu",
                group = "broken",
                source = null,
                status = PluginCommandContributionStatus.MISSING_COMMAND_ID,
                whenExpression = null,
            ),
        )
        val summary = PluginsSettingsSectionSupport.resolveCommandContributionSummary(commands)

        assertThat(
            PluginsSettingsSectionSupport.resolveCommandContributionFilterOptions(summary)
        ).containsExactly(
            PluginCommandContributionFilterOption(
                filter = PluginCommandContributionFilter.ALL,
                count = 3,
            ),
            PluginCommandContributionFilterOption(
                filter = PluginCommandContributionFilter.ISSUES,
                count = 2,
            ),
            PluginCommandContributionFilterOption(
                filter = PluginCommandContributionFilter.AVAILABLE,
                count = 1,
            ),
        ).inOrder()
        assertThat(
            PluginsSettingsSectionSupport.filterCommandContributions(
                commands = commands,
                filter = PluginCommandContributionFilter.ISSUES,
            ).map { command -> command.commandId }
        ).containsExactly("plugin.missing", "")
        assertThat(
            PluginsSettingsSectionSupport.filterCommandContributions(
                commands = commands,
                filter = PluginCommandContributionFilter.AVAILABLE,
            ).map { command -> command.commandId }
        ).containsExactly("plugin.ready")
        assertThat(
            PluginsSettingsSectionSupport.resolveCommandContributionFilterOrAll(
                filter = PluginCommandContributionFilter.AVAILABLE,
                availableFilters = listOf(
                    PluginCommandContributionFilterOption(
                        filter = PluginCommandContributionFilter.ALL,
                        count = 2,
                    ),
                    PluginCommandContributionFilterOption(
                        filter = PluginCommandContributionFilter.ISSUES,
                        count = 2,
                    ),
                ),
            )
        ).isEqualTo(PluginCommandContributionFilter.ALL)
    }

    @Test
    fun commandContributions_shouldExposeRuntimeAvailabilityDiagnostics() {
        val manifest = PluginManifest(
            id = "demo.plugin",
            name = "Demo Plugin",
            version = "1.0.0",
            contributions = PluginContributions(
                commands = listOf(
                    PluginCommand(id = "plugin.missing", title = "Missing Runtime"),
                    PluginCommand(id = "plugin.denied", title = "Denied Runtime"),
                    PluginCommand(id = "plugin.ready", title = "Ready Runtime"),
                ),
                menus = PluginMenus(
                    editorContext = listOf(
                        PluginMenuItem(command = "plugin.missing", group = "1_missing"),
                        PluginMenuItem(command = "plugin.denied", group = "2_denied"),
                        PluginMenuItem(command = "plugin.ready", group = "3_ready"),
                    ),
                ),
            ),
        )

        val commands = PluginsSettingsSectionSupport.resolveCommandContributions(
            manifest = manifest,
            isPluginCommandRegistered = { commandId, _ -> commandId != "plugin.missing" },
            pluginCommandAvailability = { commandId, _ ->
                when (commandId) {
                    "plugin.denied" -> PluginCommandAvailability(
                        available = false,
                        errorMessage = "Permission command.execute is not granted",
                    )
                    else -> PluginCommandAvailability(available = true)
                }
            },
            pluginCommandRegistrationIssue = { commandId, pluginId ->
                if (commandId == "plugin.missing") {
                    PluginCommandRegistrationIssue(
                        pluginId = pluginId,
                        pluginName = "Demo Plugin",
                        commandId = commandId,
                        message = "Command ID already registered by plugin other.plugin: plugin.missing",
                    )
                } else {
                    null
                }
            },
        )

        assertThat(commands.map { command -> command.commandId }).containsExactly(
            "plugin.missing",
            "plugin.denied",
            "plugin.ready",
        ).inOrder()
        assertThat(commands.map { command -> command.status }).containsExactly(
            PluginCommandContributionStatus.MISSING_RUNTIME_REGISTRATION,
            PluginCommandContributionStatus.UNAVAILABLE,
            PluginCommandContributionStatus.AVAILABLE,
        ).inOrder()
        assertThat(commands[0].statusMessage)
            .isEqualTo("Command ID already registered by plugin other.plugin: plugin.missing")
        assertThat(commands[1].statusMessage).isEqualTo("Permission command.execute is not granted")
        assertThat(
            PluginsSettingsSectionSupport.resolveCommandContributionSummary(commands)
        ).isEqualTo(
            PluginsCommandContributionSummary(
                totalCount = 3,
                availableCount = 1,
                issueCount = 2,
            )
        )
    }

    @Test
    fun commandRuntimeEntries_shouldExposeDiagnosticsAndActions() {
        val commands = listOf(
            PluginsCommandContribution(
                surface = ResolvedPluginCommandSurface.EDITOR_CONTEXT,
                commandId = "plugin.missing",
                title = "Missing Runtime",
                group = "1_missing",
                source = ResolvedPluginCommandSource.PLUGIN,
                status = PluginCommandContributionStatus.MISSING_RUNTIME_REGISTRATION,
                whenExpression = null,
                statusMessage = "Command ID already registered by plugin other.plugin: plugin.missing",
            ),
            PluginsCommandContribution(
                surface = ResolvedPluginCommandSurface.EDITOR_CONTEXT,
                commandId = "plugin.denied",
                title = "Denied Runtime",
                group = "2_denied",
                source = ResolvedPluginCommandSource.PLUGIN,
                status = PluginCommandContributionStatus.UNAVAILABLE,
                whenExpression = null,
                statusMessage = "Permission command.execute is not granted",
            ),
            PluginsCommandContribution(
                surface = ResolvedPluginCommandSurface.EDITOR_CONTEXT,
                commandId = "plugin.ready",
                title = "Ready Runtime",
                group = "3_ready",
                source = ResolvedPluginCommandSource.PLUGIN,
                status = PluginCommandContributionStatus.AVAILABLE,
                whenExpression = null,
            ),
        )
        val diagnosticText = PluginCommandRuntimeDiagnosticText(
            missingRegistrationTemplate = "Command %1\$s is not registered",
            missingRegistrationWithReasonTemplate = "Command %1\$s registration failed: %2\$s",
            unavailableTemplate = "Command %1\$s is unavailable: %2\$s",
            unavailableWithoutReasonTemplate = "Command %1\$s is unavailable",
            runtimeFixHint = "Reload plugin",
            permissionFixHint = "Grant permission",
            missingCommandIdLabel = "Missing command ID",
        )

        val entries = PluginsSettingsSectionSupport.buildCommandRuntimeEntries(
            commands = commands,
            diagnosticText = diagnosticText,
        )

        assertThat(entries.map { entry -> entry.source }).containsExactly(
            PluginDiagnosticSource.RUNTIME,
            PluginDiagnosticSource.RUNTIME,
        )
        assertThat(entries.map { entry -> entry.issue.category }).containsExactly(
            PluginDiagnosticCategory.RUNTIME,
            PluginDiagnosticCategory.PERMISSIONS,
        )
        assertThat(entries.map { entry -> entry.issue.message }).containsExactly(
            "Command plugin.missing registration failed: Command ID already registered by plugin other.plugin: plugin.missing",
            "Command plugin.denied is unavailable: Permission command.execute is not granted",
        ).inOrder()
        assertThat(entries.map { entry -> entry.issue.fixHint }).containsExactly(
            "Reload plugin",
            "Grant permission",
        ).inOrder()
        assertThat(
            PluginsSettingsSectionSupport.resolvePluginCommandContributionActions(
                command = commands[0],
                isScriptPlugin = true,
            )
        ).containsExactly(
            PluginDiagnosticAction.OPEN_LOGS,
            PluginDiagnosticAction.RELOAD_PLUGIN,
            PluginDiagnosticAction.COPY_DIAGNOSTIC,
        ).inOrder()
        assertThat(
            PluginsSettingsSectionSupport.resolvePluginCommandContributionActions(
                command = commands[1],
                isScriptPlugin = true,
            )
        ).containsExactly(
            PluginDiagnosticAction.OPEN_LOGS,
            PluginDiagnosticAction.SHOW_PERMISSIONS,
            PluginDiagnosticAction.RELOAD_PLUGIN,
            PluginDiagnosticAction.COPY_DIAGNOSTIC,
        ).inOrder()
        assertThat(
            PluginsSettingsSectionSupport.resolvePluginCommandContributionActions(
                command = commands[2],
                isScriptPlugin = true,
            )
        ).isEmpty()
        assertThat(
            PluginsSettingsSectionSupport.resolvePluginCommandContributionActions(
                command = commands[2].copy(
                    status = PluginCommandContributionStatus.MISSING_COMMAND_DECLARATION,
                ),
                isScriptPlugin = true,
            )
        ).containsExactly(PluginDiagnosticAction.COPY_DIAGNOSTIC)
    }

    @Test
    fun commandContributionLabels_shouldMapToStableStringResources() {
        assertThat(
            PluginsSettingsSectionSupport.resolvePluginCommandSurfaceLabelRes(
                ResolvedPluginCommandSurface.EDITOR_CONTEXT
            )
        ).isEqualTo(Strings.plugins_commands_surface_editor_context)
        assertThat(
            PluginsSettingsSectionSupport.resolvePluginCommandSourceLabelRes(
                ResolvedPluginCommandSource.HOST
            )
        ).isEqualTo(Strings.plugins_commands_source_host)
        assertThat(
            PluginsSettingsSectionSupport.resolvePluginCommandSourceLabelRes(null)
        ).isEqualTo(Strings.plugins_commands_source_unknown)
        assertThat(
            PluginsSettingsSectionSupport.resolvePluginCommandStatusLabelRes(
                PluginCommandContributionStatus.MISSING_COMMAND_DECLARATION
            )
        ).isEqualTo(Strings.plugins_commands_status_missing_declaration)
        assertThat(
            PluginsSettingsSectionSupport.resolvePluginCommandStatusLabelRes(
                PluginCommandContributionStatus.MISSING_RUNTIME_REGISTRATION
            )
        ).isEqualTo(Strings.plugins_commands_status_missing_registration)
        assertThat(
            PluginsSettingsSectionSupport.resolvePluginCommandStatusLabelRes(
                PluginCommandContributionStatus.UNAVAILABLE
            )
        ).isEqualTo(Strings.plugins_commands_status_unavailable)
        assertThat(
            PluginsSettingsSectionSupport.resolvePluginCommandContributionFilterLabelRes(
                PluginCommandContributionFilter.ISSUES
            )
        ).isEqualTo(Strings.plugins_commands_filter_issues)
        assertThat(
            PluginsSettingsSectionSupport.resolvePluginCommandContributionFilterLabelRes(
                PluginCommandContributionFilter.AVAILABLE
            )
        ).isEqualTo(Strings.plugins_commands_filter_available)
    }

    @Test
    fun requirementsSummary_shouldNormalizeAndGroupManifestRequires() {
        val manifest = PluginManifest(
            id = "demo.plugin",
            name = "Demo Plugin",
            version = "1.0.0",
            requires = PluginRequirements(
                toolchain = PluginToolchainRequirements(
                    recommended = listOf("clangd", " cmake ", "clangd", ""),
                    optional = listOf("lldb", " "),
                ),
                packages = mapOf(
                    "proot" to listOf("python3", " nodejs ", "python3"),
                    "native" to listOf("lld"),
                    " " to listOf("ignored"),
                    "empty" to emptyList(),
                ),
            ),
        )

        assertThat(
            PluginsSettingsSectionSupport.resolveRequirementsSummary(manifest)
        ).isEqualTo(
            PluginsRequirementsSummary(
                recommendedToolchains = listOf("clangd", "cmake"),
                optionalToolchains = listOf("lldb"),
                packageGroups = listOf(
                    PluginsPackageRequirementGroup(
                        manager = "native",
                        packages = listOf("lld"),
                    ),
                    PluginsPackageRequirementGroup(
                        manager = "proot",
                        packages = listOf("nodejs", "python3"),
                    ),
                ),
            )
        )
    }

    @Test
    fun configurationSummary_shouldResolveValidPropertiesOnly() {
        val manifest = PluginManifest(
            id = "demo.plugin",
            name = "Demo Plugin",
            version = "1.0.0",
            configuration = PluginConfiguration(
                title = "  Demo Settings  ",
                properties = mapOf(
                    "output.format" to PluginConfigurationProperty(
                        type = "string",
                        default = JsonPrimitive("text"),
                        enumValues = listOf("text", "json", "text"),
                    ),
                    " " to PluginConfigurationProperty(
                        type = "boolean",
                    ),
                    "feature.enabled" to PluginConfigurationProperty(
                        type = "boolean",
                        default = JsonPrimitive(true),
                    ),
                    "unsupported" to PluginConfigurationProperty(
                        type = "object",
                    ),
                ),
            ),
        )

        val summary = PluginsSettingsSectionSupport.resolveConfigurationSummary(manifest)

        assertThat(summary.title).isEqualTo("Demo Settings")
        assertThat(summary.properties.map { property -> property.key }).containsExactly(
            "feature.enabled",
            "output.format",
        ).inOrder()
        assertThat(summary.properties.single { property -> property.key == "output.format" }.enumValues)
            .containsExactly("text", "json")
            .inOrder()
    }

    @Test
    fun detailSelectionHelpers_shouldCloseWhenInstalledPluginDisappears() {
        val plugin = installedPlugin(
            id = "demo.plugin",
            name = "Demo Plugin",
        )

        assertThat(
            PluginsSettingsSectionSupport.resolveDetailPlugin(
                selectedPluginId = "demo.plugin",
                installedPlugins = listOf(plugin),
            )
        ).isEqualTo(plugin)
        assertThat(
            PluginsSettingsSectionSupport.shouldClosePluginDetails(
                selectedPluginId = "demo.plugin",
                detailPlugin = plugin,
            )
        ).isFalse()
        assertThat(
            PluginsSettingsSectionSupport.shouldClosePluginDetails(
                selectedPluginId = "missing.plugin",
                detailPlugin = null,
            )
        ).isTrue()
    }

    @Test
    fun diagnosticsSummary_shouldReadFromUnifiedReport() {
        val report = PluginDiagnosticsReport(
            pluginId = "demo.plugin",
            pluginName = "Demo Plugin",
            isInstalled = true,
            entries = listOf(
                PluginDiagnosticEntry(
                    source = PluginDiagnosticSource.RUNTIME,
                    issue = PluginDiagnosticIssue(
                        severity = PluginDiagnosticSeverity.ERROR,
                        category = PluginDiagnosticCategory.RUNTIME,
                        message = "Runtime crash"
                    )
                ),
                PluginDiagnosticEntry(
                    source = PluginDiagnosticSource.HEALTH,
                    issue = PluginDiagnosticIssue(
                        severity = PluginDiagnosticSeverity.WARNING,
                        category = PluginDiagnosticCategory.COMPATIBILITY,
                        message = "Compatibility warning"
                    )
                ),
            )
        )

        val summary = PluginsSettingsSectionSupport.resolvePluginDiagnosticsSummary(report)

        assertThat(summary.totalCount).isEqualTo(2)
        assertThat(summary.errorCount).isEqualTo(1)
        assertThat(summary.warningCount).isEqualTo(1)
        assertThat(summary.highestSeverity).isEqualTo(PluginDiagnosticSeverity.ERROR)
        assertThat(PluginsSettingsSectionSupport.hasLspDiagnostics(report)).isFalse()

        val lspReport = report.copy(
            entries = report.entries + PluginDiagnosticEntry(
                source = PluginDiagnosticSource.RUNTIME,
                issue = PluginDiagnosticIssue(
                    severity = PluginDiagnosticSeverity.ERROR,
                    category = PluginDiagnosticCategory.LSP,
                    message = "LSP dependency failed",
                ),
            )
        )
        assertThat(PluginsSettingsSectionSupport.hasLspDiagnostics(lspReport)).isTrue()
        assertThat(PluginsSettingsSectionSupport.resolveLspDiagnosticSeverity(lspReport))
            .isEqualTo(PluginDiagnosticSeverity.ERROR)
    }

    @Test
    fun preflightDiagnosticGroups_shouldGroupVisibleErrorsAndWarningsByCategory() {
        val manifestWarning = PluginDiagnosticEntry(
            source = PluginDiagnosticSource.HEALTH,
            issue = PluginDiagnosticIssue(
                severity = PluginDiagnosticSeverity.WARNING,
                category = PluginDiagnosticCategory.MANIFEST,
                message = "Manifest warning",
            )
        )
        val permissionError = PluginDiagnosticEntry(
            source = PluginDiagnosticSource.HEALTH,
            issue = PluginDiagnosticIssue(
                severity = PluginDiagnosticSeverity.ERROR,
                category = PluginDiagnosticCategory.PERMISSIONS,
                message = "Permission error",
            )
        )
        val permissionWarning = PluginDiagnosticEntry(
            source = PluginDiagnosticSource.HEALTH,
            issue = PluginDiagnosticIssue(
                severity = PluginDiagnosticSeverity.WARNING,
                category = PluginDiagnosticCategory.PERMISSIONS,
                message = "Permission warning",
            )
        )
        val runtimeInfo = PluginDiagnosticEntry(
            source = PluginDiagnosticSource.RUNTIME,
            issue = PluginDiagnosticIssue(
                severity = PluginDiagnosticSeverity.INFO,
                category = PluginDiagnosticCategory.RUNTIME,
                message = "Runtime info",
            )
        )
        val report = PluginDiagnosticsReport(
            pluginId = "demo.plugin",
            pluginName = "Demo Plugin",
            isInstalled = false,
            entries = listOf(manifestWarning, permissionWarning, runtimeInfo, permissionError),
        )

        val groups = PluginsSettingsSectionSupport.resolvePluginPreflightDiagnosticGroups(report)

        assertThat(groups.map { group -> group.category }).containsExactly(
            PluginDiagnosticCategory.PERMISSIONS,
            PluginDiagnosticCategory.MANIFEST,
        ).inOrder()
        assertThat(groups.first().errorCount).isEqualTo(1)
        assertThat(groups.first().warningCount).isEqualTo(1)
        assertThat(groups.first().entries).containsExactly(
            permissionError,
            permissionWarning,
        ).inOrder()
        assertThat(groups.last().errorCount).isEqualTo(0)
        assertThat(groups.last().warningCount).isEqualTo(1)
        assertThat(groups.flatMap { group -> group.entries }).doesNotContain(runtimeInfo)
    }

    @Test
    fun diagnosticsOverviewSummary_shouldCountInstalledErrorWarningAndLoadReports() {
        val installedReports = mapOf(
            "error.plugin" to PluginDiagnosticsReport(
                pluginId = "error.plugin",
                pluginName = "Error Plugin",
                isInstalled = true,
                entries = listOf(
                    PluginDiagnosticEntry(
                        source = PluginDiagnosticSource.RUNTIME,
                        issue = PluginDiagnosticIssue(
                            severity = PluginDiagnosticSeverity.ERROR,
                            category = PluginDiagnosticCategory.RUNTIME,
                            message = "Runtime crash"
                        )
                    )
                ),
            ),
            "warning.plugin" to PluginDiagnosticsReport(
                pluginId = "warning.plugin",
                pluginName = "Warning Plugin",
                isInstalled = true,
                entries = listOf(
                    PluginDiagnosticEntry(
                        source = PluginDiagnosticSource.HEALTH,
                        issue = PluginDiagnosticIssue(
                            severity = PluginDiagnosticSeverity.WARNING,
                            category = PluginDiagnosticCategory.COMPATIBILITY,
                            message = "Compatibility warning"
                        )
                    )
                ),
            ),
            "lsp.plugin" to PluginDiagnosticsReport(
                pluginId = "lsp.plugin",
                pluginName = "LSP Plugin",
                isInstalled = true,
                entries = listOf(
                    PluginDiagnosticEntry(
                        source = PluginDiagnosticSource.RUNTIME,
                        issue = PluginDiagnosticIssue(
                            severity = PluginDiagnosticSeverity.WARNING,
                            category = PluginDiagnosticCategory.LSP,
                            message = "LSP toolchain missing"
                        )
                    )
                ),
            ),
            "clean.plugin" to PluginDiagnosticsReport(
                pluginId = "clean.plugin",
                pluginName = "Clean Plugin",
                isInstalled = true,
                entries = emptyList()
            ),
        )
        val loadReports = listOf(
            PluginDiagnosticsReport(
                pluginId = null,
                pluginName = "Broken Plugin",
                directoryName = "broken-plugin",
                isInstalled = false,
                entries = listOf(
                    PluginDiagnosticEntry(
                        source = PluginDiagnosticSource.LOAD,
                        issue = PluginDiagnosticIssue(
                            severity = PluginDiagnosticSeverity.ERROR,
                            category = PluginDiagnosticCategory.MANIFEST,
                            message = "manifest missing"
                        )
                    )
                )
            )
        )

        val summary = PluginsSettingsSectionSupport.resolvePluginDiagnosticsOverviewSummary(
            PluginDiagnosticsSnapshot(
                installedReports = installedReports,
                loadReports = loadReports,
            )
        )

        assertThat(summary.installedCount).isEqualTo(4)
        assertThat(summary.issuePluginCount).isEqualTo(3)
        assertThat(summary.errorPluginCount).isEqualTo(1)
        assertThat(summary.warningPluginCount).isEqualTo(2)
        assertThat(summary.lspIssuePluginCount).isEqualTo(1)
        assertThat(summary.notLoadedCount).isEqualTo(1)

        assertThat(
            PluginsSettingsSectionSupport.resolvePluginDiagnosticsOverviewSummary(
                installedReports = listOf(installedReports.getValue("error.plugin")),
                loadReports = emptyList(),
            )
        ).isEqualTo(
            PluginDiagnosticsOverviewSummary(
                installedCount = 1,
                issuePluginCount = 1,
                errorPluginCount = 1,
                warningPluginCount = 0,
                lspIssuePluginCount = 0,
                notLoadedCount = 0,
            )
        )
    }

    @Test
    fun diagnosticsFilter_shouldToggleAndFilterInstalledAndLoadReports() {
        val errorPlugin = installedPlugin(
            id = "error.plugin",
            name = "Error Plugin",
        )
        val warningPlugin = installedPlugin(
            id = "warning.plugin",
            name = "Warning Plugin",
        )
        val lspPlugin = installedPlugin(
            id = "lsp.plugin",
            name = "LSP Plugin",
        )
        val cleanPlugin = installedPlugin(
            id = "clean.plugin",
            name = "Clean Plugin",
        )
        val diagnosticPlugins = listOf(errorPlugin, warningPlugin, lspPlugin, cleanPlugin)
        val snapshot = PluginDiagnosticsSnapshot(
            installedReports = mapOf(
                "error.plugin" to PluginDiagnosticsReport(
                    pluginId = "error.plugin",
                    pluginName = "Error Plugin",
                    isInstalled = true,
                    entries = listOf(
                        PluginDiagnosticEntry(
                            source = PluginDiagnosticSource.RUNTIME,
                            issue = PluginDiagnosticIssue(
                                severity = PluginDiagnosticSeverity.ERROR,
                                category = PluginDiagnosticCategory.RUNTIME,
                                message = "Runtime crash"
                            )
                        )
                    )
                ),
                "warning.plugin" to PluginDiagnosticsReport(
                    pluginId = "warning.plugin",
                    pluginName = "Warning Plugin",
                    isInstalled = true,
                    entries = listOf(
                        PluginDiagnosticEntry(
                            source = PluginDiagnosticSource.HEALTH,
                            issue = PluginDiagnosticIssue(
                                severity = PluginDiagnosticSeverity.WARNING,
                                category = PluginDiagnosticCategory.COMPATIBILITY,
                                message = "Compatibility warning"
                            )
                        )
                    )
                ),
                "lsp.plugin" to PluginDiagnosticsReport(
                    pluginId = "lsp.plugin",
                    pluginName = "LSP Plugin",
                    isInstalled = true,
                    entries = listOf(
                        PluginDiagnosticEntry(
                            source = PluginDiagnosticSource.RUNTIME,
                            issue = PluginDiagnosticIssue(
                                severity = PluginDiagnosticSeverity.WARNING,
                                category = PluginDiagnosticCategory.LSP,
                                message = "LSP toolchain missing"
                            )
                        )
                    )
                ),
                "clean.plugin" to PluginDiagnosticsReport(
                    pluginId = "clean.plugin",
                    pluginName = "Clean Plugin",
                    isInstalled = true,
                    entries = emptyList()
                ),
            ),
            loadReports = listOf(
                PluginDiagnosticsReport(
                    pluginId = null,
                    pluginName = "Broken Plugin",
                    directoryName = "broken-plugin",
                    isInstalled = false,
                    entries = listOf(
                        PluginDiagnosticEntry(
                            source = PluginDiagnosticSource.LOAD,
                            issue = PluginDiagnosticIssue(
                                severity = PluginDiagnosticSeverity.ERROR,
                                category = PluginDiagnosticCategory.MANIFEST,
                                message = "Manifest missing"
                            )
                        )
                    )
                )
            )
        )

        assertThat(
            PluginsSettingsSectionSupport.togglePluginDiagnosticsFilter(
                currentFilter = PluginDiagnosticsFilter.ERROR,
                selectedFilter = PluginDiagnosticsFilter.ERROR,
            )
        ).isEqualTo(PluginDiagnosticsFilter.ALL)
        assertThat(
            PluginsSettingsSectionSupport.togglePluginDiagnosticsFilter(
                currentFilter = PluginDiagnosticsFilter.WARNING,
                selectedFilter = PluginDiagnosticsFilter.NOT_LOADED,
            )
        ).isEqualTo(PluginDiagnosticsFilter.NOT_LOADED)

        assertThat(
            PluginsSettingsSectionSupport.filterInstalledPluginsByDiagnostics(
                installedPlugins = diagnosticPlugins,
                snapshot = snapshot,
                filter = PluginDiagnosticsFilter.ERROR,
            )
        ).containsExactly(errorPlugin)
        assertThat(
            PluginsSettingsSectionSupport.filterInstalledPluginsByDiagnostics(
                installedPlugins = diagnosticPlugins,
                snapshot = snapshot,
                filter = PluginDiagnosticsFilter.WARNING,
            )
        ).containsExactly(warningPlugin, lspPlugin).inOrder()
        assertThat(
            PluginsSettingsSectionSupport.filterInstalledPluginsByDiagnostics(
                installedPlugins = diagnosticPlugins,
                snapshot = snapshot,
                filter = PluginDiagnosticsFilter.NOT_LOADED,
            )
        ).isEmpty()
        assertThat(
            PluginsSettingsSectionSupport.filterInstalledPluginsByDiagnostics(
                installedPlugins = diagnosticPlugins,
                snapshot = snapshot,
                filter = PluginDiagnosticsFilter.LSP,
            )
        ).containsExactly(lspPlugin)
        assertThat(
            PluginsSettingsSectionSupport.filterLoadReportsByDiagnostics(
                snapshot = snapshot,
                filter = PluginDiagnosticsFilter.ERROR,
            )
        ).isEmpty()
        assertThat(
            PluginsSettingsSectionSupport.filterLoadReportsByDiagnostics(
                snapshot = snapshot,
                filter = PluginDiagnosticsFilter.LSP,
            )
        ).isEmpty()
        assertThat(
            PluginsSettingsSectionSupport.filterLoadReportsByDiagnostics(
                snapshot = snapshot,
                filter = PluginDiagnosticsFilter.NOT_LOADED,
            )
        ).hasSize(1)
        assertThat(
            PluginsSettingsSectionSupport.shouldShowDiagnosticsFilteredEmptyState(
                installedPlugins = emptyList(),
                loadReports = emptyList(),
                filter = PluginDiagnosticsFilter.WARNING,
            )
        ).isTrue()
        assertThat(
            PluginsSettingsSectionSupport.shouldShowDiagnosticsFilteredEmptyState(
                installedPlugins = listOf(warningPlugin),
                loadReports = emptyList(),
                filter = PluginDiagnosticsFilter.WARNING,
            )
        ).isFalse()
    }

    @Test
    fun diagnosticSourceFilter_shouldToggleResolveAndFilterEntries() {
        val entries = listOf(
            PluginDiagnosticEntry(
                source = PluginDiagnosticSource.RUNTIME,
                issue = PluginDiagnosticIssue(
                    severity = PluginDiagnosticSeverity.ERROR,
                    category = PluginDiagnosticCategory.RUNTIME,
                    message = "Runtime crash"
                )
            ),
            PluginDiagnosticEntry(
                source = PluginDiagnosticSource.HEALTH,
                issue = PluginDiagnosticIssue(
                    severity = PluginDiagnosticSeverity.WARNING,
                    category = PluginDiagnosticCategory.COMPATIBILITY,
                    message = "Compatibility warning"
                )
            ),
        )

        assertThat(
            PluginsSettingsSectionSupport.togglePluginDiagnosticSourceFilter(
                currentFilter = PluginDiagnosticSourceFilter.RUNTIME,
                selectedFilter = PluginDiagnosticSourceFilter.RUNTIME,
            )
        ).isEqualTo(PluginDiagnosticSourceFilter.ALL)
        assertThat(
            PluginsSettingsSectionSupport.togglePluginDiagnosticSourceFilter(
                currentFilter = PluginDiagnosticSourceFilter.HEALTH,
                selectedFilter = PluginDiagnosticSourceFilter.RUNTIME,
            )
        ).isEqualTo(PluginDiagnosticSourceFilter.RUNTIME)

        assertThat(
            PluginsSettingsSectionSupport.resolveAvailablePluginDiagnosticSourceFilters(entries)
        ).containsExactly(
            PluginDiagnosticSourceFilter.ALL,
            PluginDiagnosticSourceFilter.HEALTH,
            PluginDiagnosticSourceFilter.RUNTIME,
        ).inOrder()
        assertThat(
            PluginsSettingsSectionSupport.resolvePluginDiagnosticSourceFilterOrAll(
                filter = PluginDiagnosticSourceFilter.RUNTIME,
                availableFilters = listOf(
                    PluginDiagnosticSourceFilter.ALL,
                    PluginDiagnosticSourceFilter.RUNTIME,
                ),
            )
        ).isEqualTo(PluginDiagnosticSourceFilter.RUNTIME)
        assertThat(
            PluginsSettingsSectionSupport.resolvePluginDiagnosticSourceFilterOrAll(
                filter = PluginDiagnosticSourceFilter.LOAD,
                availableFilters = listOf(
                    PluginDiagnosticSourceFilter.ALL,
                    PluginDiagnosticSourceFilter.RUNTIME,
                ),
            )
        ).isEqualTo(PluginDiagnosticSourceFilter.ALL)

        assertThat(
            PluginsSettingsSectionSupport.filterPluginDiagnosticEntriesBySource(
                entries = entries,
                filter = PluginDiagnosticSourceFilter.RUNTIME,
            )
        ).containsExactly(entries.first())
        assertThat(
            PluginsSettingsSectionSupport.filterPluginDiagnosticEntriesBySource(
                entries = entries,
                filter = PluginDiagnosticSourceFilter.LOAD,
            )
        ).isEmpty()
        assertThat(
            PluginsSettingsSectionSupport.shouldShowPluginDiagnosticSourceFilteredEmptyState(
                entries = emptyList(),
                filter = PluginDiagnosticSourceFilter.RUNTIME,
            )
        ).isTrue()
        assertThat(
            PluginsSettingsSectionSupport.shouldShowPluginDiagnosticSourceFilteredEmptyState(
                entries = listOf(entries.first()),
                filter = PluginDiagnosticSourceFilter.RUNTIME,
            )
        ).isFalse()
    }

    @Test
    fun diagnosticSourceFilter_shouldResolveOptionsAndFilterReports() {
        val mixedReport = PluginDiagnosticsReport(
            pluginId = "mixed.plugin",
            pluginName = "Mixed Plugin",
            isInstalled = true,
            entries = listOf(
                PluginDiagnosticEntry(
                    source = PluginDiagnosticSource.RUNTIME,
                    issue = PluginDiagnosticIssue(
                        severity = PluginDiagnosticSeverity.ERROR,
                        category = PluginDiagnosticCategory.RUNTIME,
                        message = "Runtime crash"
                    )
                ),
                PluginDiagnosticEntry(
                    source = PluginDiagnosticSource.HEALTH,
                    issue = PluginDiagnosticIssue(
                        severity = PluginDiagnosticSeverity.WARNING,
                        category = PluginDiagnosticCategory.COMPATIBILITY,
                        message = "Compatibility warning"
                    )
                ),
            )
        )
        val cleanReport = PluginDiagnosticsReport(
            pluginId = "clean.plugin",
            pluginName = "Clean Plugin",
            isInstalled = true,
            entries = emptyList()
        )
        val loadReport = PluginDiagnosticsReport(
            pluginId = null,
            pluginName = "Broken Plugin",
            directoryName = "broken-plugin",
            isInstalled = false,
            entries = listOf(
                PluginDiagnosticEntry(
                    source = PluginDiagnosticSource.LOAD,
                    issue = PluginDiagnosticIssue(
                        severity = PluginDiagnosticSeverity.ERROR,
                        category = PluginDiagnosticCategory.MANIFEST,
                        message = "Manifest missing"
                    )
                )
            )
        )

        assertThat(
            PluginsSettingsSectionSupport.resolvePluginDiagnosticSourceFilterOptions(
                installedReports = listOf(mixedReport, cleanReport),
                loadReports = listOf(loadReport),
            )
        ).containsExactly(
            PluginDiagnosticSourceFilterOption(
                filter = PluginDiagnosticSourceFilter.ALL,
                count = 3,
            ),
            PluginDiagnosticSourceFilterOption(
                filter = PluginDiagnosticSourceFilter.LOAD,
                count = 1,
            ),
            PluginDiagnosticSourceFilterOption(
                filter = PluginDiagnosticSourceFilter.HEALTH,
                count = 1,
            ),
            PluginDiagnosticSourceFilterOption(
                filter = PluginDiagnosticSourceFilter.RUNTIME,
                count = 1,
            ),
        ).inOrder()

        assertThat(
            PluginsSettingsSectionSupport.filterPluginDiagnosticsReportBySource(
                report = mixedReport,
                filter = PluginDiagnosticSourceFilter.RUNTIME,
            )
        ).isEqualTo(
            mixedReport.copy(
                entries = listOf(mixedReport.entries.first())
            )
        )
        assertThat(
            PluginsSettingsSectionSupport.filterPluginDiagnosticsReportBySource(
                report = mixedReport,
                filter = PluginDiagnosticSourceFilter.LOAD,
            )
        ).isNull()
        assertThat(
            PluginsSettingsSectionSupport.filterPluginDiagnosticsReportsBySource(
                reports = listOf(mixedReport, loadReport),
                filter = PluginDiagnosticSourceFilter.HEALTH,
            )
        ).containsExactly(
            mixedReport.copy(
                entries = listOf(mixedReport.entries.last())
            )
        )
        assertThat(
            PluginsSettingsSectionSupport.filterInstalledPluginDiagnosticsReportsBySource(
                reports = mapOf(
                    "mixed.plugin" to mixedReport,
                    "clean.plugin" to cleanReport,
                ),
                filter = PluginDiagnosticSourceFilter.RUNTIME,
            )
        ).containsExactly(
            "mixed.plugin",
            mixedReport.copy(
                entries = listOf(mixedReport.entries.first())
            )
        )
    }

    @Test
    fun diagnosticActions_shouldFollowSourceCategoryAndInstallState() {
        val runtimeEntry = PluginDiagnosticEntry(
            source = PluginDiagnosticSource.RUNTIME,
            issue = PluginDiagnosticIssue(
                severity = PluginDiagnosticSeverity.ERROR,
                category = PluginDiagnosticCategory.RUNTIME,
                message = "Runtime crash",
            ),
        )
        val permissionsEntry = PluginDiagnosticEntry(
            source = PluginDiagnosticSource.HEALTH,
            issue = PluginDiagnosticIssue(
                severity = PluginDiagnosticSeverity.WARNING,
                category = PluginDiagnosticCategory.PERMISSIONS,
                message = "Permission missing",
            ),
        )
        val loadEntry = PluginDiagnosticEntry(
            source = PluginDiagnosticSource.LOAD,
            issue = PluginDiagnosticIssue(
                severity = PluginDiagnosticSeverity.ERROR,
                category = PluginDiagnosticCategory.MANIFEST,
                message = "Manifest missing",
            ),
        )

        assertThat(
            PluginsSettingsSectionSupport.resolvePluginDiagnosticActions(
                entry = runtimeEntry,
                isScriptPlugin = true,
                isInstalled = true,
            )
        ).containsExactly(
            PluginDiagnosticAction.OPEN_LOGS,
            PluginDiagnosticAction.RELOAD_PLUGIN,
        ).inOrder()
        assertThat(
            PluginsSettingsSectionSupport.resolvePluginDiagnosticActions(
                entry = runtimeEntry,
                isScriptPlugin = false,
                isInstalled = true,
            )
        ).containsExactly(PluginDiagnosticAction.OPEN_LOGS)
        assertThat(
            PluginsSettingsSectionSupport.resolvePluginDiagnosticActions(
                entry = permissionsEntry,
                isScriptPlugin = true,
                isInstalled = true,
            )
        ).containsExactly(PluginDiagnosticAction.SHOW_PERMISSIONS)
        assertThat(
            PluginsSettingsSectionSupport.resolvePluginDiagnosticActions(
                entry = loadEntry,
                isScriptPlugin = true,
                isInstalled = false,
            )
        ).containsExactly(PluginDiagnosticAction.COPY_DIAGNOSTIC)
        assertThat(
            PluginsSettingsSectionSupport.resolvePluginDiagnosticActionLabelRes(
                PluginDiagnosticAction.SHOW_PERMISSIONS
            )
        ).isEqualTo(Strings.plugins_diagnostics_action_view_permissions)
        assertThat(
            PluginsSettingsSectionSupport.resolvePluginDiagnosticPreferredLogLevel(runtimeEntry)
        ).isEqualTo(PluginLogLevel.ERROR)
        val lspEntry = runtimeEntry.copy(
            issue = runtimeEntry.issue.copy(category = PluginDiagnosticCategory.LSP)
        )
        assertThat(
            PluginsSettingsSectionSupport.resolvePluginDiagnosticActions(
                entry = lspEntry,
                isScriptPlugin = true,
                isInstalled = true,
            )
        ).containsExactly(
            PluginDiagnosticAction.OPEN_LOGS,
            PluginDiagnosticAction.REPAIR_LSP_DEPENDENCIES,
        ).inOrder()
        assertThat(
            PluginsSettingsSectionSupport.resolvePluginDiagnosticActionLabelRes(
                PluginDiagnosticAction.REPAIR_LSP_DEPENDENCIES
            )
        ).isEqualTo(Strings.plugins_diagnostics_action_repair_lsp)
        assertThat(
            PluginsSettingsSectionSupport.resolvePluginDiagnosticPreferredLogLevel(permissionsEntry)
        ).isNull()
        assertThat(
            PluginsSettingsSectionSupport.resolvePluginDiagnosticPreferredLogLevel(
                runtimeEntry.copy(
                    issue = runtimeEntry.issue.copy(severity = PluginDiagnosticSeverity.WARNING)
                )
            )
        ).isEqualTo(PluginLogLevel.WARN)
    }

    @Test
    fun diagnosticClipboardText_shouldIncludeStableStructuredFields() {
        val entry = PluginDiagnosticEntry(
            source = PluginDiagnosticSource.RUNTIME,
            issue = PluginDiagnosticIssue(
                severity = PluginDiagnosticSeverity.ERROR,
                category = PluginDiagnosticCategory.RUNTIME,
                message = "Runtime crash",
                fixHint = "Reload plugin",
            ),
        )
        val report = PluginDiagnosticsReport(
            pluginId = "demo.plugin",
            pluginName = "Demo Plugin",
            directoryName = "demo-plugin",
            isInstalled = true,
            entries = listOf(entry),
        )

        val clipboardText = PluginsSettingsSectionSupport.buildPluginDiagnosticClipboardText(
            report = report,
            entry = entry,
        )

        assertThat(clipboardText).contains("Plugin diagnostics")
        assertThat(clipboardText).contains("pluginId: demo.plugin")
        assertThat(clipboardText).contains("pluginName: Demo Plugin")
        assertThat(clipboardText).contains("directoryName: demo-plugin")
        assertThat(clipboardText).contains("installed: true")
        assertThat(clipboardText).contains("source: RUNTIME")
        assertThat(clipboardText).contains("severity: ERROR")
        assertThat(clipboardText).contains("category: RUNTIME")
        assertThat(clipboardText).contains("message: Runtime crash")
        assertThat(clipboardText).contains("fixHint: Reload plugin")
    }

    @Test
    fun diagnosticsClipboardText_shouldIncludeEveryIssue() {
        val report = PluginDiagnosticsReport(
            pluginId = "demo.plugin",
            pluginName = "Demo Plugin",
            directoryName = "plugin-preflight",
            isInstalled = false,
            entries = listOf(
                PluginDiagnosticEntry(
                    source = PluginDiagnosticSource.LOAD,
                    issue = PluginDiagnosticIssue(
                        severity = PluginDiagnosticSeverity.ERROR,
                        category = PluginDiagnosticCategory.MANIFEST,
                        message = "Missing manifest",
                        fixHint = "Add manifest.json",
                    ),
                ),
                PluginDiagnosticEntry(
                    source = PluginDiagnosticSource.HEALTH,
                    issue = PluginDiagnosticIssue(
                        severity = PluginDiagnosticSeverity.WARNING,
                        category = PluginDiagnosticCategory.PERMISSIONS,
                        message = "Unknown permission",
                    ),
                ),
            ),
        )

        val clipboardText = PluginsSettingsSectionSupport.buildPluginDiagnosticsClipboardText(report)

        assertThat(clipboardText).contains("Plugin diagnostics")
        assertThat(clipboardText).contains("pluginId: demo.plugin")
        assertThat(clipboardText).contains("pluginName: Demo Plugin")
        assertThat(clipboardText).contains("directoryName: plugin-preflight")
        assertThat(clipboardText).contains("installed: false")
        assertThat(clipboardText).contains("issueCount: 2")
        assertThat(clipboardText).contains("issue[1]")
        assertThat(clipboardText).contains("source: LOAD")
        assertThat(clipboardText).contains("severity: ERROR")
        assertThat(clipboardText).contains("message: Missing manifest")
        assertThat(clipboardText).contains("fixHint: Add manifest.json")
        assertThat(clipboardText).contains("issue[2]")
        assertThat(clipboardText).contains("source: HEALTH")
        assertThat(clipboardText).contains("severity: WARNING")
        assertThat(clipboardText).contains("message: Unknown permission")
    }

    @Test
    fun commandContributionClipboardText_shouldIncludeStableStructuredFields() {
        val plugin = installedPlugin(
            id = "demo.plugin",
            name = "Demo Plugin",
        )
        val command = PluginsCommandContribution(
            surface = ResolvedPluginCommandSurface.EDITOR_TOOLBAR,
            commandId = "demo.run",
            title = "Run Demo",
            group = "navigation",
            source = ResolvedPluginCommandSource.PLUGIN,
            status = PluginCommandContributionStatus.UNAVAILABLE,
            whenExpression = "editorLang == cpp",
            statusMessage = "Permission command.execute is not granted",
        )

        val clipboardText = PluginsSettingsSectionSupport.buildPluginCommandContributionClipboardText(
            plugin = plugin,
            command = command,
        )

        assertThat(clipboardText).contains("Plugin command contribution")
        assertThat(clipboardText).contains("pluginId: demo.plugin")
        assertThat(clipboardText).contains("pluginName: Demo Plugin")
        assertThat(clipboardText).contains("directoryName: demo.plugin")
        assertThat(clipboardText).contains("enabled: true")
        assertThat(clipboardText).contains("surface: EDITOR_TOOLBAR")
        assertThat(clipboardText).contains("source: PLUGIN")
        assertThat(clipboardText).contains("status: UNAVAILABLE")
        assertThat(clipboardText).contains("commandId: demo.run")
        assertThat(clipboardText).contains("title: Run Demo")
        assertThat(clipboardText).contains("group: navigation")
        assertThat(clipboardText).contains("when: editorLang == cpp")
        assertThat(clipboardText).contains("reason: Permission command.execute is not granted")
    }

    @Test
    fun buildPluginPreflightLogMessage_shouldSummarizeStatusAndIssues() {
        val report = PluginDiagnosticsReport(
            pluginId = "demo.plugin",
            pluginName = "Demo Plugin",
            isInstalled = false,
            entries = listOf(
                PluginDiagnosticEntry(
                    source = PluginDiagnosticSource.LOAD,
                    issue = PluginDiagnosticIssue(
                        severity = PluginDiagnosticSeverity.ERROR,
                        category = PluginDiagnosticCategory.MANIFEST,
                        message = "Missing manifest",
                        fixHint = "Add manifest.json",
                    ),
                ),
                PluginDiagnosticEntry(
                    source = PluginDiagnosticSource.HEALTH,
                    issue = PluginDiagnosticIssue(
                        severity = PluginDiagnosticSeverity.WARNING,
                        category = PluginDiagnosticCategory.PERMISSIONS,
                        message = "Unknown permission",
                    ),
                ),
            ),
        )

        val blockedMessage = PluginsSettingsSectionSupport.buildPluginPreflightLogMessage(
            report = report,
            blocked = true,
        )
        val warningMessage = PluginsSettingsSectionSupport.buildPluginPreflightLogMessage(
            report = report.copy(entries = report.entries.drop(1)),
            blocked = false,
        )

        assertThat(blockedMessage).contains("Install preflight blocked")
        assertThat(blockedMessage).contains("pluginId=demo.plugin")
        assertThat(blockedMessage).contains("pluginName=Demo Plugin")
        assertThat(blockedMessage).contains("errors=1")
        assertThat(blockedMessage).contains("warnings=1")
        assertThat(blockedMessage).contains("LOAD/ERROR/MANIFEST: Missing manifest")
        assertThat(blockedMessage).contains("fix: Add manifest.json")
        assertThat(warningMessage).contains("Install preflight warning")
        assertThat(warningMessage).contains("errors=0")
        assertThat(warningMessage).contains("warnings=1")
        assertThat(warningMessage).contains("HEALTH/WARNING/PERMISSIONS: Unknown permission")
    }

    @Test
    fun resolveInstalledPluginDetailDiagnosticsReport_shouldPreferDoctorHealthButKeepRuntime() {
        val plugin = installedPlugin(
            id = "demo.plugin",
            name = "Demo Plugin",
        )
        val runtimeReport = PluginDiagnosticsReport(
            pluginId = plugin.manifest.id,
            pluginName = plugin.manifest.name,
            directoryName = "demo-plugin",
            isInstalled = true,
            entries = listOf(
                PluginDiagnosticEntry(
                    source = PluginDiagnosticSource.RUNTIME,
                    issue = PluginDiagnosticIssue(
                        severity = PluginDiagnosticSeverity.ERROR,
                        category = PluginDiagnosticCategory.RUNTIME,
                        message = "Runtime crash",
                    )
                )
            )
        )
        val doctorReport = PluginDiagnosticsReport(
            pluginId = plugin.manifest.id,
            pluginName = plugin.manifest.name,
            directoryName = "demo-plugin",
            isInstalled = false,
            entries = listOf(
                PluginDiagnosticEntry(
                    source = PluginDiagnosticSource.HEALTH,
                    issue = PluginDiagnosticIssue(
                        severity = PluginDiagnosticSeverity.WARNING,
                        category = PluginDiagnosticCategory.CONTRIBUTIONS,
                        message = "Menu command missing declaration",
                    )
                )
            )
        )

        val merged = PluginsSettingsSectionSupport.resolveInstalledPluginDetailDiagnosticsReport(
            plugin = plugin,
            snapshotReport = runtimeReport,
            manualDoctorReport = doctorReport,
        )

        assertThat(merged.isInstalled).isTrue()
        assertThat(merged.entries.map { it.source }).containsExactly(
            PluginDiagnosticSource.RUNTIME,
            PluginDiagnosticSource.HEALTH,
        ).inOrder()
    }

    @Test
    fun resolveInstalledPluginDetailDiagnosticsReport_shouldKeepLoadIssueStandalone() {
        val plugin = installedPlugin(
            id = "demo.plugin",
            name = "Demo Plugin",
        )
        val snapshotReport = PluginDiagnosticsReport(
            pluginId = plugin.manifest.id,
            pluginName = plugin.manifest.name,
            directoryName = "demo-plugin",
            isInstalled = true,
            entries = listOf(
                PluginDiagnosticEntry(
                    source = PluginDiagnosticSource.RUNTIME,
                    issue = PluginDiagnosticIssue(
                        severity = PluginDiagnosticSeverity.ERROR,
                        category = PluginDiagnosticCategory.RUNTIME,
                        message = "Runtime crash",
                    )
                )
            )
        )
        val loadDoctorReport = PluginDiagnosticsReport(
            pluginId = plugin.manifest.id,
            pluginName = plugin.manifest.name,
            directoryName = "demo-plugin",
            isInstalled = false,
            entries = listOf(
                PluginDiagnosticEntry(
                    source = PluginDiagnosticSource.LOAD,
                    issue = PluginDiagnosticIssue(
                        severity = PluginDiagnosticSeverity.ERROR,
                        category = PluginDiagnosticCategory.MANIFEST,
                        message = "Manifest missing",
                    )
                )
            )
        )

        val resolved = PluginsSettingsSectionSupport.resolveInstalledPluginDetailDiagnosticsReport(
            plugin = plugin,
            snapshotReport = snapshotReport,
            manualDoctorReport = loadDoctorReport,
        )

        assertThat(resolved).isEqualTo(loadDoctorReport)
    }

    private fun installedPlugin(
        id: String,
        name: String,
        isBundled: Boolean = false,
        contributions: PluginContributions? = null,
    ): InstalledPlugin = InstalledPlugin(
        manifest = PluginManifest(
            id = id,
            name = name,
            version = "1.0.0",
            contributions = contributions,
            isBundled = isBundled,
        ),
        directory = File("build/$id"),
        enabled = true,
    )
}
