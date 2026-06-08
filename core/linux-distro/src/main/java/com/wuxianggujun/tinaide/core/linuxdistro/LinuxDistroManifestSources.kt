package com.wuxianggujun.tinaide.core.linuxdistro

import android.content.Context

interface LinuxDistroManifestSource {
    fun loadManifest(): LinuxDistroManifest
}

class AndroidAssetLinuxDistroManifestSource(
    context: Context,
    private val assetPath: String = DEFAULT_ASSET_PATH,
) : LinuxDistroManifestSource {
    private val appContext = context.applicationContext

    override fun loadManifest(): LinuxDistroManifest = appContext.assets.open(assetPath).use { input -> LinuxDistroManifestParser.decode(input) }

    companion object {
        const val DEFAULT_ASSET_PATH = "linux-distro/manifest.json"
    }
}

fun LinuxDistroManifestSource.loadCatalog(): LinuxDistroCatalog = ManifestLinuxDistroCatalog(loadManifest())
