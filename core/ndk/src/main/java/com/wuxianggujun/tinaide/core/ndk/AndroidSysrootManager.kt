package com.wuxianggujun.tinaide.core.ndk

import android.content.Context
import com.wuxianggujun.tinaide.core.common.io.TarExtractor
import com.wuxianggujun.tinaide.core.i18n.AppStrings
import com.wuxianggujun.tinaide.core.i18n.Strings
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.Properties
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber

/**
 * Android Sysroot 管理器
 *
 * 功能：
 * - 从 assets 解压统一的 sysroot 包
 * - 管理 sysroot 路径和版本
 * - 为编译器提供正确的编译参数
 *
 * Sysroot 结构：
 * ```
 * android-sysroot/
 * ├── usr/
 * │   ├── include/              # 通用头文件（所有 API 共享）
 * │   │   └── <triple>/         # 架构特定头文件
 * │   └── lib/
 * │       └── <triple>/
 * │           ├── 21/           # API 21 的库
 * │           ├── 24/           # API 24 的库
 * │           └── ...
 * └── .version                  # 版本信息
 * ```
 */
class AndroidSysrootManager(private val context: Context) {

    companion object {
        private const val TAG = "SysrootManager"

        // Assets 中的 sysroot 包名
        private const val SYSROOT_ASSET_ARM64 = "android-sysroot/android-sysroot-arm64-all.tar.xz"
        private const val SYSROOT_ASSET_X86_64 = "android-sysroot/android-sysroot-x86_64-all.tar.xz"

        // 解压后的目录名
        private const val SYSROOT_DIR_NAME = "android-sysroot"

        // 支持的架构
        enum class Arch(val triple: String, val assetPath: String) {
            ARM64("aarch64-linux-android", SYSROOT_ASSET_ARM64),
            X86_64("x86_64-linux-android", SYSROOT_ASSET_X86_64);

            companion object {
                fun current(): Arch {
                    val abi = android.os.Build.SUPPORTED_ABIS[0]
                    return when {
                        abi.startsWith("arm64") || abi.startsWith("aarch64") -> ARM64
                        abi.startsWith("x86_64") -> X86_64
                        else -> ARM64 // 默认 ARM64
                    }
                }
            }
        }
    }

    // Sysroot 根目录
    private val sysrootBaseDir: File
        get() = File(context.filesDir, SYSROOT_DIR_NAME)

    /**
     * 检查 sysroot 是否已安装
     */
    fun isInstalled(arch: Arch = Arch.current()): Boolean {
        val sysrootDir = getSysrootDir(arch)
        if (!sysrootDir.exists()) return false

        // 检查关键目录是否存在
        val includeDir = File(sysrootDir, "usr/include")
        val libDir = File(sysrootDir, "usr/lib/${arch.triple}")
        val versionFile = File(sysrootDir, ".version")

        return includeDir.exists() && libDir.exists() && versionFile.exists()
    }

    /**
     * 从外部文件导入 sysroot
     *
     * @param archiveFile sysroot 压缩包文件（支持 .tar.xz / .tar.gz / .tar）
     * @param arch 目标架构
     * @param onProgress 进度回调 (0.0 - 1.0)
     * @return 导入结果
     */
    suspend fun importFromFile(
        archiveFile: File,
        arch: Arch = Arch.current(),
        onProgress: ((Float) -> Unit)? = null
    ): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            if (!archiveFile.exists() || !archiveFile.isFile) {
                return@withContext Result.failure(
                    IllegalArgumentException(
                        AppStrings.get(
                            Strings.sysroot_import_archive_not_found,
                            archiveFile.absolutePath
                        )
                    )
                )
            }

            Timber.tag(TAG).i("Importing sysroot from file: %s", archiveFile.absolutePath)

            val tempDir = File(context.cacheDir, "sysroot-import-${System.currentTimeMillis()}")
            tempDir.mkdirs()

            try {
                onProgress?.invoke(0.1f)

                // 解压到临时目录
                TarExtractor.extract(archiveFile, tempDir) { progress ->
                    onProgress?.invoke(0.1f + progress * 0.7f)
                }

                onProgress?.invoke(0.8f)

                // 查找解压后的 sysroot 目录
                val extractedDir = File(tempDir, SYSROOT_DIR_NAME)
                if (!extractedDir.exists()) {
                    return@withContext Result.failure(
                        IllegalStateException(
                            AppStrings.get(
                                Strings.sysroot_import_invalid_archive_missing_dir,
                                SYSROOT_DIR_NAME
                            )
                        )
                    )
                }

                // 验证 sysroot 完整性
                val includeDir = File(extractedDir, "usr/include")
                val libDir = File(extractedDir, "usr/lib/${arch.triple}")

                if (!includeDir.exists() || !libDir.exists()) {
                    return@withContext Result.failure(
                        IllegalStateException(
                            AppStrings.get(
                                Strings.sysroot_import_invalid_missing_paths,
                                "usr/include",
                                "usr/lib/${arch.triple}"
                            )
                        )
                    )
                }

                onProgress?.invoke(0.9f)

                // 移动到目标位置
                val targetDir = getSysrootDir(arch)
                targetDir.parentFile?.mkdirs()

                if (targetDir.exists()) {
                    targetDir.deleteRecursively()
                }

                if (!extractedDir.renameTo(targetDir)) {
                    extractedDir.copyRecursively(targetDir, overwrite = true)
                    extractedDir.deleteRecursively()
                }

                onProgress?.invoke(1.0f)
                Timber.tag(TAG).i("Sysroot imported successfully: %s", targetDir)

                Result.success(Unit)
            } finally {
                tempDir.deleteRecursively()
            }
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Sysroot import failed")
            Result.failure(e)
        }
    }

    /**
     * 安装 sysroot（从 assets 解压）
     *
     * @param arch 目标架构
     * @param onProgress 进度回调 (0.0 - 1.0)
     */
    suspend fun install(
        arch: Arch = Arch.current(),
        onProgress: ((Float) -> Unit)? = null
    ): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            Timber.tag(TAG).i("Starting sysroot installation: %s", arch)

            // 检查 assets 文件是否存在
            val assetExists = try {
                context.assets.open(arch.assetPath).use { true }
            } catch (e: Exception) {
                false
            }

            if (!assetExists) {
                return@withContext Result.failure(
                    IllegalStateException(
                        AppStrings.get(
                            Strings.sysroot_asset_not_found,
                            arch.assetPath
                        )
                    )
                )
            }

            // 创建临时目录
            val tempDir = File(context.cacheDir, "sysroot-install-${System.currentTimeMillis()}")
            tempDir.mkdirs()

            try {
                // 1. 复制 tar.xz 到临时目录
                onProgress?.invoke(0.1f)
                val tarFile = File(tempDir, "sysroot.tar.xz")
                context.assets.open(arch.assetPath).use { input ->
                    FileOutputStream(tarFile).use { output ->
                        input.copyTo(output)
                    }
                }
                Timber.tag(TAG).i("Copied tar.xz file: %d bytes", tarFile.length())

                // 2. 解压 tar.xz（复用 TarExtractor）
                onProgress?.invoke(0.3f)
                try {
                    TarExtractor.extract(tarFile, tempDir) { progress ->
                        onProgress?.invoke(0.3f + progress * 0.6f)
                    }
                } catch (e: Exception) {
                    Timber.tag(TAG).e(e, "Failed to extract tar.xz")
                    return@withContext Result.failure(e)
                }

                // 3. 移动到最终位置
                onProgress?.invoke(0.9f)
                val extractedDir = File(tempDir, SYSROOT_DIR_NAME)
                if (!extractedDir.exists()) {
                    return@withContext Result.failure(
                        IllegalStateException(
                            AppStrings.get(
                                Strings.sysroot_install_extracted_dir_missing,
                                extractedDir.absolutePath
                            )
                        )
                    )
                }

                val targetDir = getSysrootDir(arch)
                targetDir.parentFile?.mkdirs()

                // 删除旧的 sysroot
                if (targetDir.exists()) {
                    targetDir.deleteRecursively()
                }

                // 移动到目标位置
                if (!extractedDir.renameTo(targetDir)) {
                    // 如果 rename 失败，尝试复制
                    extractedDir.copyRecursively(targetDir, overwrite = true)
                    extractedDir.deleteRecursively()
                }

                onProgress?.invoke(1.0f)
                Timber.tag(TAG).i("Sysroot installation complete: %s", targetDir)

                Result.success(Unit)
            } finally {
                // 清理临时目录
                tempDir.deleteRecursively()
            }
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Sysroot installation failed")
            Result.failure(e)
        }
    }

    /**
     * 获取 sysroot 目录
     */
    fun getSysrootDir(arch: Arch = Arch.current()): File = sysrootBaseDir

    /**
     * 获取 sysroot 路径（用于编译器 --sysroot 参数）
     */
    fun getSysrootPath(arch: Arch = Arch.current()): String? {
        val dir = getSysrootDir(arch)
        return if (dir.exists()) dir.absolutePath else null
    }

    /**
     * 获取指定 API 级别的库路径
     */
    fun getLibPath(apiLevel: Int, arch: Arch = Arch.current()): String? {
        val sysroot = getSysrootDir(arch)
        val libDir = File(sysroot, "usr/lib/${arch.triple}/$apiLevel")
        return if (libDir.exists()) libDir.absolutePath else null
    }

    /**
     * 获取头文件路径
     */
    fun getIncludePath(arch: Arch = Arch.current()): String? {
        val sysroot = getSysrootDir(arch)
        val includeDir = File(sysroot, "usr/include")
        return if (includeDir.exists()) includeDir.absolutePath else null
    }

    /**
     * 获取编译器参数
     *
     * @param apiLevel 目标 API 级别
     * @param arch 目标架构
     * @param isCpp 是否为 C++ 编译（决定是否添加 C++ 标准库头文件路径）
     * @return 编译器参数列表
     */
    fun getCompilerFlags(
        apiLevel: Int,
        arch: Arch = Arch.current(),
        isCpp: Boolean = false
    ): List<String> {
        val sysroot = getSysrootPath(arch) ?: return emptyList()
        val libPath = getLibPath(apiLevel, arch) ?: return emptyList()

        return buildList {
            add("--target=${arch.triple}$apiLevel")
            add("--sysroot=$sysroot")
            // C++ 标准库头文件必须在 C 标准库之前（关键修复）
            if (isCpp) {
                add("-isystem")
                add("$sysroot/usr/include/c++/v1")
            }
            add("-I$sysroot/usr/include")
            add("-I$sysroot/usr/include/${arch.triple}")
            add("-L$libPath")
        }
    }

    /**
     * 获取 sysroot 版本信息
     */
    fun getVersion(arch: Arch = Arch.current()): SysrootVersion? {
        val versionFile = File(getSysrootDir(arch), ".version")
        if (!versionFile.exists()) return null

        return try {
            val props = Properties()
            FileInputStream(versionFile).use { props.load(it) }

            SysrootVersion(
                arch = props.getProperty("ARCH"),
                abi = props.getProperty("ABI"),
                apiLevels = props.getProperty("API_LEVELS")?.split(" ")?.mapNotNull { it.toIntOrNull() } ?: emptyList(),
                toolchainTriple = props.getProperty("TOOLCHAIN_TRIPLE"),
                createdAt = props.getProperty("CREATED_AT")
            )
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Failed to read version info")
            null
        }
    }

    /**
     * 卸载 sysroot
     */
    fun uninstall(arch: Arch = Arch.current()): Boolean {
        val dir = getSysrootDir(arch)
        return if (dir.exists()) {
            dir.deleteRecursively()
        } else {
            true
        }
    }

    /**
     * Sysroot 版本信息
     */
    data class SysrootVersion(
        val arch: String,
        val abi: String,
        val apiLevels: List<Int>,
        val toolchainTriple: String,
        val createdAt: String
    )
}
