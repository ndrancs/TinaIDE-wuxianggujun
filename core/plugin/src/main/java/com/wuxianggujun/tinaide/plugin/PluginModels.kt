package com.wuxianggujun.tinaide.plugin

import kotlinx.serialization.Serializable
import kotlinx.serialization.SerialName
import com.wuxianggujun.tinaide.plugin.lsp.LspServerConfig
import com.wuxianggujun.tinaide.plugin.lsp.LspToolchainConfig
import com.wuxianggujun.tinaide.project.ProjectApkExportType
import java.io.File

/**
 * 插件清单（manifest.json）
 */
@Serializable
data class PluginManifest(
    val id: String,
    val name: String,
    val version: String,
    val apiVersion: Int = 1,
    val minAppVersion: String? = null,
    val author: PluginAuthor? = null,
    val description: String? = null,
    val license: String? = null,
    val repository: String? = null,
    val type: String = "config", // config, script, hybrid, lsp, system
    val capabilities: List<String>? = null,
    val lifecycle: PluginLifecycle? = null,
    val main: String? = null, // Entry script file (e.g., "main.lua")
    val contributions: PluginContributions? = null,
    val activationEvents: List<String>? = null,
    val permissions: List<String>? = null,
    val optionalPermissions: List<String>? = null,
    val networkHosts: List<String>? = null,
    val isBundled: Boolean = false // 是否为内置插件（从 assets/bundled_plugins 或 assets/plugins 安装）
)

@Serializable
data class PluginLifecycle(
    val requiresSetup: Boolean = false
)

@Serializable
data class PluginAuthor(
    val name: String,
    val email: String? = null,
    val url: String? = null
)

@Serializable
data class PluginContributions(
    val themes: List<String>? = null,
    val snippets: List<String>? = null,
    val keybindings: List<String>? = null,
    val projectTemplates: List<PluginProjectTemplate>? = null,
    val apkExports: List<PluginApkExport>? = null,
    val commands: List<PluginCommand>? = null,
    val menus: PluginMenus? = null,
    val fileIcons: List<PluginFileIcon>? = null,
    val panels: List<PluginPanel>? = null,
    // LSP 插件贡献
    val languageServers: List<LspServerConfig>? = null,
    val toolchains: List<LspToolchainConfig>? = null
)

@Serializable
data class PluginProjectTemplate(
    val id: String,
    val name: String,
    val description: String,
    val templatePath: String,
    val buildSystem: String,
    val primaryLanguage: String = "CPP",
    val isNdkTemplate: Boolean = false
)

@Serializable
data class PluginApkExport(
    val id: String,
    val name: String,
    val description: String? = null,
    val projectTypes: List<String> = emptyList(),
    val templateType: String,
    val templatePath: String
)

data class ResolvedPluginApkExport(
    val optionId: String,
    val pluginId: String,
    val exportId: String,
    val displayName: String,
    val description: String?,
    val projectTypes: Set<ProjectApkExportType>,
    val templateType: String,
    val templateFile: File
)

@Serializable
data class PluginCommand(
    val id: String,
    val title: String,
    val icon: String? = null
)

@Serializable
data class PluginMenus(
    @SerialName("editor/context")
    val editorContext: List<PluginMenuItem>? = null,
    @SerialName("editor/toolbar")
    val editorToolbar: List<PluginMenuItem>? = null,
    @SerialName("filetree/context")
    val fileTreeContext: List<PluginMenuItem>? = null
)

@Serializable
data class PluginMenuItem(
    val command: String,
    val group: String? = null,
    val `when`: String? = null
)

@Serializable
data class PluginFileIcon(
    val icon: String,
    val extensions: List<String>? = null,
    val fileNames: List<String>? = null,
    val priority: Int = 0
)

@Serializable
data class PluginPanel(
    val id: String,
    val title: String,
    val icon: String? = null
)

data class ResolvedPluginFileIcon(
    val pluginId: String,
    val iconSpec: String,
    val iconFile: File?,
    val extensions: Set<String>,
    val fileNames: Set<String>,
    val priority: Int
)

/**
 * 主题配置（插件贡献）
 *
 * 当前最小实现仅解析 colors（颜色 ID/常量名 -> 颜色值）。
 */
@Serializable
data class ThemeConfig(
    val name: String,
    val type: String = "dark", // dark, light
    val colors: Map<String, String>,
    val tokenColors: List<TokenColor>? = null
)

@Serializable
data class TokenColor(
    val scope: List<String>,
    val settings: TokenSettings
)

@Serializable
data class TokenSettings(
    val foreground: String? = null,
    val fontStyle: String? = null
)

/**
 * 代码片段（预留）
 */
@Serializable
data class SnippetFile(
    val language: String,
    val snippets: List<Snippet>
)

@Serializable
data class Snippet(
    val prefix: String,
    val name: String,
    val description: String? = null,
    val body: List<String>
)

/**
 * 快捷键绑定（预留）
 */
@Serializable
data class KeyBinding(
    val key: String,
    val command: String,
    val `when`: String? = null
)
