package com.wuxianggujun.tinaide.core.linuxdistro

import com.wuxianggujun.tinaide.core.common.io.TarExtractor
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient

@Serializable
enum class DistroFamily {
    ALPINE,
    DEBIAN,
    UBUNTU,
    ARCH,
    FEDORA,
    OPENSUSE,
    VOID,
    CUSTOM,
}

@Serializable
enum class DistroPackageManager {
    APK,
    APT,
    PACMAN,
    DNF,
    ZYPPER,
    XBPS,
    UNKNOWN,
}

@Serializable
enum class DistroArchitecture(val androidAbis: Set<String>) {
    AARCH64(setOf("arm64-v8a")),
    ARM(setOf("armeabi-v7a")),
    X86_64(setOf("x86_64")),
    I686(setOf("x86"));

    companion object {
        fun fromAndroidAbi(abi: String): DistroArchitecture? = entries.firstOrNull { architecture -> abi in architecture.androidAbis }
    }
}

@Serializable
enum class DistroArchiveFormat {
    TAR,
    TAR_GZ,
    TAR_XZ,
    TAR_ZST;

    fun compressionType(): TarExtractor.CompressionType = when (this) {
        TAR -> TarExtractor.CompressionType.NONE
        TAR_GZ -> TarExtractor.CompressionType.GZIP
        TAR_XZ -> TarExtractor.CompressionType.XZ
        TAR_ZST -> TarExtractor.CompressionType.ZSTD
    }
}

@Serializable
enum class DistroChecksumAlgorithm {
    SHA256,
}

@Serializable
data class DistroChecksum(
    val algorithm: DistroChecksumAlgorithm,
    val value: String,
) {
    init {
        require(value.isNotBlank()) { "Checksum value must not be blank." }
    }

    @Transient
    val normalizedValue: String = value.lowercase()
}

@Serializable
data class DistroArtifact(
    val architecture: DistroArchitecture,
    val url: String,
    val format: DistroArchiveFormat,
    val checksum: DistroChecksum,
    val sizeBytes: Long? = null,
    val signatureUrl: String? = null,
) {
    init {
        require(url.startsWith("https://") || url.startsWith("http://")) {
            "Artifact URL must be http(s): $url"
        }
        require(sizeBytes == null || sizeBytes > 0L) { "Artifact size must be positive when provided." }
    }
}

@Serializable
data class DistroRelease(
    val id: String,
    val version: String,
    val displayName: String,
    val channel: String = "stable",
    val artifacts: List<DistroArtifact>,
) {
    init {
        require(id.isSafeId()) { "Unsafe release id: $id" }
        require(displayName.isNotBlank()) { "Release display name must not be blank." }
    }

    fun artifactFor(architecture: DistroArchitecture): DistroArtifact? = artifacts.firstOrNull { artifact -> artifact.architecture == architecture }
}

@Serializable
data class DistroDefinition(
    val id: String,
    val family: DistroFamily,
    val displayName: String,
    val packageManager: DistroPackageManager,
    val defaultReleaseId: String,
    val homepageUrl: String? = null,
    val releases: List<DistroRelease>,
) {
    init {
        require(id.isSafeId()) { "Unsafe distro id: $id" }
        require(displayName.isNotBlank()) { "Distro display name must not be blank." }
        require(defaultReleaseId.isSafeId()) { "Unsafe default release id: $defaultReleaseId" }
        require(releases.isNotEmpty()) { "Distro must define at least one release: $id" }
        require(releases.map { it.id }.distinct().size == releases.size) {
            "Distro releases must be unique: $id"
        }
        require(releases.any { it.id == defaultReleaseId }) {
            "Distro default release must exist: $id/$defaultReleaseId"
        }
    }

    fun defaultRelease(): DistroRelease? = releases.firstOrNull { release -> release.id == defaultReleaseId }

    fun release(releaseId: String?): DistroRelease? = if (releaseId.isNullOrBlank()) defaultRelease() else releases.firstOrNull { it.id == releaseId }
}

data class ResolvedDistroArtifact(
    val distro: DistroDefinition,
    val release: DistroRelease,
    val artifact: DistroArtifact,
)

internal fun String.isSafeId(): Boolean = isNotBlank() && all { char -> char.isLetterOrDigit() || char == '-' || char == '_' || char == '.' }
