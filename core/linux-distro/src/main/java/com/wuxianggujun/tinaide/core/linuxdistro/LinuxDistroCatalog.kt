package com.wuxianggujun.tinaide.core.linuxdistro

interface LinuxDistroCatalog {
    fun listDistros(): List<DistroDefinition>
    fun resolveDistro(distroId: String): DistroDefinition?

    /** 清单级镜像回落规则；默认无镜像。 */
    fun mirrorRules(): List<DistroMirrorRule> = emptyList()

    fun resolveArtifact(
        distroId: String,
        architecture: DistroArchitecture,
        releaseId: String? = null,
    ): ResolvedDistroArtifact? {
        val distro = resolveDistro(distroId) ?: return null
        val release = distro.release(releaseId) ?: return null
        val artifact = release.artifactFor(architecture) ?: return null
        return ResolvedDistroArtifact(distro, release, artifact)
    }

    fun listInstallableDefaultArtifacts(architecture: DistroArchitecture): List<ResolvedDistroArtifact> {
        return listDistros().mapNotNull { distro ->
            val release = distro.defaultRelease() ?: return@mapNotNull null
            val artifact = release.artifactFor(architecture) ?: return@mapNotNull null
            ResolvedDistroArtifact(distro, release, artifact)
        }
    }
}

class ManifestLinuxDistroCatalog(
    manifest: LinuxDistroManifest,
) : LinuxDistroCatalog {
    private val distros = manifest.distros.sortedBy { distro -> distro.displayName.lowercase() }
    private val distroIndex = distros.associateBy { distro -> distro.id }
    private val mirrors = manifest.mirrors

    init {
        require(distroIndex.size == distros.size) { "Distro ids must be unique." }
    }

    override fun listDistros(): List<DistroDefinition> = distros

    override fun resolveDistro(distroId: String): DistroDefinition? = distroIndex[distroId]

    override fun mirrorRules(): List<DistroMirrorRule> = mirrors
}
