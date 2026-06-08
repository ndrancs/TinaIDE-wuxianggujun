package com.wuxianggujun.tinaide.core.packages

import android.content.Context
import com.wuxianggujun.tinaide.core.common.io.ArchivePathSafety
import com.wuxianggujun.tinaide.core.common.io.TarExtractor
import com.wuxianggujun.tinaide.core.packages.model.InstallType
import com.wuxianggujun.tinaide.core.packages.model.Platform
import com.wuxianggujun.tinaide.core.packages.store.LocalInstallStateStore
import com.wuxianggujun.tinaide.core.serialization.JsonSerializer
import java.io.File
import java.util.zip.ZipFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import timber.log.Timber

/**
 * 内置包安装器
 *
 * 负责从 assets/bundled_packages/ 解压预编译的库到 filesDir/installed-packages/
 * 支持多种压缩格式：.tar.xz, .tar.zst, .tar.gz, .zip
 * 并自动解析 package.json 元数据，更新安装状态
 */
class BundledPackagesInstaller(
    private val context: Context,
    private val installStateStore: LocalInstallStateStore
) {
    companion object {
        private const val TAG = "BundledPackagesInstaller"
        private const val ASSET_DIR = "bundled_packages"
        private const val INSTALL_DIR_NAME = "installed-packages"
    }

    private val json = JsonSerializer.default

    private val installDir: File by lazy {
        File(context.filesDir, INSTALL_DIR_NAME).also { it.mkdirs() }
    }

    /**
     * 安装所有内置包
     *
     * 扫描 assets/bundled_packages/ 目录，解压所有支持的压缩包
     * 支持格式: .tar.xz, .tar.zst, .tar.gz, .zip
     *
     * @param forceReinstall 是否强制重新安装（删除已有目录）
     */
    suspend fun installBundledPackages(forceReinstall: Boolean = false) = withContext(Dispatchers.IO) {
        val assetManager = context.assets
        val entries = runCatching {
            assetManager.list(ASSET_DIR).orEmpty().toList()
        }.getOrDefault(emptyList())

        if (entries.isEmpty()) {
            Timber.tag(TAG).d("No bundled packages found in assets")
            return@withContext
        }

        Timber.tag(TAG).i("Found ${entries.size} bundled package(s)")

        for (entry in entries) {
            val packageInfo = parsePackageFileName(entry) ?: continue
            val assetPath = "$ASSET_DIR/$entry"

            try {
                installPackageFromAsset(packageInfo.id, assetPath, packageInfo.format, forceReinstall)
            } catch (e: Exception) {
                Timber.tag(TAG).e(e, "Failed to install bundled package: ${packageInfo.id}")
            }
        }
    }

    /**
     * 解析包文件名，提取包 ID 和压缩格式
     */
    private fun parsePackageFileName(fileName: String): PackageFileInfo? = when {
        fileName.endsWith(".tar.xz", ignoreCase = true) ->
            PackageFileInfo(fileName.removeSuffix(".tar.xz"), CompressionFormat.TAR_XZ)
        fileName.endsWith(".tar.zst", ignoreCase = true) ->
            PackageFileInfo(fileName.removeSuffix(".tar.zst"), CompressionFormat.TAR_ZSTD)
        fileName.endsWith(".tar.gz", ignoreCase = true) ->
            PackageFileInfo(fileName.removeSuffix(".tar.gz"), CompressionFormat.TAR_GZ)
        fileName.endsWith(".zip", ignoreCase = true) ->
            PackageFileInfo(fileName.removeSuffix(".zip"), CompressionFormat.ZIP)
        else -> null
    }

    private data class PackageFileInfo(
        val id: String,
        val format: CompressionFormat
    )

    private enum class CompressionFormat {
        TAR_XZ,
        TAR_ZSTD,
        TAR_GZ,
        ZIP;

        fun toTarExtractorType(): TarExtractor.CompressionType? = when (this) {
            TAR_XZ -> TarExtractor.CompressionType.XZ
            TAR_ZSTD -> TarExtractor.CompressionType.ZSTD
            TAR_GZ -> TarExtractor.CompressionType.GZIP
            ZIP -> null // ZIP 单独处理
        }
    }

    /**
     * 从 assets 安装单个包
     */
    private fun installPackageFromAsset(
        packageId: String,
        assetPath: String,
        format: CompressionFormat,
        forceReinstall: Boolean = false
    ) {
        val targetDir = File(installDir, packageId)
        val metadataFile = File(targetDir, "package.json")

        // 强制重装：删除已有目录
        if (forceReinstall && targetDir.exists()) {
            Timber.tag(TAG).i("Force reinstall: deleting existing package $packageId")
            targetDir.deleteRecursively()
        }

        // 检查是否已安装（避免重复解压）
        if (targetDir.exists() && targetDir.listFiles()?.isNotEmpty() == true) {
            if (!metadataFile.isFile) {
                Timber.tag(TAG).w(
                    "Package %s is installed but missing package.json, reinstalling bundled asset metadata",
                    packageId
                )
                targetDir.deleteRecursively()
            } else {
                Timber.tag(TAG).d("Package $packageId already installed, skipping")
                return
            }
        }

        if (targetDir.exists() && targetDir.listFiles()?.isNotEmpty() == true) {
            Timber.tag(TAG).d("Package $packageId already installed, skipping")
            return
        }

        Timber.tag(TAG).i("Installing bundled package: $packageId from $assetPath (format: $format)")

        // 复制到临时文件
        val tempFile = File(context.cacheDir, "bundled_$packageId.tmp")
        try {
            context.assets.open(assetPath).use { input ->
                tempFile.outputStream().use { output ->
                    input.copyTo(output)
                }
            }

            // 根据格式解压
            targetDir.mkdirs()
            when (format) {
                CompressionFormat.ZIP -> extractZip(tempFile, targetDir)
                else -> {
                    // 使用 TarExtractor 统一处理 tar.xz/tar.zst/tar.gz
                    val compressionType = format.toTarExtractorType()
                        ?: throw IllegalStateException("Unsupported format: $format")
                    tempFile.inputStream().use { input ->
                        TarExtractor.extract(input, targetDir, compressionType)
                    }
                }
            }

            // 读取 package.json 获取元数据
            val metadata = readPackageMetadata(targetDir)

            if (metadata != null) {
                // 更新安装状态
                installStateStore.setInstalled(
                    packageId = metadata.id,
                    platform = Platform.ANDROID,
                    version = metadata.version,
                    packageName = metadata.name,
                    installType = InstallType.DOWNLOAD,
                    size = calculatePackageSize(targetDir),
                    isBundled = true // 标记为内置包
                )

                Timber.tag(TAG).i("✓ Bundled package installed: ${metadata.name} v${metadata.version}")
            } else {
                Timber.tag(TAG).w("No package.json found for $packageId, using defaults")

                // 没有 metadata，使用默认值
                installStateStore.setInstalled(
                    packageId = packageId,
                    platform = Platform.ANDROID,
                    version = "unknown",
                    packageName = packageId,
                    installType = InstallType.DOWNLOAD,
                    size = calculatePackageSize(targetDir),
                    isBundled = true // 标记为内置包
                )
            }
        } finally {
            tempFile.delete()
        }
    }

    /**
     * 解压 ZIP 格式
     */
    private fun extractZip(archiveFile: File, targetDir: File) {
        ZipFile(archiveFile).use { zip ->
            zip.entries().asSequence().forEach { entry ->
                val entryFile = ArchivePathSafety.resolveEntryFile(targetDir, entry.name, "zip entry")
                if (entry.isDirectory) {
                    entryFile.mkdirs()
                } else {
                    entryFile.parentFile?.mkdirs()
                    zip.getInputStream(entry).use { input ->
                        entryFile.outputStream().use { output ->
                            input.copyTo(output)
                        }
                    }
                }
            }
        }
    }

    /**
     * 读取包元数据
     */
    private fun readPackageMetadata(packageDir: File): PackageMetadata? {
        val metadataFile = File(packageDir, "package.json")
        if (!metadataFile.exists()) return null

        return try {
            JsonSerializer.decodeFromFile<PackageMetadata>(metadataFile)
        } catch (e: Exception) {
            Timber.tag(TAG).w(e, "Failed to parse package.json")
            null
        }
    }

    /**
     * 计算包大小
     */
    private fun calculatePackageSize(dir: File): Long = dir.walkTopDown()
        .filter { it.isFile }
        .map { it.length() }
        .sum()

    /**
     * 包元数据（对应 package.json）
     */
    @Serializable
    data class PackageMetadata(
        val id: String,
        val name: String,
        val version: String,
        val packageRevision: Int? = null,
        val upstreamName: String? = null,
        val upstreamVersion: String? = null,
        val upstreamTag: String? = null,
        val upstreamCommit: String? = null,
        val description: String? = null,
        val platform: String? = null,
        val artifactType: String? = null,
        val installType: String? = null,
        val category: String? = null,
        val homepage: String? = null,
        val license: String? = null,
        val installedAt: Long? = null,
        val files: PackageFiles? = null,
        val abis: List<String>? = null
    )

    @Serializable
    data class PackageFiles(
        val include: String? = null,
        val source: String? = null,
        val lib: String? = null,
        val pkgconfig: String? = null
    )
}
