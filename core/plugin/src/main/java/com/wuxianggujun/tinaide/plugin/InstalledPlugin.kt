package com.wuxianggujun.tinaide.plugin

import java.io.File

data class InstalledPlugin(
    val manifest: PluginManifest,
    val directory: File,
    val enabled: Boolean
)
