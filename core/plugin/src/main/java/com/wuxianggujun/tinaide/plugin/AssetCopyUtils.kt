package com.wuxianggujun.tinaide.plugin

import android.content.Context
import java.io.File

object AssetCopyUtils {

    fun copyAssetDirTo(
        context: Context,
        assetDirPath: String,
        destDir: File
    ) {
        destDir.mkdirs()
        val assetManager = context.assets

        val children = assetManager.list(assetDirPath).orEmpty()
        require(children.isNotEmpty()) { "Asset is not a directory: $assetDirPath" }

        for (child in children) {
            val childAssetPath = if (assetDirPath.isBlank()) child else "$assetDirPath/$child"
            val grandChildren = assetManager.list(childAssetPath).orEmpty()
            if (grandChildren.isEmpty()) {
                val outFile = File(destDir, child)
                outFile.parentFile?.mkdirs()
                assetManager.open(childAssetPath).use { input ->
                    outFile.outputStream().use { output ->
                        input.copyTo(output)
                    }
                }
            } else {
                copyAssetDirTo(context, childAssetPath, File(destDir, child))
            }
        }
    }

    fun copyAssetFileTo(
        context: Context,
        assetFilePath: String,
        destFile: File
    ) {
        destFile.parentFile?.mkdirs()
        context.assets.open(assetFilePath).use { input ->
            destFile.outputStream().use { output ->
                input.copyTo(output)
            }
        }
    }
}
