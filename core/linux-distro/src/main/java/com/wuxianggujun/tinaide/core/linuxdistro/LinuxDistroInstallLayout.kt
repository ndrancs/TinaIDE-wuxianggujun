package com.wuxianggujun.tinaide.core.linuxdistro

import java.io.File

data class LinuxDistroInstallLayout(
    val runtimeDir: File,
    val downloadCacheDir: File = File(runtimeDir, "downloads"),
    val installedRootfsDir: File = File(runtimeDir, "installed-rootfs"),
    val stagingDir: File = File(runtimeDir, "staging"),
) {
    fun ensureDirectories() {
        downloadCacheDir.mkdirs()
        installedRootfsDir.mkdirs()
        stagingDir.mkdirs()
    }

    fun rootfsDir(distroId: String): File {
        require(distroId.isSafeId()) { "Unsafe distro id: $distroId" }
        return File(installedRootfsDir, distroId)
    }

    fun archiveFile(resolved: ResolvedDistroArtifact): File {
        val fileName = buildString {
            append(resolved.distro.id)
            append('-')
            append(resolved.release.id)
            append('-')
            append(resolved.artifact.architecture.name.lowercase())
            append('.')
            append(resolved.artifact.format.fileExtension)
        }
        return File(downloadCacheDir, fileName)
    }

    fun newStagingRootfsDir(distroId: String): File {
        require(distroId.isSafeId()) { "Unsafe distro id: $distroId" }
        return File(stagingDir, "$distroId-${System.currentTimeMillis()}")
    }
}

val DistroArchiveFormat.fileExtension: String
    get() = when (this) {
        DistroArchiveFormat.TAR -> "tar"
        DistroArchiveFormat.TAR_GZ -> "tar.gz"
        DistroArchiveFormat.TAR_XZ -> "tar.xz"
        DistroArchiveFormat.TAR_ZST -> "tar.zst"
    }
