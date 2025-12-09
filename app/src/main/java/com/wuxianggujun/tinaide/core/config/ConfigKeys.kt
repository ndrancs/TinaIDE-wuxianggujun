package com.wuxianggujun.tinaide.core.config

/**
 * 类型安全的配置键定义（按 AI 方案）
 */
sealed class ConfigKey<T>(val key: String, val default: T) {

    // UI 相关
    object Theme : ConfigKey<String>("ui.theme", "DARK")

    // 面板可见性：ui.panel.<PANEL_NAME> -> Boolean
    class PanelVisible(panelName: String, defaultVisible: Boolean) :
        ConfigKey<Boolean>("ui.panel.$panelName", defaultVisible)

    // 项目相关
    object ProjectRootDir : ConfigKey<String>(
        key = "project.root_dir",
        default = ""
    )

    object CurrentProject : ConfigKey<String>(
        key = "file.current_project",
        default = ""
    )

    object RecentFiles : ConfigKey<String>(
        key = "file.recent_files",
        default = ""
    )

    // 输出相关
    object OutputMode : ConfigKey<String>(
        key = "output.mode",
        default = "ACTIVITY"
    )

    // LSP / clangd 配置
    object LspCompletionLimit : ConfigKey<Int>(
        key = "lsp.completion.limit",
        default = 50
    )
}

/**
 * 便于集中管理的 ConfigKeys 别名
 */
object ConfigKeys {
    val Theme = ConfigKey.Theme
    fun panelVisible(panelName: String, defaultVisible: Boolean) =
        ConfigKey.PanelVisible(panelName, defaultVisible)

    val ProjectRootDir = ConfigKey.ProjectRootDir
    val CurrentProject = ConfigKey.CurrentProject
    val RecentFiles = ConfigKey.RecentFiles
    val OutputMode = ConfigKey.OutputMode
    val LspCompletionLimit = ConfigKey.LspCompletionLimit
}
