package com.wuxianggujun.tinaide.core.linuxdistro

import java.io.File

class LinuxDistroManager(
    private val catalog: LinuxDistroCatalog,
    private val layout: LinuxDistroInstallLayout,
    private val installer: LinuxDistroInstaller = LinuxDistroInstaller(catalog),
    private val registry: LinuxDistroInstallationRegistry = FileLinuxDistroInstallationRegistry(
        File(layout.runtimeDir, "linux-distro-registry.json"),
    ),
    private val metadataStore: LinuxDistroInstallMetadataStore = JsonLinuxDistroInstallMetadataStore(),
    private val rootfsProbe: LinuxDistroRootfsProbe = BasicLinuxDistroRootfsProbe,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    fun listAvailable(): List<DistroDefinition> = catalog.listDistros()

    fun resolveDistro(distroId: String): DistroDefinition? = catalog.resolveDistro(distroId)

    fun listInstalled(syncFromDisk: Boolean = true): List<InstalledLinuxDistro> = if (syncFromDisk) refreshInstalledFromDisk() else registry.list()

    fun isInstalled(distroId: String): Boolean {
        require(distroId.isSafeId()) { "Unsafe distro id: $distroId" }
        val rootfsDir = layout.rootfsDir(distroId)
        return rootfsProbe.hasBootShell(rootfsDir)
    }

    suspend fun install(
        distroId: String,
        architecture: DistroArchitecture,
        releaseId: String? = null,
        reinstall: Boolean = false,
        rootfsConfig: LinuxDistroRootfsConfig = LinuxDistroRootfsConfig(),
        progress: (LinuxDistroInstallProgress) -> Unit = {},
    ): LinuxDistroInstallResult {
        val result = installer.install(
            request = LinuxDistroInstallRequest(
                distroId = distroId,
                architecture = architecture,
                layout = layout,
                releaseId = releaseId,
                reinstall = reinstall,
                rootfsConfig = rootfsConfig,
            ),
            progress = progress,
        )
        registry.upsert(result.installation)
        return result
    }

    fun uninstall(distroId: String): Boolean {
        require(distroId.isSafeId()) { "Unsafe distro id: $distroId" }
        val rootfsDir = layout.rootfsDir(distroId)
        val deletedRootfs = rootfsDir.exists() && rootfsDir.deleteRecursively()
        val removedRegistry = registry.remove(distroId)
        return deletedRootfs || removedRegistry
    }

    fun refreshInstalledFromDisk(): List<InstalledLinuxDistro> {
        val installedRoot = layout.installedRootfsDir
        val installations = installedRoot.listFiles()
            .orEmpty()
            .filter { rootfsDir -> rootfsProbe.hasBootShell(rootfsDir) }
            .mapNotNull { rootfsDir -> metadataStore.read(rootfsDir) ?: inferInstallation(rootfsDir) }
            .sortedBy { installation -> installation.displayName.lowercase() }
        registry.replaceAll(installations)
        return installations
    }

    private fun inferInstallation(rootfsDir: File): InstalledLinuxDistro? {
        val distroId = rootfsDir.name.takeIf { it.isSafeId() } ?: return null
        val distro = catalog.resolveDistro(distroId)
        val timestamp = rootfsDir.lastModified().takeIf { it > 0L } ?: clock()
        return InstalledLinuxDistro(
            distroId = distroId,
            releaseId = distro?.defaultReleaseId,
            architecture = null,
            displayName = distro?.displayName ?: distroId,
            packageManager = distro?.packageManager ?: DistroPackageManager.UNKNOWN,
            rootfsPath = rootfsDir.absolutePath,
            archivePath = null,
            checksum = null,
            installedAtEpochMillis = timestamp,
            updatedAtEpochMillis = timestamp,
        )
    }
}
