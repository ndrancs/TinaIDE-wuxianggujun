package com.wuxianggujun.tinaide.plugin

import android.content.Context
import java.io.File
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber

/**
 * 将 assets 中的内置插件（测试/官方）安装到 filesDir/plugins。
 *
 * 约定目录结构：
 * assets/bundled_plugins/<pluginId>/manifest.json + 其他资源
 */
class BundledPluginsInstaller(
    private val context: Context,
    private val pluginManager: PluginManager
) {
    companion object {
        private const val TAG = "BundledPluginsInstaller"
        private val ASSET_ROOTS = listOf(
            "bundled_plugins",
            "plugins"
        )
    }

    suspend fun installOrUpdateBundledPlugins() = withContext(Dispatchers.IO) {
        val assetManager = context.assets
        var installedAny = false

        for (assetRoot in ASSET_ROOTS) {
            val entries = runCatching { assetManager.list(assetRoot).orEmpty().toList() }.getOrDefault(emptyList())
            if (entries.isEmpty()) continue

            for (entry in entries) {
                val assetPath = "$assetRoot/$entry"
                val children = runCatching { assetManager.list(assetPath).orEmpty() }.getOrDefault(emptyArray())
                val isDirectory = children.isNotEmpty()

                val tempDir = File(context.cacheDir, "bundled_plugin_${UUID.randomUUID()}")
                try {
                    tempDir.mkdirs()
                    val manifest = if (isDirectory) {
                        AssetCopyUtils.copyAssetDirTo(context, assetPath, tempDir)
                        pluginManager.installPluginFromDirectory(tempDir, allowSkipIfSameVersion = true, markAsBundled = true)
                    } else if (entry.endsWith(".tinaplug", ignoreCase = true) || entry.endsWith(".zip", ignoreCase = true)) {
                        val tempZip = File(context.cacheDir, "bundled_plugin_${UUID.randomUUID()}.zip")
                        try {
                            AssetCopyUtils.copyAssetFileTo(context, assetPath, tempZip)
                            ZipUtils.unzipToDirectory(tempZip, tempDir)
                            pluginManager.installPluginFromDirectory(tempDir, allowSkipIfSameVersion = true, markAsBundled = true)
                        } finally {
                            if (tempZip.exists()) tempZip.delete()
                        }
                    } else {
                        null
                    }

                    if (manifest != null) {
                        installedAny = true
                        Timber.tag(TAG).i("Bundled plugin installed/updated: ${manifest.id}@${manifest.version} (asset=$assetPath)")
                    }
                } catch (t: Throwable) {
                    Timber.tag(TAG).w(t, "Failed to install bundled plugin from assets: $assetPath")
                } finally {
                    if (tempDir.exists()) tempDir.deleteRecursively()
                }
            }
        }

        if (installedAny) {
            pluginManager.refreshInstalledPlugins()
        }
    }
}
